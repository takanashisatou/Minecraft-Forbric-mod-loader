package net.forbric.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

class LostHookAttributionTest {
	@TempDir Path temporary;

	@Test void sameNamedHooksFromDifferentOwnersDoNotCancelEachOther() {
		MethodNode original = new MethodNode(), merged = new MethodNode();
		original.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				"net/minecraftforge/common/ForgeHooks", "tick", "()V", false));
		merged.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				"net/neoforged/neoforge/event/EventHooks", "tick", "()V", false));
		Set<String> lost = LostHookAttribution.hookCalls(original);
		lost.removeAll(LostHookAttribution.hookCalls(merged));
		assertEquals(Set.of("net/minecraftforge/common/ForgeHooks#tick()V"), lost);
	}

	@Test void bothLosingFamiliesAreInventoried() throws Exception {
		Path report = temporary.resolve("conflicts.txt");
		Files.writeString(report, "game/A#tick()V (forge hook lost)\ngame/B#tick()V (neo hook lost)\n"
				+ "game/C#tick()V DECLINED another reason\n");
		var rows = LostHookAttribution.conflicts(report);
		assertEquals(2, rows.size());
		assertEquals("forge", rows.get(0).lostFamily());
		assertEquals("neo", rows.get(1).lostFamily());
		assertEquals("game/B", rows.get(1).owner());
	}

	/** MergedBaseBuilder's second row form: the hooking side lost because only the other body inits a field. */
	@Test void fieldInitKeptRowsAreInventoriedWithTheFamilyThatLostTheHook() throws Exception {
		Path report = temporary.resolve("conflicts.txt");
		Files.writeString(report, "game/A#tick()V (forge hook lost)\n"
				+ "net/minecraft/world/entity/LivingEntity#<init>(Lnet/minecraft/world/entity/EntityType;"
				+ "Lnet/minecraft/world/level/Level;)V (kept neo body to preserve base-added field init; forge hook lost)\n"
				+ "game/B$Inner#<init>()V (kept forge body to preserve base-added field init; neo hook lost)\n");
		var rows = LostHookAttribution.conflicts(report);
		assertEquals(3, rows.size());
		assertFalse(rows.get(0).fieldInitKept());
		assertEquals("net/minecraft/world/entity/LivingEntity", rows.get(1).owner());
		assertEquals("<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V", rows.get(1).method());
		assertEquals("forge", rows.get(1).lostFamily());
		assertTrue(rows.get(1).fieldInitKept());
		assertEquals("game/B$Inner", rows.get(2).owner());
		assertEquals("neo", rows.get(2).lostFamily());
		assertTrue(rows.get(2).fieldInitKept());
		assertEquals(List.of("forge", "forge", "neo"),
				MergeabilityCensus.conflicts(report).stream().map(c -> c[2]).toList());
	}

	/** A form nobody taught the parser must stop the run, not shrink the denominator. */
	@Test void anUnrecognisedHookLostRowFailsInsteadOfDisappearing() throws Exception {
		Path report = temporary.resolve("conflicts.txt");
		for (String row : List.of("game/A#tick()V (kept forge body for some new reason; neo hook lost)",
				"game/A#tick()V (fabric hook lost)", "game/A (forge hook lost)",
				"game/A#tick()V (kept forge body to preserve base-added field init; forge hook lost)")) {
			Files.writeString(report, "game/Z#ok()V (neo hook lost)\n" + row + "\n");
			var failure = assertThrows(java.io.IOException.class, () -> LostHookAttribution.conflicts(report), row);
			assertTrue(failure.getMessage().contains(row), failure.getMessage());
			assertThrows(java.io.IOException.class, () -> MergeabilityCensus.conflicts(report), row);
		}
	}

	/** The tracked historical report: every row the builder wrote as a hook loss is a row the tools judge. */
	@Test void everyHookLostRowOfTheTrackedReportEntersTheDenominator() throws Exception {
		Path report = Path.of(System.getProperty("user.dir"), "run", "merged-base", "merge-conflicts.txt");
		long written = Files.readAllLines(report).stream().filter(line -> line.trim().endsWith("hook lost)")).count();
		var rows = LostHookAttribution.conflicts(report);
		assertTrue(written > 1000, "the tracked report is the fixture this test reads: " + written);
		assertEquals(written, rows.size());
		assertEquals(6, rows.stream().filter(LostHookAttribution.Conflict::fieldInitKept).count());
		assertTrue(rows.stream().anyMatch(r -> r.owner().equals("net/minecraft/world/entity/LivingEntity")
				&& r.method().startsWith("<init>(") && r.lostFamily().equals("forge")));
		assertEquals(written, MergeabilityCensus.conflicts(report).size());
	}

	@Test void realReportTraversalAttributesBothSidesAndNamesUnobservedConflicts() throws Exception {
		String forge = "net/minecraftforge/common/ForgeHooks", neo = "net/neoforged/neoforge/event/EventHooks";
		Path f = jar("forge.jar", forge, forge), n = jar("neo.jar", neo, neo);
		Path merged = jar("merged.jar", neo, forge), carrier = temporary.resolve("empty.jar");
		try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(carrier))) { }
		Path report = temporary.resolve("report.txt"), mods = Files.createDirectory(temporary.resolve("mods"));
		Files.writeString(report, "game/A#tick()V (forge hook lost)\ngame/B#tick()V (neo hook lost)\n"
				+ "game/Missing#tick()V (forge hook lost)\n");
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		PrintStream previous = System.out;
		try (PrintStream out = new PrintStream(bytes)) {
			System.setOut(out);
			LostHookAttribution.main(new String[] {f.toString(), n.toString(), merged.toString(), carrier.toString(),
					carrier.toString(), report.toString(), mods.toString()});
		} finally { System.setOut(previous); }
		String output = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(output.contains("[attribution] mods name 0 ecosystem class references (candidate filter; not event/subscriber count)"));
		assertFalse(output.contains("ecosystem event class(es)"));
		assertTrue(output.contains("game/A#tick()V lost-family=forge RAW-LOST [" + forge + "#tick()V]"));
		assertTrue(output.contains("game/B#tick()V lost-family=neo RAW-LOST [" + neo + "#tick()V]"));
		assertTrue(output.contains("conflicts=3 judged=2 no-modelled-direct-hook=0 unobserved=1"));
		assertTrue(output.contains("runtime restoration=NOT_ASSESSED"));
		assertTrue(output.contains("[platform-census] denominator side=forge classes=2 methods=2"));
		assertTrue(output.contains("[platform-census] denominator side=neo classes=2 methods=2"));
		assertTrue(output.contains("OUTSIDE_EVENT_MODEL symbols are not classified as event hooks"));
		assertTrue(output.contains("raw absence is not proof of a behavior defect"));
	}

	@Test void outsideFacadesAndBothNamespacesAreInventoriedWithoutClaimingEventCoverage() {
		String forge = "net/minecraftforge/registries/RegistryManager#tick()V";
		String neo = "net/neoforged/fml/ModLoader#tick()V";
		ClassNode source = classNode("game/A", methodNode("tick", "()V", forge, neo), methodNode("plain", "()V"));
		var census = LostHookAttribution.platformCensus(Map.of(source.name, source), Map.of(source.name, source), List.of());
		assertEquals(1, census.classes());
		assertEquals(2, census.methods());
		assertEquals(1, census.callers());
		assertEquals(2, census.calls().size());
		assertEquals(Set.of(forge, neo), census.calls().stream().map(LostHookAttribution.PlatformCall::symbol)
				.collect(java.util.stream.Collectors.toSet()));
		assertTrue(census.calls().stream().noneMatch(LostHookAttribution.PlatformCall::modelledFacade));
		assertTrue(census.calls().stream().allMatch(c -> c.state() == LostHookAttribution.RawCallState.RAW_RETAINED));
	}

	@Test void callersOutsideConflictListHaveTheSameRetentionAndLossCoverage() {
		String hook = "net/minecraftforge/common/ForgeHooks#tick()V";
		ClassNode source = classNode("game/A", methodNode("listed", "()V", hook), methodNode("unlisted", "()V", hook));
		ClassNode merged = classNode("game/A", methodNode("listed", "()V", hook), methodNode("unlisted", "()V"));
		var census = LostHookAttribution.platformCensus(Map.of(source.name, source), Map.of(merged.name, merged),
				List.of(new LostHookAttribution.Conflict("game/A", "listed()V", "forge")));
		assertEquals(2, census.callers());
		assertEquals(1, census.listedConflictCallers());
		assertEquals(2, census.calls().size());
		var listed = census.calls().get(0);
		var unlisted = census.calls().get(1);
		assertTrue(listed.listedConflict());
		assertEquals(LostHookAttribution.RawCallState.RAW_RETAINED, listed.state());
		assertFalse(unlisted.listedConflict());
		assertTrue(unlisted.modelledFacade());
		assertEquals("game/A#unlisted()V", unlisted.caller());
		assertEquals(LostHookAttribution.RawCallState.RAW_LOST, unlisted.state());
		assertEquals(1, unlisted.lostOccurrences());
	}

	@Test void ownerDescriptorAndRepeatedOccurrencesCannotMaskOtherRawLosses() {
		String hook = "net/minecraftforge/common/ForgeHooks#tick()V";
		String overload = "net/minecraftforge/common/ForgeHooks#tick(I)V";
		String otherOwner = "net/neoforged/neoforge/event/EventHooks#tick()V";
		ClassNode source = classNode("game/A", methodNode("tick", "()V", hook, hook, overload, otherOwner));
		ClassNode merged = classNode("game/A", methodNode("tick", "()V", hook));
		var census = LostHookAttribution.platformCensus(Map.of(source.name, source), Map.of(merged.name, merged), List.of());
		assertEquals(3, census.calls().size());
		var rows = census.calls().stream().collect(java.util.stream.Collectors.toMap(
				LostHookAttribution.PlatformCall::symbol, c -> c));
		assertEquals(LostHookAttribution.RawCallState.RAW_PARTIAL_LOSS, rows.get(hook).state());
		assertEquals(2, rows.get(hook).originalOccurrences());
		assertEquals(1, rows.get(hook).retainedOccurrences());
		assertEquals(1, rows.get(hook).lostOccurrences());
		assertEquals(LostHookAttribution.RawCallState.RAW_LOST, rows.get(overload).state());
		assertEquals(LostHookAttribution.RawCallState.RAW_LOST, rows.get(otherOwner).state());
	}

	@Test void missingMergedClassesAndOverloadsAreUnobservedOnBothSides() {
		for (String platform : List.of("net/minecraftforge/common/ForgeHooks", "net/neoforged/neoforge/event/EventHooks")) {
			String hook = platform + "#tick()V";
			ClassNode missingClass = classNode("game/Missing", methodNode("tick", "()V", hook));
			ClassNode missingMethod = classNode("game/Present", methodNode("tick", "(I)V", hook), methodNode("plain", "()V"));
			ClassNode merged = classNode("game/Present", methodNode("tick", "()V", hook));
			var census = LostHookAttribution.platformCensus(Map.of(missingClass.name, missingClass, missingMethod.name, missingMethod),
					Map.of(merged.name, merged), List.of());
			assertEquals(3, census.methods());
			assertEquals(3, census.missingMergedMethods());
			assertEquals(2, census.missingMergedCallers());
			assertEquals(2, census.calls().size());
			for (var row : census.calls()) {
				assertEquals(LostHookAttribution.RawCallState.MERGED_CALLER_MISSING, row.state());
				assertEquals(-1, row.mergedOccurrences());
				assertEquals(0, row.retainedOccurrences());
				assertEquals(0, row.lostOccurrences());
			}
		}
	}

	/**
	 * Every raw loss in the census gets an effective state, not only the conflict rows: an UNLISTED caller whose
	 * final definition restored the call is DIRECT_RESTORED, and a listed one that stayed lost is residual.
	 */
	@Test void everyCensusRawLossIsJoinedToTheFinalDefinitions() throws Exception {
		String hook = "net/minecraftforge/common/ForgeHooks#tick()V";
		String outside = "net/minecraftforge/registries/RegistryManager#tick()V";
		ClassNode source = classNode("game/A", methodNode("listed", "()V", hook), methodNode("unlisted", "()V", hook, outside));
		ClassNode merged = classNode("game/A", methodNode("listed", "()V"), methodNode("unlisted", "()V"));
		var census = LostHookAttribution.platformCensus(Map.of(source.name, source), Map.of(merged.name, merged),
				List.of(new LostHookAttribution.Conflict("game/A", "listed()V", "forge")));
		Path evidence = evidence(classNode("game/A", methodNode("listed", "()V"), methodNode("unlisted", "()V", hook)));
		var states = LostHookAttribution.effectiveStates(census, new EffectiveHookEvidence(evidence));
		assertEquals(3, states.size(), "every raw loss, listed or not, modelled facade or not: " + states);
		var bySymbol = new java.util.HashMap<String, EffectiveHookEvidence.State>();
		states.forEach((call, state) -> bySymbol.put(call.caller() + " " + call.symbol(), state));
		assertEquals(EffectiveHookEvidence.State.OBSERVED_WITHOUT_HOOK, bySymbol.get("game/A#listed()V " + hook));
		assertEquals(EffectiveHookEvidence.State.DIRECT_RESTORED, bySymbol.get("game/A#unlisted()V " + hook));
		assertEquals(EffectiveHookEvidence.State.OBSERVED_WITHOUT_HOOK, bySymbol.get("game/A#unlisted()V " + outside));
	}

	@Test void theCensusPrintsEffectiveTotalsPerSideOnlyFromSuppliedEvidence() throws Exception {
		String forge = "net/minecraftforge/common/ForgeHooks", neo = "net/neoforged/neoforge/event/EventHooks";
		Path f = jar("forge.jar", forge, forge), n = jar("neo.jar", neo, neo);
		Path merged = jar("merged.jar", neo, forge), carrier = temporary.resolve("empty.jar");
		try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(carrier))) { }
		Path report = temporary.resolve("report.txt"), mods = Files.createDirectory(temporary.resolve("mods"));
		Files.writeString(report, "game/A#tick()V (forge hook lost)\n");
		String[] args = {f.toString(), n.toString(), merged.toString(), carrier.toString(), carrier.toString(),
				report.toString(), mods.toString()};
		String without = run(args);
		assertTrue(without.contains("[platform-census] effective side=forge raw-loss-pairs=1 NOT_ASSESSED"), without);
		assertTrue(without.contains("state=RAW_LOST") && without.contains("effective=NOT_ASSESSED"), without);
		Path evidence = evidence(classNode("game/A", methodNode("tick", "()V", forge + "#tick()V")),
				classNode("game/B", methodNode("tick", "()V")));
		String[] withEvidence = java.util.Arrays.copyOf(args, 8);
		withEvidence[7] = evidence.toString();
		String with = run(withEvidence);
		assertTrue(with.contains("[platform-census] effective side=forge raw-loss-pairs=1 DIRECT_RESTORED=1"
				+ " VIA_DEFINED_HELPER=0 VIA_KERNEL_BRIDGE=0 OBSERVED_WITHOUT_HOOK=0 UNOBSERVED=0"), with);
		assertTrue(with.contains("[platform-census] effective side=neo raw-loss-pairs=1 DIRECT_RESTORED=0"
				+ " VIA_DEFINED_HELPER=0 VIA_KERNEL_BRIDGE=0 OBSERVED_WITHOUT_HOOK=1 UNOBSERVED=0"), with);
	}

	/** Facade rows are restorations, not losses: they never enter the dropped-hook denominator. */
	@Test void facadeRestoredRowsAreRestoredNotLossesAndStayOutOfTheCensus() throws Exception {
		Path report = temporary.resolve("conflicts.txt");
		Files.writeString(report, "game/A#tick()V (forge hook restored via facade portalSpawn)\n"
				+ "game/B#tick()V (neo hook restored via facade pair007)\n"
				+ "game/C#tick()V (forge hook lost)\n");
		var parsed = LostHookAttribution.report(report);
		assertEquals(1, parsed.conflicts().size());
		assertEquals("game/C", parsed.conflicts().get(0).owner());
		assertEquals(List.of(
				new LostHookAttribution.FacadeRestoration("game/A", "tick()V", "forge", "portalSpawn"),
				new LostHookAttribution.FacadeRestoration("game/B", "tick()V", "neo", "pair007")),
				parsed.facadeRestorations());
		assertTrue(parsed.facadeCandidates().isEmpty());
		assertTrue(parsed.awaitingFacadePairs().isEmpty());
		assertEquals(1, LostHookAttribution.conflicts(report).size());
		assertEquals(List.of("forge"), MergeabilityCensus.conflicts(report).stream().map(c -> c[2]).toList());
	}

	/** The decision-log form names the same restoration; the disposition row's family wins and duplicates collapse. */
	@Test void acceptedFacadeDecisionRowsAreRecognisedAndDeduplicated() throws Exception {
		Path report = temporary.resolve("conflicts.txt");
		Files.writeString(report, "game/A#tick()V (forge hook restored via facade portalSpawn)\n"
				+ "game/A#tick()V ACCEPTED paired hooks restored via facade portalSpawn (2 site(s))\n"
				+ "game/B#poke()V ACCEPTED paired hooks restored via facade pair007 (1 site(s))\n");
		var parsed = LostHookAttribution.report(report);
		assertEquals(2, parsed.facadeRestorations().size());
		assertEquals(new LostHookAttribution.FacadeRestoration("game/A", "tick()V", "forge", "portalSpawn"),
				parsed.facadeRestorations().get(0));
		assertNull(parsed.facadeRestorations().get(1).restoredFamily(), "the decision-log form names no family");
		assertEquals("pair007", parsed.facadeRestorations().get(1).pairId());
	}

	/** Like a loss row, a facade row in a shape nobody taught the parser stops the run. */
	@Test void anUnrecognisedFacadeRowFailsInsteadOfDisappearing() throws Exception {
		Path report = temporary.resolve("conflicts.txt");
		for (String row : List.of("game/A#tick()V (forge hook restored via facade)",
				"game/A#tick()V (fabric hook restored via facade pair007)",
				"game/A#tick()V (forge hook restored via facade )",
				"game/A#tick()V ACCEPTED paired hooks restored via facade pair007 (two sites)",
				"game/A#tick()V ACCEPTED paired hooks restored via facade pair007")) {
			Files.writeString(report, "game/Z#ok()V (neo hook lost)\n" + row + "\n");
			var failure = assertThrows(java.io.IOException.class, () -> LostHookAttribution.report(report), row);
			assertTrue(failure.getMessage().contains(row.trim()), failure.getMessage());
			assertThrows(java.io.IOException.class, () -> LostHookAttribution.conflicts(report), row);
			assertThrows(java.io.IOException.class, () -> MergeabilityCensus.conflicts(report), row);
		}
	}

	/**
	 * The candidates section MergedBaseBuilder writes: one {@code candidate} row per unmapped composable pair,
	 * neo hook first, with its decline count and refusal hosts. The aggregated summary lines that share the row
	 * phrases ("[merge] declined N: paired hooks align …", "[merge] paired hooks restored via facade: …") are not
	 * rows and must not even be examined, and the DECLINED decision rows the section aggregated stay skipped so
	 * no refusal is counted twice.
	 */
	@Test void awaitingFacadeCandidatesAreCountedApartFromLosses() throws Exception {
		Path report = temporary.resolve("conflicts.txt");
		Files.writeString(report, "game/A#tick()V (forge hook lost)\n"
				+ "game/A#tick()V DECLINED paired hooks align, but no runtime stand-down is reviewed:"
				+ " restoring net/minecraftforge/common/ForgeHooks.tick()V\n"
				+ "=== machine-readable: paired-hook facade candidates (composable pairs declined for want of a"
				+ " reviewed stand-down or a net/forbric/tools/restored-hook-pairs.txt row) ===\n"
				+ "# one row per distinct pair: candidate <neoHook> <forgeHook> declines=<n> sites=<host>#...\n"
				+ "candidate net/neoforged/neoforge/event/EventHooks.tick()V net/minecraftforge/common/ForgeHooks.tick()V"
				+ " declines=1 sites=game/A#tick()V\n"
				+ "candidate net/neoforged/neoforge/event/EventHooks.poke()V net/minecraftforge/common/ForgeHooks.poke()V"
				+ " declines=2 sites=game/B#poke()V,game/C#poke()V\n"
				+ "[merge]   declined 3: paired hooks align, but no runtime stand-down is reviewed\n"
				+ "[merge] paired hooks restored via facade: 0 site(s) listed in META-INF/forbric/restored-hooks.txt;"
				+ " facade candidates: 3 refusal(s) across 2 pair(s)\n");
		var parsed = LostHookAttribution.report(report);
		assertEquals(1, parsed.conflicts().size());
		assertEquals(2, parsed.facadeCandidates().size());
		assertEquals(new LostHookAttribution.FacadeCandidate("net/neoforged/neoforge/event/EventHooks.tick()V",
				"net/minecraftforge/common/ForgeHooks.tick()V", 1, List.of("game/A#tick()V")),
				parsed.facadeCandidates().get(0));
		assertEquals(new LostHookAttribution.FacadeCandidate("net/neoforged/neoforge/event/EventHooks.poke()V",
				"net/minecraftforge/common/ForgeHooks.poke()V", 2, List.of("game/B#poke()V", "game/C#poke()V")),
				parsed.facadeCandidates().get(1));
		assertTrue(parsed.facadeRestorations().isEmpty());
		assertEquals(1, MergeabilityCensus.conflicts(report).size());
	}

	@Test void malformedCandidateRowsStopTheRun() throws Exception {
		Path report = temporary.resolve("conflicts.txt");
		for (String row : List.of(
				"candidate net/neoforged/neoforge/event/EventHooks.tick()V declines=1 sites=game/A#tick()V",
				"candidate net/neoforged/neoforge/event/EventHooks.tick()V net/minecraftforge/common/ForgeHooks.tick()V"
						+ " sites=game/A#tick()V",
				"candidate tick()V tick()V declines=1 sites=game/A#tick()V",
				"candidate net/neoforged/neoforge/event/EventHooks.tick()V net/minecraftforge/common/ForgeHooks.tick()V"
						+ " declines=1 sites=game/A",
				"net/neoforged/neoforge/event/EventHooks.tick()V -> not-a-hook",
				"net/neoforged/neoforge/event/EventHooks -> net/minecraftforge/common/ForgeHooks.tick()V",
				"game/A -> game/B",
				"tick()V -> tick()V")) {
			Files.writeString(report, row + "\n");
			assertThrows(java.io.IOException.class, () -> LostHookAttribution.report(report), row);
		}
		// the same pair listed twice is one pair reported as two, in either row form
		for (String rows : List.of(
				"candidate net/neoforged/neoforge/event/EventHooks.tick()V net/minecraftforge/common/ForgeHooks.tick()V"
						+ " declines=1 sites=game/A#tick()V\n"
						+ "candidate net/neoforged/neoforge/event/EventHooks.tick()V net/minecraftforge/common/ForgeHooks.tick()V"
						+ " declines=1 sites=game/B#tick()V\n",
				"net/neoforged/neoforge/event/EventHooks.tick()V -> net/minecraftforge/common/ForgeHooks.tick()V\n"
						+ "net/neoforged/neoforge/event/EventHooks.tick()V -> net/minecraftforge/common/ForgeHooks.tick()V\n")) {
			Files.writeString(report, rows);
			assertThrows(java.io.IOException.class, () -> LostHookAttribution.report(report));
		}
		// comment lines mentioning either row shape are not rows and do not throw
		Files.writeString(report, "# keyed '<baseHook> -> <restoredHook>' as the pair table keys them\n"
				+ "# one row per distinct pair: candidate <neoHook> <forgeHook> declines=<n>\n");
		var parsed = LostHookAttribution.report(report);
		assertTrue(parsed.facadeCandidates().isEmpty());
		assertTrue(parsed.awaitingFacadePairs().isEmpty());
	}

	/**
	 * The candidates section's other row form: a bare pair key per unmapped composable pair, keyed base first
	 * as the pair table keys it, with the decline count and host sites on a comment line beneath. The restored
	 * symbol is the key's second field in every orientation, and it is what feeds awaiting-facade marking.
	 */
	@Test void barePairKeyRowsNameTheRestoredSymbol() throws Exception {
		Path report = temporary.resolve("conflicts.txt");
		Files.writeString(report, "game/A#tick()V (forge hook lost)\n"
				+ "=== machine-readable: paired-hook facade candidates ===\n"
				+ "net/neoforged/neoforge/event/EventHooks.tick()V -> net/minecraftforge/common/ForgeHooks.tick()V\n"
				+ "  # declines=1 sites=game/A#tick()V\n"
				+ "net/minecraftforge/common/ForgeHooks.poke()V -> net/neoforged/neoforge/event/EventHooks.poke()V\n"
				+ "  # declines=2 sites=game/B#poke()V,game/C#poke()V\n");
		var parsed = LostHookAttribution.report(report);
		assertEquals(1, parsed.conflicts().size());
		assertEquals(Set.of("net/minecraftforge/common/ForgeHooks.tick()V",
				"net/neoforged/neoforge/event/EventHooks.poke()V"), parsed.awaitingFacadePairs());
		assertTrue(parsed.facadeCandidates().isEmpty());
	}

	/** A conflict whose raw-lost hook a bare pair key would restore is marked awaiting-facade the same way. */
	@Test void pairKeyCandidatesMarkConflictsByTheirRestoredSymbol() throws Exception {
		String forge = "net/minecraftforge/common/ForgeHooks", neo = "net/neoforged/neoforge/event/EventHooks";
		Path f = jar("forge.jar", forge, forge), n = jar("neo.jar", neo, neo);
		Path merged = jar("merged.jar", neo, forge), carrier = temporary.resolve("empty.jar");
		try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(carrier))) { }
		Path report = temporary.resolve("report.txt"), mods = Files.createDirectory(temporary.resolve("mods"));
		Files.writeString(report, "game/A#tick()V (forge hook lost)\n"
				+ "game/B#tick()V (neo hook lost)\n"
				+ neo + ".tick()V -> " + forge + ".tick()V\n");
		String output = run(new String[] {f.toString(), n.toString(), merged.toString(), carrier.toString(),
				carrier.toString(), report.toString(), mods.toString()});
		assertTrue(output.contains("game/A#tick()V lost-family=forge RAW-LOST [" + forge + "#tick()V]"
				+ " RETAINED [" + neo + "#tick()V] AWAITING-FACADE"), output);
		assertFalse(output.contains("game/B#tick()V lost-family=neo RAW-LOST [" + neo + "#tick()V]"
				+ " RETAINED [" + forge + "#tick()V] AWAITING-FACADE"), output);
		assertTrue(output.contains("[attribution] composable-awaiting-facade=1 pair(s), 1 conflict row(s)"
				+ " marked AWAITING-FACADE"), output);
	}

	@Test void facadeRestorationIsProvedFromTheMergedJarAndItsManifest() throws Exception {
		String forge = "net/minecraftforge/common/ForgeHooks", neo = "net/neoforged/neoforge/event/EventHooks";
		Path f = jar("forge.jar", forge, forge), n = jar("neo.jar", neo, neo);
		Path carrier = temporary.resolve("empty.jar");
		try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(carrier))) { }
		Path report = temporary.resolve("report.txt"), mods = Files.createDirectory(temporary.resolve("mods"));
		Files.writeString(report, "game/A#tick()V (forge hook restored via facade portalSpawn)\n");
		Path merged = mergedJar("merged.jar", "portalSpawn", true,
				"# forbric restored hooks\nportalSpawn game/A#tick()V paired-facade\n");
		String output = run(new String[] {f.toString(), n.toString(), merged.toString(), carrier.toString(),
				carrier.toString(), report.toString(), mods.toString()});
		assertTrue(output.contains("[attribution] game/A#tick()V restored-family=forge FACADE-RESTORED pair=portalSpawn"), output);
		assertTrue(output.contains("[attribution] restored-via-facade=1 unobserved=0"), output);
		assertTrue(output.contains("[attribution] conflicts=0 judged=0"), output);
		assertTrue(output.contains("counted apart from runtime restoration"), output);
	}

	/** A restoration the jar does not bear out is a tooling bug, not a restored hook: the run stops. */
	@Test void aFacadeClaimTheJarDoesNotBearOutStopsTheRun() throws Exception {
		String forge = "net/minecraftforge/common/ForgeHooks", neo = "net/neoforged/neoforge/event/EventHooks";
		Path f = jar("forge.jar", forge, forge), n = jar("neo.jar", neo, neo);
		Path carrier = temporary.resolve("empty.jar");
		try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(carrier))) { }
		Path report = temporary.resolve("report.txt"), mods = Files.createDirectory(temporary.resolve("mods"));
		Files.writeString(report, "game/A#tick()V (forge hook restored via facade portalSpawn)\n");
		String[] args = {f.toString(), n.toString(), null, carrier.toString(), carrier.toString(),
				report.toString(), mods.toString()};
		// the manifest has only its header: the site is not listed
		args[2] = mergedJar("merged-header-only.jar", "portalSpawn", true, "# forbric restored hooks\n").toString();
		assertThrows(java.io.IOException.class, () -> LostHookAttribution.main(args));
		// the jar predates the manifest channel entirely
		args[2] = mergedJar("merged-no-manifest.jar", "portalSpawn", true, null).toString();
		assertThrows(java.io.IOException.class, () -> LostHookAttribution.main(args));
		// listed, but the merged body never calls the facade
		args[2] = mergedJar("merged-no-call.jar", "portalSpawn", false,
				"# forbric restored hooks\nportalSpawn game/A#tick()V paired-facade\n").toString();
		assertThrows(java.io.IOException.class, () -> LostHookAttribution.main(args));
	}

	/** A host missing from the merged jar is unobserved, exactly as for conflict rows; it is not a pass. */
	@Test void aFacadeClaimWhoseHostIsMissingIsUnobserved() throws Exception {
		String forge = "net/minecraftforge/common/ForgeHooks", neo = "net/neoforged/neoforge/event/EventHooks";
		Path f = jar("forge.jar", forge, forge), n = jar("neo.jar", neo, neo);
		Path carrier = temporary.resolve("empty.jar");
		try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(carrier))) { }
		Path report = temporary.resolve("report.txt"), mods = Files.createDirectory(temporary.resolve("mods"));
		Files.writeString(report, "game/Missing#tick()V (forge hook restored via facade portalSpawn)\n");
		Path merged = mergedJar("merged.jar", "portalSpawn", true,
				"# forbric restored hooks\nportalSpawn game/Missing#tick()V paired-facade\n");
		String output = run(new String[] {f.toString(), n.toString(), merged.toString(), carrier.toString(),
				carrier.toString(), report.toString(), mods.toString()});
		assertTrue(output.contains("game/Missing#tick()V restored-family=forge FACADE-UNOBSERVED pair=portalSpawn"), output);
		assertTrue(output.contains("[attribution] restored-via-facade=0 unobserved=1"), output);
	}

	/** Awaiting-facade conflicts are marked on the raw-loss row they still are, and counted as their own category. */
	@Test void awaitingFacadeConflictsAreMarkedButRemainRealLossesToday() throws Exception {
		String forge = "net/minecraftforge/common/ForgeHooks", neo = "net/neoforged/neoforge/event/EventHooks";
		Path f = jar("forge.jar", forge, forge), n = jar("neo.jar", neo, neo);
		Path merged = jar("merged.jar", neo, forge), carrier = temporary.resolve("empty.jar");
		try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(carrier))) { }
		Path report = temporary.resolve("report.txt"), mods = Files.createDirectory(temporary.resolve("mods"));
		Files.writeString(report, "game/A#tick()V (forge hook lost)\n"
				+ "game/B#tick()V (neo hook lost)\n"
				+ "=== machine-readable: paired-hook facade candidates ===\n"
				+ "candidate " + neo + ".tick()V " + forge + ".tick()V declines=1 sites=game/A#tick()V\n");
		String output = run(new String[] {f.toString(), n.toString(), merged.toString(), carrier.toString(),
				carrier.toString(), report.toString(), mods.toString()});
		assertTrue(output.contains("game/A#tick()V lost-family=forge RAW-LOST [" + forge + "#tick()V]"
				+ " RETAINED [" + neo + "#tick()V] AWAITING-FACADE"), output);
		assertTrue(output.contains("game/B#tick()V lost-family=neo RAW-LOST [" + neo + "#tick()V]"
				+ " RETAINED [" + forge + "#tick()V]"), output);
		assertFalse(output.contains("game/B#tick()V lost-family=neo RAW-LOST [" + neo + "#tick()V]"
				+ " RETAINED [" + forge + "#tick()V] AWAITING-FACADE"), output);
		assertTrue(output.contains("[attribution] composable-awaiting-facade=1 pair(s), 1 conflict row(s)"
				+ " marked AWAITING-FACADE"), output);
		assertTrue(output.contains("[attribution] restored-via-facade=0 unobserved=0"), output);
	}

	/** The census announces the rows outside its question instead of silently reading past them. */
	@Test void theCensusAnnouncesFacadeRowsWithoutJudgingThem() throws Exception {
		Path vanilla = plainJar("vanilla.jar"), forge = plainJar("forge.jar"), neo = plainJar("neo.jar");
		Path report = temporary.resolve("conflicts.txt");
		Files.writeString(report, "game/A#tick()V (forge hook restored via facade portalSpawn)\n"
				+ "candidate net/neoforged/neoforge/event/EventHooks.tick()V"
				+ " net/minecraftforge/common/ForgeHooks.tick()V declines=1 sites=game/A#tick()V\n");
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		PrintStream previous = System.out;
		try (PrintStream out = new PrintStream(bytes)) {
			System.setOut(out);
			MergeabilityCensus.main(new String[] {vanilla.toString(), forge.toString(), neo.toString(), report.toString()});
		} finally { System.setOut(previous); }
		String output = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(output.contains("[mergeability] 0 dropped hook(s) to judge (0 Forge, 0 NeoForge)"), output);
		assertTrue(output.contains("facade-restored=1"), output);
		assertTrue(output.contains("composable-awaiting-facade=1 pair(s)"), output);
	}

	/** The manifest channel itself: comments and blanks skip, everything else must be a paired-facade row. */
	@Test void restoredHookManifestParsesRowsAndRefusesWhatItDoesNotKnow() throws Exception {
		assertNull(LostHookAttribution.restoredHookManifest(plainJar("no-manifest.jar")));
		Path listed = mergedJar("listed.jar", null, false,
				"# forbric restored hooks\n\nportalSpawn game/A#tick()V paired-facade\n");
		assertEquals(Set.of("portalSpawn game/A#tick()V"),
				LostHookAttribution.restoredHookManifest(listed));
		for (String bad : List.of("portalSpawn game/A#tick()V", "portalSpawn game/A#tick()V other",
				"portalSpawn game/A paired-facade")) {
			Path broken = mergedJar("broken.jar", null, false, bad + "\n");
			assertThrows(java.io.IOException.class, () -> LostHookAttribution.restoredHookManifest(broken), bad);
		}
		Path duplicate = mergedJar("duplicate.jar", null, false,
				"portalSpawn game/A#tick()V paired-facade\nportalSpawn game/A#tick()V paired-facade\n");
		assertThrows(java.io.IOException.class, () -> LostHookAttribution.restoredHookManifest(duplicate));
	}

	/** A merged jar with the restored-hooks manifest and, optionally, a game/A#tick()V host calling the facade. */
	private Path mergedJar(String name, String pairId, boolean callsFacade, String manifest) throws Exception {
		Path file = temporary.resolve(name);
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
			if (manifest != null) {
				out.putNextEntry(new JarEntry("META-INF/forbric/restored-hooks.txt"));
				out.write(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				out.closeEntry();
			}
			if (pairId != null) {
				ClassWriter writer = new ClassWriter(0);
				writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "game/A", null, "java/lang/Object", null);
				var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tick", "()V", null, null);
				method.visitCode();
				if (callsFacade) {
					method.visitMethodInsn(Opcodes.INVOKESTATIC, "net/forbric/kernel/runtime/ComposedHook",
							pairId, "()V", false);
				}
				method.visitInsn(Opcodes.RETURN);
				method.visitMaxs(0, 0);
				method.visitEnd();
				writer.visitEnd();
				out.putNextEntry(new JarEntry("game/A.class"));
				out.write(writer.toByteArray());
				out.closeEntry();
			}
		}
		return file;
	}

	/** A one-class jar whose only method body is RETURN. */
	private Path plainJar(String name) throws Exception {
		Path file = temporary.resolve(name);
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
			ClassWriter writer = new ClassWriter(0);
			writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "game/A", null, "java/lang/Object", null);
			var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tick", "()V", null, null);
			method.visitCode();
			method.visitInsn(Opcodes.RETURN);
			method.visitMaxs(0, 0);
			method.visitEnd();
			writer.visitEnd();
			out.putNextEntry(new JarEntry("game/A.class"));
			out.write(writer.toByteArray());
			out.closeEntry();
		}
		return file;
	}

	private static String run(String[] args) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		PrintStream previous = System.out;
		try (PrintStream out = new PrintStream(bytes)) {
			System.setOut(out);
			LostHookAttribution.main(args);
		} finally { System.setOut(previous); }
		return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
	}

	/** A defined-class evidence session in the kernel's content-addressed layout. */
	private Path evidence(ClassNode... defined) throws Exception {
		Path root = Files.createTempDirectory(temporary, "definitions-");
		StringBuilder manifest = new StringBuilder(EffectiveHookEvidence.HEADER + "\n");
		for (ClassNode node : defined) {
			node.version = Opcodes.V17;
			node.superName = "java/lang/Object";
			for (MethodNode method : node.methods) {
				if (method.instructions.getLast() == null || method.instructions.getLast().getOpcode() != Opcodes.RETURN)
					method.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
				method.maxStack = 1;
			}
			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			byte[] bytes = writer.toByteArray();
			String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
			Files.createDirectories(root.resolve("blobs"));
			Files.write(root.resolve("blobs").resolve(hash + ".class"), bytes);
			manifest.append(node.name).append('\t').append(hash).append('\n');
		}
		Files.writeString(root.resolve("definitions.tsv"), manifest);
		Files.createFile(root.resolve(EffectiveHookEvidence.INTACT));
		return root;
	}

	@Test void opcodeAndInterfaceOwnerChangesAreNotReportedAsPreservedInvocations() {
		String owner = "net/minecraftforge/common/ForgeHooks", symbol = owner + "#tick()V";
		for (var changed : List.of(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "tick", "()V", false),
				new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "tick", "()V", true))) {
			ClassNode source = classNode("game/A", methodNode("tick", "()V", symbol));
			MethodNode changedMethod = methodNode("tick", "()V");
			changedMethod.instructions.add(changed);
			ClassNode merged = classNode("game/A", changedMethod);
			var row = LostHookAttribution.platformCensus(Map.of(source.name, source), Map.of(merged.name, merged),
					List.of()).calls().get(0);
			assertEquals(LostHookAttribution.RawCallState.RAW_INVOCATION_CHANGED, row.state());
			assertEquals(1, row.mergedOccurrences());
			assertEquals(0, row.retainedOccurrences());
			assertEquals(1, row.lostOccurrences());
			assertEquals(Map.of("INVOKESTATIC/itf=false", 1), row.originalForms());
			assertNotEquals(row.originalForms(), row.mergedForms());
		}
	}

	private static ClassNode classNode(String owner, MethodNode... methods) {
		ClassNode node = new ClassNode();
		node.name = owner;
		node.methods.addAll(List.of(methods));
		return node;
	}

	private static MethodNode methodNode(String name, String descriptor, String... calls) {
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
		for (String call : calls) {
			int hash = call.indexOf('#'), arguments = call.indexOf('(', hash);
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, call.substring(0, hash),
					call.substring(hash + 1, arguments), call.substring(arguments), false));
		}
		return method;
	}

	private Path jar(String name, String first, String second) throws Exception {
		Path file = temporary.resolve(name);
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
			String[] hooks = {first, second};
			for (int i = 0; i < 2; i++) {
				String owner = "game/" + (i == 0 ? "A" : "B");
				ClassWriter writer = new ClassWriter(0);
				writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
				var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tick", "()V", null, null);
				method.visitCode();
				method.visitMethodInsn(Opcodes.INVOKESTATIC, hooks[i], "tick", "()V", false);
				method.visitInsn(Opcodes.RETURN);
				method.visitMaxs(0, 0);
				method.visitEnd();
				writer.visitEnd();
				out.putNextEntry(new JarEntry(owner + ".class"));
				out.write(writer.toByteArray());
				out.closeEntry();
			}
		}
		return file;
	}
}
