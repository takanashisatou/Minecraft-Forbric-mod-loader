/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The dispatch scope and self-check behind every facade-composed hook pair, exercised against the real
 * {@code ComposedHook} — compiled from {@code src/runtime/java} in-test, the way {@code KernelGameLookupTest}
 * compiles {@code KernelGameLookupHelper}: the game-side classes are not on the test classpath, and compiling
 * the two sources here is what lets this suite run on a machine with no staged game jars at all.
 */
class ComposedHookTest {
	private static final Path RUNTIME_SRC = Path.of("src/runtime/java/net/forbric/kernel/runtime");

	@TempDir Path temporary;
	private int manifests;

	/** ComposedHook and ComposedHookManifest, compiled from source into a loader of their own. */
	private final class Fixture implements AutoCloseable {
		final URLClassLoader loader;
		final Class<?> hook;
		private final Path classes;

		Fixture(List<String> facadePairIds) throws Exception {
			classes = Files.createDirectories(temporary.resolve("classes"));
			List<String> arguments = new ArrayList<>(List.of("--release", "21", "-proc:none", "-d", classes.toString(),
					RUNTIME_SRC.resolve("ComposedHook.java").toString(),
					RUNTIME_SRC.resolve("ComposedHookManifest.java").toString()));
			ByteArrayOutputStream errors = new ByteArrayOutputStream();
			assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, errors, errors,
					arguments.toArray(String[]::new)), errors.toString());
			for (String pairId : facadePairIds) addFacade(pairId);
			loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
			hook = Class.forName("net.forbric.kernel.runtime.ComposedHook", true, loader);
		}

		/** The merge tool resolves a facade by NAME; this declares one, exactly as enabling a real pair will. */
		private void addFacade(String pairId) throws Exception {
			Path compiled = classes.resolve("net/forbric/kernel/runtime/ComposedHook.class");
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			new ClassReader(Files.readAllBytes(compiled)).accept(new ClassVisitor(Opcodes.ASM9, writer) {
				@Override public void visitEnd() {
					MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, pairId,
							"()V", null, null);
					mv.visitCode();
					mv.visitInsn(Opcodes.RETURN);
					mv.visitMaxs(0, 0);
					mv.visitEnd();
					super.visitEnd();
				}
			}, 0);
			Files.write(compiled, writer.toByteArray());
		}

		void enter(String pairId) throws Exception { scope("enter", pairId); }
		void exit(String pairId) throws Exception { scope("exit", pairId); }

		private void scope(String name, String pairId) throws Exception {
			var method = hook.getDeclaredMethod(name, String.class);
			method.setAccessible(true);
			try {
				method.invoke(null, pairId);
			} catch (InvocationTargetException wrapped) {
				throw unwrap(wrapped);
			}
		}

		boolean inDispatch(String pairId) throws Exception {
			return (boolean) hook.getMethod("inDispatch", String.class).invoke(null, pairId);
		}

		/** verifySelf reads the manifest through the thread's context classloader, as it will in the game. */
		void verifySelf(ClassLoader manifestSource) throws Exception {
			ClassLoader previous = Thread.currentThread().getContextClassLoader();
			Thread.currentThread().setContextClassLoader(manifestSource);
			try {
				hook.getMethod("verifySelf").invoke(null);
			} catch (InvocationTargetException wrapped) {
				throw unwrap(wrapped);
			} finally {
				Thread.currentThread().setContextClassLoader(previous);
			}
		}

		@Override public void close() throws Exception { loader.close(); }
	}

	private static RuntimeException unwrap(InvocationTargetException wrapped) throws Exception {
		if (wrapped.getCause() instanceof RuntimeException failure) return failure;
		if (wrapped.getCause() instanceof Error failure) throw failure;
		throw new AssertionError(wrapped.getCause());
	}

	/** A loader holding exactly one resource: the manifest with {@code content}, or none when null. */
	private URLClassLoader manifest(String content) throws Exception {
		Path dir = Files.createDirectories(temporary.resolve("manifest" + manifests++));
		if (content != null) {
			Path file = dir.resolve("META-INF/forbric/restored-hooks.txt");
			Files.createDirectories(file.getParent());
			Files.writeString(file, content, StandardCharsets.UTF_8);
		}
		return new URLClassLoader(new URL[] {dir.toUri().toURL()}, null);
	}

	private static final String PORTAL_ROW = "portalSpawn net/minecraft/world/level/block/BaseFireBlock#onPlace"
			+ "(Lnet/minecraft/world/level/block/state/BlockState;)V paired-facade";

	@Test void enterMarksAPairInDispatchUntilItsOwnExit() throws Exception {
		try (Fixture f = new Fixture(List.of())) {
			assertFalse(f.inDispatch("portalSpawn"));
			f.enter("portalSpawn");
			assertTrue(f.inDispatch("portalSpawn"));
			assertFalse(f.inDispatch("useItemFinish"), "the scope is per pair id, not per class");
			f.exit("portalSpawn");
			assertFalse(f.inDispatch("portalSpawn"));
		}
	}

	@Test void nestedPairsKeepTheOuterDispatchOpen() throws Exception {
		try (Fixture f = new Fixture(List.of())) {
			f.enter("outer"); f.enter("inner");
			assertTrue(f.inDispatch("outer")); assertTrue(f.inDispatch("inner"));
			f.exit("inner");
			assertTrue(f.inDispatch("outer"), "closing the inner pair must not close the outer one");
			assertFalse(f.inDispatch("inner"));
			f.exit("outer");
			assertFalse(f.inDispatch("outer"));
		}
	}

	@Test void reentrantDispatchOfOnePairStaysOpenUntilItsOwnExitPerFrame() throws Exception {
		try (Fixture f = new Fixture(List.of())) {
			// A listener inside the NeoForge half triggers the same call site again: the same id, twice.
			f.enter("p"); f.enter("p");
			f.exit("p");
			assertTrue(f.inDispatch("p"), "the outer frame of the re-entered pair is still open");
			f.exit("p");
			assertFalse(f.inDispatch("p"));
		}
	}

	@Test void aDispatchIsInvisibleToOtherThreads() throws Exception {
		try (Fixture f = new Fixture(List.of())) {
			f.enter("p");
			CompletableFuture.runAsync(() -> {
				try {
					assertFalse(f.inDispatch("p"), "the scope is thread-local");
					f.enter("p"); assertTrue(f.inDispatch("p")); f.exit("p"); assertFalse(f.inDispatch("p"));
				} catch (Exception failure) { throw new AssertionError(failure); }
			}).join();
			assertTrue(f.inDispatch("p"), "the other thread's enter/exit left this thread's dispatch untouched");
			f.exit("p");
			assertFalse(f.inDispatch("p"));
		}
	}

	@Test void exitWithoutAMatchingEnterFailsLoudlyAndPopsNothing() throws Exception {
		try (Fixture f = new Fixture(List.of())) {
			assertThrows(IllegalStateException.class, () -> f.exit("p"), "no dispatch is open at all");
			f.enter("a"); f.enter("b");
			IllegalStateException failure = assertThrows(IllegalStateException.class, () -> f.exit("a"),
					"the innermost open dispatch is b's, not a's");
			assertTrue(failure.getMessage().contains("\"b\""), "the message names what IS open: " + failure);
			assertTrue(f.inDispatch("a")); assertTrue(f.inDispatch("b"));
			f.exit("b"); f.exit("a");
			assertFalse(f.inDispatch("a")); assertFalse(f.inDispatch("b"));
		}
	}

	@Test void theFacadePatternClearsTheScopeWhenTheHookThrows() throws Exception {
		try (Fixture f = new Fixture(List.of())) {
			RuntimeException listener = new IllegalStateException("listener");
			// The documented facade body, literally: enter, try the hooks, exit in finally.
			RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
				f.enter("p");
				try {
					throw listener;
				} finally {
					f.exit("p");
				}
			});
			assertSame(listener, thrown, "exit in finally does not mask the hook's failure");
			assertFalse(f.inDispatch("p"), "a failing listener leaves no phantom dispatch open");
			f.enter("p"); f.exit("p");
			assertFalse(f.inDispatch("p"));
		}
	}

	@Test void verifySelfPassesWhenNothingWasRestored() throws Exception {
		try (Fixture f = new Fixture(List.of());
				URLClassLoader absent = manifest(null);
				URLClassLoader headerOnly = manifest("# Restored hook pairs, composed through ComposedHook.\n"
						+ "# <pairId> <neoOwner>.<name><desc> <forgeOwner>.<name><desc>... none yet.\n\n")) {
			f.verifySelf(absent);
			f.verifySelf(headerOnly);
		}
	}

	@Test void verifySelfFailsWhenTheManifestNamesAPairWithNoFacadeMethod() throws Exception {
		try (Fixture f = new Fixture(List.of());
				URLClassLoader twoPairs = manifest(PORTAL_ROW + "\n"
						+ "useItemFinish net/minecraft/world/item/ItemStack#finishUsingItem"
						+ "(Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/item/ItemStack; paired-facade\n")) {
			IllegalStateException failure = assertThrows(IllegalStateException.class, () -> f.verifySelf(twoPairs));
			assertTrue(failure.getMessage().contains("portalSpawn"), "names the pair id: " + failure.getMessage());
			assertTrue(failure.getMessage().contains("useItemFinish"), failure.getMessage());
		}
	}

	@Test void verifySelfCountsAPackagePrivateMethodAsNoFacade() throws Exception {
		// "enter" exists but is not public, and the merged base calls the facade from another package: a
		// package-private facade is the same NoSuchMethodError-shaped failure one step earlier, so it must not pass.
		try (Fixture f = new Fixture(List.of());
				URLClassLoader enterPair = manifest("enter net/x/A#m()V paired-facade\n")) {
			IllegalStateException failure = assertThrows(IllegalStateException.class, () -> f.verifySelf(enterPair));
			assertTrue(failure.getMessage().contains("enter"), failure.getMessage());
		}
	}

	@Test void verifySelfPassesWhenEveryNamedPairHasAFacadeMethod() throws Exception {
		try (Fixture f = new Fixture(List.of("portalSpawn"));
				URLClassLoader onePair = manifest(PORTAL_ROW + "\n")) {
			f.verifySelf(onePair);
		}
	}
}
