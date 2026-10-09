/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The kernel's reader for {@code META-INF/forbric/restored-hooks.txt}, the manifest {@code MergedBaseBuilder}
 * embeds in the merged base jar: one row per facade-composed site,
 * {@code <pairId> <hostInternalName>#<hostName><hostDesc> paired-facade}, with {@code #} comment lines and blank
 * lines ignored. It exists apart from {@link ComposedHook} because the manifest is a file-format concern —
 * parsing, validation, classloader lookup — while {@code ComposedHook} is the dispatch scope;
 * {@link ComposedHook#verifySelf()} is where the two meet.
 *
 * <p>With the seed pair table empty the file carries only its header comment and this manifest is empty; a
 * merged base built before the manifest existed has no resource at all, which is ALSO the empty manifest rather
 * than an error. Fail-closed on anything else: a row that does not parse is a merge/build bug, and half-reading
 * the file would let a restored pair run without the runtime knowing its stand-down scope, so the whole load
 * fails naming the line. A pair id on two rows is refused the same way: if one pair is ever composed into two
 * host methods, the format grows deliberately rather than silently.
 *
 * <p>Not cached: the answer is a function of the classloader asked, and a cached empty from an early call on
 * the wrong loader would hide the manifest the real game loader can see.
 */
public final class ComposedHookManifest {
	/** The resource path inside the merged base jar. */
	public static final String RESOURCE = "META-INF/forbric/restored-hooks.txt";

	/** The only row kind this version reads; anything else is a manifest from a build this kernel is not. */
	private static final String KIND = "paired-facade";

	private static final ComposedHookManifest EMPTY = new ComposedHookManifest(Map.of());

	/** One facade-composed site: pair {@code pairId} restored inside {@code host}'s {@code name}{@code desc}. */
	public record Row(String pairId, String host, String name, String desc) { }

	private final Map<String, Row> rows;

	private ComposedHookManifest(Map<String, Row> rows) {
		this.rows = rows;
	}

	/** Every pair id the merged base was built with, in manifest order. */
	public Set<String> pairIds() {
		return rows.keySet();
	}

	/** The row for {@code pairId}, or empty when the merged base was not built with that pair. */
	public Optional<Row> row(String pairId) {
		return Optional.ofNullable(rows.get(pairId));
	}

	/**
	 * Loads the manifest from the thread's context classloader, falling back to this class's own loader. The
	 * manifest rides inside the merged base jar while this class rides in the kernel's runtime jar, so the
	 * context loader — the game loader in production — is asked first; the fallback covers a thread whose
	 * context loader was pointed elsewhere. Absence is soft and falls through (an older merged base has no
	 * manifest at all); a manifest that IS present but malformed fails loudly rather than falling through to a
	 * loader that might hide the defect.
	 */
	public static ComposedHookManifest load() {
		ClassLoader context = Thread.currentThread().getContextClassLoader();
		ClassLoader own = ComposedHookManifest.class.getClassLoader();
		if (context != null) {
			ComposedHookManifest found = loadIfPresent(context);
			if (found != null) return found;
		}
		if (own != null && own != context) {
			ComposedHookManifest found = loadIfPresent(own);
			if (found != null) return found;
		}
		return EMPTY;
	}

	/** The manifest {@code loader} can see, or the empty manifest when it holds no such resource. */
	public static ComposedHookManifest load(ClassLoader loader) {
		ComposedHookManifest found = loadIfPresent(loader);
		return found == null ? EMPTY : found;
	}

	/** Null when absent, the parsed manifest when present — malformed is the caller's problem to fail on. */
	private static ComposedHookManifest loadIfPresent(ClassLoader loader) {
		try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
			if (in == null) return null;
			return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException failure) {
			throw new IllegalStateException("the merged base carries " + RESOURCE + " but it could not be read",
					failure);
		}
	}

	private static ComposedHookManifest parse(String text) {
		Map<String, Row> rows = new LinkedHashMap<>();
		String[] lines = text.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i].strip();
			if (line.isEmpty() || line.startsWith("#")) continue;
			Row row = row(line, i);
			if (rows.put(row.pairId(), row) != null) {
				throw malformed(i, line, "pair id " + row.pairId() + " already has a row — this version composes "
						+ "one site per pair, so a second one is a format change that must not load silently");
			}
		}
		return new ComposedHookManifest(rows.isEmpty() ? Map.of() : Collections.unmodifiableMap(rows));
	}

	private static Row row(String line, int i) {
		String[] tokens = line.strip().split("\\s+");
		if (tokens.length != 3 || !tokens[2].equals(KIND)) {
			throw malformed(i, line, "a row is '<pairId> <hostInternalName>#<hostName><hostDesc> " + KIND + "'");
		}
		if (!isPairId(tokens[0])) {
			throw malformed(i, line, "the pair id is not a plain Java method name — the merge tool retargets to "
					+ "ComposedHook.<pairId>, so the id must be spellable as one");
		}
		String site = tokens[1];
		int hash = site.indexOf('#');
		int paren = hash < 0 ? -1 : site.indexOf('(', hash);
		if (hash <= 0 || paren <= hash + 1 || !site.contains(")")) {
			throw malformed(i, line, "the site is not <hostInternalName>#<hostName><hostDesc>");
		}
		return new Row(tokens[0], site.substring(0, hash), site.substring(hash + 1, paren), site.substring(paren));
	}

	private static IllegalStateException malformed(int line, String content, String what) {
		return new IllegalStateException("the merged base's " + RESOURCE + " is malformed at line " + (line + 1)
				+ ": " + what + " — got \"" + content + "\". The merge tool writes this file, so a row that does "
				+ "not parse is a build bug, not a configuration problem");
	}

	/** A pair id becomes the facade's method name, so it must be spellable as one. */
	private static boolean isPairId(String pairId) {
		if (pairId.isEmpty() || !Character.isJavaIdentifierStart(pairId.charAt(0))) return false;
		for (int i = 1; i < pairId.length(); i++) {
			if (!Character.isJavaIdentifierPart(pairId.charAt(i))) return false;
		}
		return true;
	}
}
