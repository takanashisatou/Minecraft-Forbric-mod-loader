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
import java.util.Optional;
import java.util.Set;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The restored-hooks manifest reader, exercised against the real {@code ComposedHookManifest} compiled from
 * {@code src/runtime/java} in-test — the game-side classes are not on the test classpath, and compiling the
 * source here is what lets this suite run on a machine with no staged game jars (same pattern as
 * {@code KernelGameLookupTest}).
 */
class ComposedHookManifestTest {
	private static final Path SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/ComposedHookManifest.java");

	@TempDir Path temporary;
	private int manifests;

	/** ComposedHookManifest, compiled from source into a loader of its own. */
	private final class Fixture implements AutoCloseable {
		final URLClassLoader loader;
		final Class<?> manifest, row;
		private final Path classes;

		Fixture() throws Exception {
			classes = Files.createDirectories(temporary.resolve("classes"));
			List<String> arguments = new ArrayList<>(List.of("--release", "21", "-proc:none", "-d", classes.toString(),
					SOURCE.toString()));
			ByteArrayOutputStream errors = new ByteArrayOutputStream();
			assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, errors, errors,
					arguments.toArray(String[]::new)), errors.toString());
			loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
			manifest = Class.forName("net.forbric.kernel.runtime.ComposedHookManifest", true, loader);
			row = Class.forName("net.forbric.kernel.runtime.ComposedHookManifest$Row", true, loader);
		}

		/** Gives this fixture's own loader a manifest, for the fallback half of the load() chain. */
		void installInOwnLoader(String content) throws Exception {
			Path file = classes.resolve("META-INF/forbric/restored-hooks.txt");
			Files.createDirectories(file.getParent());
			Files.writeString(file, content, StandardCharsets.UTF_8);
		}

		Object load(ClassLoader source) throws Exception {
			try {
				return manifest.getMethod("load", ClassLoader.class).invoke(null, source);
			} catch (InvocationTargetException wrapped) {
				throw unwrap(wrapped);
			}
		}

		Object loadInContext(ClassLoader context) throws Exception {
			ClassLoader previous = Thread.currentThread().getContextClassLoader();
			Thread.currentThread().setContextClassLoader(context);
			try {
				return manifest.getMethod("load").invoke(null);
			} catch (InvocationTargetException wrapped) {
				throw unwrap(wrapped);
			} finally {
				Thread.currentThread().setContextClassLoader(previous);
			}
		}

		@SuppressWarnings("unchecked") Set<String> pairIds(Object m) throws Exception {
			return (Set<String>) manifest.getMethod("pairIds").invoke(m);
		}

		Optional<?> row(Object m, String pairId) throws Exception {
			return (Optional<?>) manifest.getMethod("row", String.class).invoke(m, pairId);
		}

		String field(Object rowInstance, String accessor) throws Exception {
			return (String) row.getMethod(accessor).invoke(rowInstance);
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
	private static final String FINISH_ROW = "useItemFinish net/minecraft/world/item/ItemStack#finishUsingItem"
			+ "(Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/item/ItemStack; paired-facade";

	@Test void absentResourceIsTheEmptyManifest() throws Exception {
		try (Fixture f = new Fixture(); URLClassLoader none = manifest(null)) {
			Object m = f.load(none);
			assertTrue(f.pairIds(m).isEmpty(), "a merged base with no manifest restored nothing");
			assertTrue(f.row(m, "portalSpawn").isEmpty());
		}
	}

	@Test void headerAndBlankLinesOnlyIsTheEmptyManifest() throws Exception {
		// The shipped seed state: MergedBaseBuilder embeds the file with only its header while the pair table is empty.
		try (Fixture f = new Fixture();
				URLClassLoader header = manifest("# Restored hook pairs composed through ComposedHook.\n"
						+ "# <pairId> <hostInternalName>#<hostName><hostDesc> paired-facade\n\n  \n")) {
			assertTrue(f.pairIds(f.load(header)).isEmpty());
		}
	}

	@Test void rowsParseInOrderAndAnswerLookupByPairId() throws Exception {
		try (Fixture f = new Fixture(); URLClassLoader two = manifest("# header\n" + PORTAL_ROW + "\n" + FINISH_ROW + "\n")) {
			Object m = f.load(two);
			assertEquals(List.of("portalSpawn", "useItemFinish"), List.copyOf(f.pairIds(m)),
					"manifest order is preserved, so logs read like the file");
			Object portal = f.row(m, "portalSpawn").orElseThrow();
			assertEquals("portalSpawn", f.field(portal, "pairId"));
			assertEquals("net/minecraft/world/level/block/BaseFireBlock", f.field(portal, "host"));
			assertEquals("onPlace", f.field(portal, "name"));
			assertEquals("(Lnet/minecraft/world/level/block/state/BlockState;)V", f.field(portal, "desc"));
			assertEquals("net/minecraft/world/item/ItemStack", f.field(f.row(m, "useItemFinish").orElseThrow(), "host"));
			assertTrue(f.row(m, "nobody").isEmpty());
			assertThrows(UnsupportedOperationException.class, () -> f.pairIds(m).clear(),
					"the manifest is a fact about the merged base, not mutable state");
		}
	}

	@Test void malformedRowsFailTheWholeLoadNamingTheLine() throws Exception {
		List<String> malformed = List.of(
				"portalSpawn",                                          // one token
				PORTAL_ROW + " trailing",                               // four tokens
				"portalSpawn net/x/A#m()V paired-raw",                  // a kind this version is not
				"portalSpawn noHash paired-facade",                     // site without '#'
				"portalSpawn net/x/A# paired-facade",                   // nothing after '#'
				"portalSpawn #m()V paired-facade",                      // empty host
				"portalSpawn net/x/A#m paired-facade",                  // name without a descriptor
				"portal-spawn net/x/A#m()V paired-facade",              // not spellable as a method name
				"9lives net/x/A#m()V paired-facade",                    // does not start as an identifier
				PORTAL_ROW + "\n" + PORTAL_ROW);                        // one site per pair id, this version
		try (Fixture f = new Fixture()) {
			for (String content : malformed) {
				try (URLClassLoader bad = manifest(content)) {
					assertThrows(IllegalStateException.class, () -> f.load(bad), "accepted: " + content);
				}
			}
			// A good row does not redeem a bad one: half a manifest is a restored pair with no known stand-down.
			try (URLClassLoader bad = manifest(PORTAL_ROW + "\nbroken row here\n")) {
				IllegalStateException failure = assertThrows(IllegalStateException.class, () -> f.load(bad));
				assertTrue(failure.getMessage().contains("line 2"), "names the offending line: " + failure.getMessage());
			}
		}
	}

	@Test void crlfAndSurroundingWhitespaceAreTolerated() throws Exception {
		try (Fixture f = new Fixture();
				URLClassLoader crlf = manifest("# header\r\n\r\n  " + PORTAL_ROW + "\r\n")) {
			assertEquals(Set.of("portalSpawn"), f.pairIds(f.load(crlf)));
		}
	}

	@Test void theContextClassLoaderIsPreferredAndItsAbsenceFallsBack() throws Exception {
		try (Fixture f = new Fixture();
				URLClassLoader contextHasIt = manifest(FINISH_ROW + "\n");
				URLClassLoader contextLacksIt = manifest(null)) {
			f.installInOwnLoader(PORTAL_ROW + "\n");
			Object preferred = f.loadInContext(contextHasIt);
			assertEquals(Set.of("useItemFinish"), f.pairIds(preferred), "the context loader answers first");
			Object fallback = f.loadInContext(contextLacksIt);
			assertEquals(Set.of("portalSpawn"), f.pairIds(fallback),
					"a context loader without the resource falls through to the facade classes' own loader");
			Object nullContext = f.loadInContext(null);
			assertEquals(Set.of("portalSpawn"), f.pairIds(nullContext));
		}
	}

	@Test void aMalformedManifestFailsRatherThanFallingThrough() throws Exception {
		try (Fixture f = new Fixture(); URLClassLoader bad = manifest("broken row\n")) {
			f.installInOwnLoader(PORTAL_ROW + "\n");
			assertThrows(IllegalStateException.class, () -> f.loadInContext(bad),
					"absence is soft and falls through; a malformed manifest is a build bug and must not be hidden "
							+ "by the fallback loader's good copy");
		}
	}
}
