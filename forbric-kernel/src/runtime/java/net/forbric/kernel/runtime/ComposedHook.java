/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The runtime half of a restored hook PAIR: one static facade method per pair id, called from merged-base
 * bytecode the merge tool rewrote, running the NeoForge hook and then the MinecraftForge hook inside a
 * thread-local dispatch scope so the kernel's Neo→Forge event bridges stand down for exactly the one event the
 * facade itself is delivering.
 *
 * <p>Why this exists: {@code AdditiveMethodMerger}'s paired-hook grammar can prove that both carriers patched
 * the same vanilla call site with the same call, but restoring the MinecraftForge call into the base without a
 * runtime compensation delivers the event TWICE — the restored call posts it, and the bridge that forwards
 * NeoForge's post to MinecraftForge delivers it again. The proof that a restoration is safe is therefore half
 * runtime, and {@link KernelPortalSpawn} is the hand-written instance of the pattern for the portal pair. This
 * class is the mechanism every further pair uses, so enabling a pair is one small method here plus one row in
 * the merge tool's pair table ({@code net/forbric/tools/restored-hook-pairs.txt}), not a new wrapper class per
 * site. The merge tool retargets the base's paired call to {@code INVOKESTATIC
 * net/forbric/kernel/runtime/ComposedHook.<pairId>(<the pair's shared descriptor>)} and does NOT insert the
 * other side's call; the facade performs both.
 *
 * <h2>The facade method-body pattern</h2>
 *
 * <p>For pair id {@code p} whose hooks share name {@code hook} and descriptor {@code D} on
 * {@code net.neoforged.neoforge....NeoHooks} and {@code net.minecraftforge....ForgeHooks} (the pair table's v1
 * constraint is same name, same descriptor — the paired grammar already requires it), the facade is:
 *
 * <pre>{@code
 * public static R p(A0 a0, A1 a1) {              // the pair's shared descriptor, verbatim
 *     enter("p");
 *     try {
 *         R neo = NeoHooks.hook(a0, a1);         // NeoForge first, the base's native order
 *         if (neo.isEmpty()) return neo;         // only hooks whose result the base threads through a local
 *                                                // behind an Optional refusal guard: repeat that guard, so
 *                                                // NeoForge's refusal is final and MinecraftForge is not asked
 *         return ForgeHooks.hook(a0, a1);        // handed NeoForge's result exactly as the base threaded it
 *     } finally {
 *         exit("p");                             // finally: a failing listener must not leave a phantom
 *                                                // dispatch open on this thread
 *     }
 * }
 * }</pre>
 *
 * <p>Void hooks skip the guard line. Whether a MinecraftForge-half failure propagates or is swallowed is part
 * of the pair's review, not mechanical: {@link KernelPortalSpawn} swallows it because the legacy bridge it
 * replaces had that failure policy, and a pair whose base had no such policy must not grow one here. The facade
 * holds no state beyond the dispatch scope — the arguments, the refusal guard's consumer and the result all
 * stay in the merged method, which is why the facade's descriptor is the hook's own.
 *
 * <p>The scope query is {@link #inDispatch(String)}: the bridges consult it with the pair's id while
 * dispatching that pair's event, exactly as {@code KernelGameResultBridges} consults
 * {@code KernelPortalSpawn.postedByWrapper}. Scope membership answers "is a facade for this pair between enter
 * and exit on this thread"; a pair whose event OTHER producers can raise on the same thread needs the
 * arg-identity refinement {@code postedByWrapper} embodies, and that is a per-pair decision made when the pair
 * is enabled, not a property of this mechanism.
 */
public final class ComposedHook {
	/** The pair ids of the facade dispatches in progress on this thread, innermost last. */
	private static final ThreadLocal<ArrayDeque<String>> DISPATCHES = new ThreadLocal<>();

	private ComposedHook() { }

	/**
	 * Opens the dispatch scope for {@code pairId}. The facade calls this before the NeoForge hook and pairs it
	 * with {@link #exit} in a {@code finally}; see the class javadoc for the whole pattern.
	 */
	static void enter(String pairId) {
		ArrayDeque<String> dispatches = DISPATCHES.get();
		if (dispatches == null) DISPATCHES.set(dispatches = new ArrayDeque<>());
		dispatches.addLast(pairId);
	}

	/**
	 * Closes this thread's innermost dispatch scope, which must be {@code pairId}'s. A facade body pairs
	 * enter/exit by construction, so a mismatch means a facade was edited wrong — and an unbalanced stack would
	 * silently corrupt every later stand-down query on this thread, so it fails loudly instead of popping.
	 */
	static void exit(String pairId) {
		ArrayDeque<String> dispatches = DISPATCHES.get();
		if (dispatches == null || dispatches.isEmpty() || !dispatches.peekLast().equals(pairId)) {
			throw new IllegalStateException("ComposedHook.exit(\"" + pairId + "\") on a thread whose innermost "
					+ "facade dispatch is " + (dispatches == null || dispatches.isEmpty()
							? "none" : "\"" + dispatches.peekLast() + "\""));
		}
		dispatches.removeLast();
		if (dispatches.isEmpty()) DISPATCHES.remove();
	}

	/**
	 * Whether a facade dispatch for {@code pairId} is open on THIS thread, innermost or outer. Re-entrant
	 * dispatch of one pair (a listener inside the NeoForge half triggers the same call site again) keeps both
	 * frames until its own exit, so this is stack membership, not an innermost match.
	 */
	public static boolean inDispatch(String pairId) {
		ArrayDeque<String> dispatches = DISPATCHES.get();
		return dispatches != null && dispatches.contains(pairId);
	}

	/**
	 * The runtime counterpart of the merge-time pair table: fails loudly if the merged base's restored-hook
	 * manifest names a pair id this class declares no public static method for. The merge tool retargeted each
	 * listed pair's call sites to {@code ComposedHook.<pairId>}, so a pair id with no facade here is a
	 * {@code NoSuchMethodError} the first time the patched method runs — surfaced by a kernel whose runtime jar
	 * and merged base came from different builds. Checking the whole table here, before the game runs, turns
	 * that into one line that says which side to rebuild.
	 *
	 * <p>With the seed pair table empty the manifest carries only its header and there is nothing to check; an
	 * absent manifest is the empty manifest, so this passes trivially until the first real pair is enabled.
	 */
	public static void verifySelf() {
		ComposedHookManifest manifest = ComposedHookManifest.load();
		List<String> missing = new ArrayList<>();
		for (String pairId : manifest.pairIds()) {
			if (!declaresFacade(pairId)) missing.add(pairId);
		}
		if (!missing.isEmpty()) {
			throw new IllegalStateException("the merged base's " + ComposedHookManifest.RESOURCE + " names hook "
					+ "pairs ComposedHook declares no public static facade method for: " + missing + ". The merge "
					+ "tool retargeted those call sites to ComposedHook.<pairId>, so the game would die with a "
					+ "NoSuchMethodError the first time each patched method runs. The kernel runtime jar and the "
					+ "merged base come from different builds; rebuild both from one checkout");
		}
	}

	/** The merged base calls the facade from another package, so package-private here is as broken as absent. */
	private static boolean declaresFacade(String pairId) {
		for (Method method : ComposedHook.class.getDeclaredMethods()) {
			if (method.getName().equals(pairId) && Modifier.isStatic(method.getModifiers())
					&& Modifier.isPublic(method.getModifiers())) return true;
		}
		return false;
	}
}
