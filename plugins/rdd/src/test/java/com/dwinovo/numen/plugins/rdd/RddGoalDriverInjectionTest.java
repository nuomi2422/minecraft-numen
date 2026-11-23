package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.side.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-08: runtime injection seam (not factory-only) must write DAILY+recurrence into the chain. */
class RddGoalDriverInjectionTest {
    @Test void acceptedCurrentSnapshotRetiresOldDescriptionsButPreservesHistoryAndOtherGoals() {
        TaskChain chain=emptyChain();SideTaskEngine engine=engine();
        var other=new RecurringSideTaskSpec("daily:other-goal:keep","daily_maintenance",1,1,
                RecurringSideTaskSpec.MissedPolicy.SKIP,true,false,Map.of("description","other goal"));
        engine.registerRecurring(other,"daily_maintenance");
        for(int i=0;i<67;i++)engine.registerRecurring(new RecurringSideTaskSpec("daily:g:old"+i,"daily_maintenance",1,1,
                RecurringSideTaskSpec.MissedPolicy.SKIP,true,false,Map.of("description","old description "+i)),"daily_maintenance");
        var accepted=RddGoalDriver.buildInjection("p",chain.nextPlanRevision(),List.of(
                new SubtaskSpec("current daily wheat",Map.of("asset_key","minecraft:wheat","minimum",1),null,NodeType.DAILY,RecurrenceSpec.daily(false)),
                new SubtaskSpec("main iron",Map.of("asset_key","minecraft:iron_ingot","minimum",1),null)));
        RddGoalDriver.acceptPlan(accepted,ignored->SideTaskHost.DailyRegistration.registerChain(chain,engine),()->chain.expandCurrentPrimary(accepted));
        assertEquals(69,engine.recurringSpecsSnapshot().size(),"retirement retains original specs, not deletion");
        assertEquals(2,engine.recurringSpecsSnapshot().stream().filter(RecurringSideTaskSpec::enabled).count(),"only accepted own daily plus other goal may stay active");
        assertFalse(engine.recurringSpec("daily:g:old0").enabled());assertEquals("old description 0",engine.recurringSpec("daily:g:old0").data().get("description"));
        assertEquals(other,engine.recurringSpec(other.scheduleId()));
    }

    @Test void failedInstallationCannotRegisterProposalOrRetireCommittedSpecs() {
        TaskChain chain=emptyChain();SideTaskEngine engine=engine();
        var old=new RecurringSideTaskSpec("daily:g:committed","daily_maintenance",2,1,
                RecurringSideTaskSpec.MissedPolicy.SKIP,true,false,Map.of("description","committed"));engine.registerRecurring(old,"daily_maintenance");
        var candidates=dailyCandidates(chain);String before=engine.toJson();var calls=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IllegalArgumentException.class,()->RddGoalDriver.acceptPlan(candidates,subs->{calls.incrementAndGet();SideTaskHost.DailyRegistration.register(chain,subs,engine);},()->chain.expandCurrentPrimary(candidates)));
        assertEquals(0,calls.get(),"registration belongs after successful mainline install, never a rejected proposal");
        assertEquals(before,engine.toJson());
    }

    @Test void acceptedEmptyDailySetRetiresWithoutCancellingOriginalInstanceAndSurvivesRestore() {
        TaskChain chain=emptyChain();SideTaskEngine engine=engine();var candidates=dailyCandidates(chain);
        SideTaskHost.DailyRegistration.register(chain,candidates,engine);UUID id=UUID.randomUUID();World world=new World();engine.tick(id,world,mainlineToken(chain,id),false);
        String instance=engine.current(id).orElseThrow().instanceId();String specId="daily:g:"+Integer.toHexString(candidates.get(0).description().hashCode());
        var main=List.of(Subtask.hardCoded("main","main iron",Map.of("asset_key","minecraft:iron_ingot","minimum",1)));
        RddGoalDriver.acceptPlan(main,ignored->SideTaskHost.DailyRegistration.registerChain(chain,engine),()->chain.expandCurrentPrimary(main));
        assertFalse(engine.recurringSpec(specId).enabled(),"an accepted main-only plan is an explicit empty DAILY snapshot");
        assertEquals(instance,engine.current(id).orElseThrow().instanceId(),"registration is not physical cancellation");
        assertEquals(Boolean.TRUE,engine.handledToday(id,world.worldId(),specId,world.day));
        SideTaskEngine restored=engine();restored.restore(engine.toJson());SideTaskHost.DailyRegistration.restoreChain(TaskChain.fromJson(chain.toJson()),restored);
        assertFalse(restored.recurringSpec(specId).enabled());assertEquals(instance,restored.current(id).orElseThrow().instanceId());
        assertEquals(Boolean.TRUE,restored.handledToday(id,world.worldId(),specId,world.day));
    }

    @Test void completeRestoredChainCanRetireLegacyExtrasButIncompleteRestoreCannot() {
        var main=Subtask.hardCoded("main","main iron",Map.of("asset_key","minecraft:iron_ingot","minimum",1));
        TaskChain installed=new TaskChain(new Goal("g","current",List.of(new PrimaryGoal("p","main",List.of(main)))));
        SideTaskEngine engine=engine();var old=new RecurringSideTaskSpec("daily:g:obsolete","daily_maintenance",1,1,
                RecurringSideTaskSpec.MissedPolicy.SKIP,true,false,Map.of("description","obsolete"));engine.registerRecurring(old,"daily_maintenance");
        SideTaskHost.DailyRegistration.restoreChain(emptyChain(),engine);assertTrue(engine.recurringSpec(old.scheduleId()).enabled(),"unexpanded is UNKNOWN, not an empty accepted snapshot");
        SideTaskHost.DailyRegistration.restoreChain(TaskChain.fromJson(installed.toJson()),engine);
        assertFalse(engine.recurringSpec(old.scheduleId()).enabled(),"complete restored current plan must retire old live registration");
        SideTaskHost.DailyRegistration.restoreChain(emptyChain(),engine);assertFalse(engine.recurringSpec(old.scheduleId()).enabled());
    }

    @Test void authoritativeSnapshotKeepsDistinctDailySchedulesIndependentWithOriginalAnchorAndHandled() {
        TaskChain chain=emptyChain();SideTaskEngine engine=engine();UUID id=UUID.randomUUID();World world=new World();
        var all=RddGoalDriver.buildInjection("p",chain.nextPlanRevision(),List.of(
                new SubtaskSpec("daily wheat",Map.of("asset_key","minecraft:wheat","minimum",1),null,NodeType.DAILY,RecurrenceSpec.daily(false)),
                new SubtaskSpec("daily carrot",Map.of("asset_key","minecraft:carrot","minimum",1),null,NodeType.DAILY,RecurrenceSpec.daily(false)),
                new SubtaskSpec("main iron",Map.of("asset_key","minecraft:iron_ingot","minimum",1),null)));
        RddGoalDriver.acceptPlan(all,ignored->SideTaskHost.DailyRegistration.registerChain(chain,engine),()->chain.expandCurrentPrimary(all));
        assertEquals(2,engine.recurringSpecsSnapshot().stream().filter(RecurringSideTaskSpec::enabled).count());
        engine.tick(id,world,mainlineToken(chain,id),false);String first=engine.current(id).orElseThrow().instanceId();
        engine.decline(id,"current reason",world);engine.tick(id,world,mainlineToken(chain,id),false);
        assertTrue(engine.current(id).isPresent(),"different valid DAILY still has its own occurrence");assertNotEquals(first,engine.current(id).orElseThrow().instanceId());
        String wheat="daily:g:"+Integer.toHexString("daily wheat".hashCode());assertEquals(Boolean.TRUE,engine.handledToday(id,world.worldId(),wheat,world.day));
        SideTaskHost.DailyRegistration.restoreChain(TaskChain.fromJson(chain.toJson()),engine);assertEquals(1,engine.recurringSpec(wheat).anchorGameDay());
        assertEquals(Boolean.TRUE,engine.handledToday(id,world.worldId(),wheat,world.day));
    }

    @Test void injectedDailyReachesChainWithRecurrence() {
        TaskChain chain = new TaskChain(new Goal("g", "维护农田",
                List.of(new PrimaryGoal("p", "维护农田", List.of(), List.of(), true))));
        SubtaskSpec daily = new SubtaskSpec("每天收麦补种",
                Map.of("asset_key", "minecraft:wheat", "minimum", 1), null,
                NodeType.DAILY, new RecurrenceSpec(1, RecurrenceSpec.MissedPolicy.SKIP, true));
        SubtaskSpec main = new SubtaskSpec("开垦农田",
                Map.of("asset_key", "minecraft:iron_hoe", "minimum", 1), null, NodeType.MAIN, null);
        var subs = RddGoalDriver.buildInjection("p", 1, List.of(daily, main));
        chain.expandCurrentPrimary(subs);
        var written = chain.currentPrimary().subtasks();
        assertEquals(2, written.size());
        assertEquals(NodeType.DAILY, written.get(0).nodeType(), "运行期注入后链里必须仍是 DAILY");
        assertNotNull(written.get(0).recurrence());
        assertEquals(NodeType.MAIN, written.get(1).nodeType());
    }

    /** Production buildInjection -> expand -> activate, not just factory metadata. */
    @Test void injectedDailyFirstDispatchesMainAndKeepsRecurringSource() {
        TaskChain chain = new TaskChain(new Goal("g", "iron equipment",
                List.of(PrimaryGoal.unexpanded("p", "prepare", List.of()))));
        RecurrenceSpec schedule = RecurrenceSpec.daily(true);
        var subs = RddGoalDriver.buildInjection("p", 1, List.of(
                new SubtaskSpec("daily wheat",
                        Map.of("asset_key", "minecraft:wheat", "minimum", 1), null,
                        NodeType.DAILY, schedule),
                new SubtaskSpec("main iron",
                        Map.of("asset_key", "minecraft:iron_ingot", "minimum", 1), null,
                        NodeType.MAIN, null)));
        SideTaskEngine engine = engine();
        RddGoalDriver.acceptPlan(subs, candidates -> SideTaskHost.DailyRegistration.register(chain, candidates, engine),
                () -> chain.expandCurrentPrimary(subs));
        assertTrue(chain.activateCurrent(Map.of()));
        assertEquals(subs.get(1).id(), chain.currentSubtask().id(),
                "the injected DAILY must not become the running main node");
        assertEquals(subs, chain.currentPrimary().subtasks());
        assertEquals(schedule, chain.currentPrimary().subtasks().get(0).recurrence());
        assertNotEquals(SubtaskStatus.RUNNING, chain.subtaskStatuses().get(subs.get(0).id()));
        assertTrue(chain.applyHardCodedResult(subs.get(1).id(), true));
        assertNotEquals(SubtaskStatus.COMPLETED, chain.subtaskStatuses().get(subs.get(0).id()));
    }

    // V25 new-rdd:144/146: replay the original description/predicate through real consumers, not a factory-only MAIN.
    @Test void foodTwelvePreparationSurvivesDailyRegistrationAndRestoreBeforeItsTail() {
        String description = "把食物补到能支撑一整个下界/找要塞来回的量：至少 12 份直接可食用的东西（生肉也计，后面可以在基地熔炉烤熟再吃；小麦/种子不算）。来源按优先级：基地与村庄箱子里的存货 → 夜里或水边打猎（牛/羊/猪/鸡/鲑鱼）→ 现成农田收获后补种。起步攒到 12 份就走下一步，别在野外无限狩猎。";
        var parsed = RddDecomposer.parse(new com.google.gson.Gson().toJson(Map.of("subtasks", List.of(
                Map.of("description", description, "condition", Map.of("group", "food", "minimum", 12), "node_type", "MAIN"),
                Map.of("description", "补足火把供地表照明", "condition", Map.of("asset_key", "minecraft:torch", "minimum", 8), "node_type", "MAIN")))));
        assertEquals(2, parsed.size());
        var guarded = RddPlanGuard.filterSubtasks(parsed, "末地准备与定位要塞", Map.of("minecraft:salmon", 2));
        assertEquals(parsed, guarded.allowed(), guarded.dropped().toString());
        TaskChain chain = emptyChain();
        var subs = RddGoalDriver.buildInjection("p", chain.nextPlanRevision(), guarded.allowed());
        SideTaskEngine engine = engine();
        RddGoalDriver.acceptPlan(subs, candidates -> SideTaskHost.DailyRegistration.register(chain, candidates, engine),
                () -> chain.expandCurrentPrimary(subs));
        assertTrue(chain.activateCurrent(Map.of("minecraft:salmon", 2)));
        // A restart lands in RECOVERING; only the original exit puts it back to ACTIVE.
        TaskChain restoredChain = TaskChain.fromJson(chain.toJson());
        assertEquals(PrimaryGoalStatus.RECOVERING, restoredChain.primaryStatus(), "restart must re-enter RECOVERING");
        restoredChain.resumeFromRecovering();
        assertEquals(PrimaryGoalStatus.ACTIVE, restoredChain.primaryStatus(), "recovery must exit through resumeFromRecovering");
        assertEquals(PrimaryGoalStatus.ACTIVE, chain.primaryStatus(), "the live chain stays ACTIVE");
        // Both the live chain and the restored consumer must wait for the original food12 predicate.
        for (TaskChain consumer : List.of(chain, restoredChain)) {
            SideTaskHost.DailyRegistration.registerChain(consumer, engine);
            Subtask current = consumer.currentSubtask();
            // JSON has no Java integer wrappers: Gson reads 12 back as 12.0. Keep every field check,
            // and compare condition numbers by value so restore fidelity is still proven, not weakened.
            assertEquals(subs.get(0).id(), current.id());
            assertEquals(subs.get(0).detectionMode(), current.detectionMode());
            assertEquals(subs.get(0).detectionIntervalSeconds(), current.detectionIntervalSeconds());
            assertEquals(subs.get(0).maxAiChecks(), current.maxAiChecks());
            assertEquals(subs.get(0).recheckOnUnchanged(), current.recheckOnUnchanged());
            assertEquals(subs.get(0).body(), current.body());
            assertEquals(conditionValues(subs.get(0).condition()), conditionValues(current.condition()));
            assertEquals(description, current.description());
            assertEquals(conditionValues(parsed.get(0).condition()), conditionValues(current.condition()));
            assertEquals(NodeType.MAIN, current.nodeType());
            // The restored threshold must stay exactly 12: 12.5/12.9 would silently move the predicate.
            double minimum = ((Number) current.condition().get("minimum")).doubleValue();
            assertEquals(12.0, minimum);
            assertTrue(minimum != 12.5 && minimum != 12.9, "food minimum must stay exactly 12");
            String dailyId = "daily:g:" + Integer.toHexString(description.hashCode());
            assertNull(engine.recurringSpec(dailyId), "one-trip food must never be registered as recurring work");
            assertFalse(consumer.applyHardCodedResult(subs.get(0).id(),
                    com.dwinovo.numen.rdd.core.HardCodedEvaluator.matches(consumer.currentSubtask().condition(), Map.of("minecraft:salmon", 11))));
            assertEquals(subs.get(0).id(), consumer.currentSubtask().id(), "food11 cannot dispatch the torch tail");
            assertEquals(SubtaskStatus.RUNNING, consumer.subtaskStatuses().get(subs.get(0).id()));
            assertEquals(SubtaskStatus.PENDING, consumer.subtaskStatuses().get(subs.get(1).id()));
            assertTrue(consumer.satisfiedStages().isEmpty());
            assertTrue(consumer.applyHardCodedResult(subs.get(0).id(),
                    com.dwinovo.numen.rdd.core.HardCodedEvaluator.matches(consumer.currentSubtask().condition(), Map.of("minecraft:salmon", 12))));
            assertEquals(subs.get(1).id(), consumer.currentSubtask().id(), "only food12 may advance to the original legal tail");
            assertEquals(SubtaskStatus.COMPLETED, consumer.subtaskStatuses().get(subs.get(0).id()));
            assertTrue(consumer.satisfiedStages().isEmpty(), "food completion is not verified stage completion");
        }
    }

    /** Numbers in a JSON map have no wrapper identity: Gson reads 12 back as 12.0. Compare by value. */
    private static Map<String, Object> conditionValues(Map<String, Object> condition) {
        var out = new java.util.LinkedHashMap<String, Object>();
        for (var entry : condition.entrySet()) {
            out.put(entry.getKey(), entry.getValue() instanceof Number n ? (Object) n.doubleValue() : entry.getValue());
        }
        return out;
    }

    @Test void guardedRecurringHarvestStillRegistersDailyAndDispatchesItsMainTail() {
        var harvest = new SubtaskSpec("每日收割成熟小麦后补种", Map.of("asset_key", "minecraft:wheat", "minimum", 1), null);
        var tail = new SubtaskSpec("补足火把供地表照明", Map.of("asset_key", "minecraft:torch", "minimum", 8), null);
        var guarded = RddPlanGuard.filterSubtasks(List.of(harvest, tail), "维护农田", Map.of());
        assertEquals(2, guarded.allowed().size(), guarded.dropped().toString());
        assertEquals(NodeType.DAILY, guarded.allowed().get(0).nodeType());
        assertEquals(RecurrenceSpec.daily(true), guarded.allowed().get(0).recurrence());
        TaskChain chain = emptyChain();
        var subs = RddGoalDriver.buildInjection("p", chain.nextPlanRevision(), guarded.allowed());
        SideTaskEngine engine = engine();
        RddGoalDriver.acceptPlan(subs, candidates -> SideTaskHost.DailyRegistration.register(chain, candidates, engine),
                () -> chain.expandCurrentPrimary(subs));
        assertTrue(chain.activateCurrent(Map.of()));
        assertEquals(subs.get(1).id(), chain.currentSubtask().id());
        assertEquals(SubtaskStatus.SKIPPED, chain.subtaskStatuses().get(subs.get(0).id()));
        String dailyId = "daily:g:" + Integer.toHexString(harvest.description().hashCode());
        assertNotNull(engine.recurringSpec(dailyId));
        assertTrue(chain.satisfiedStages().isEmpty());
    }

    private static TaskChain emptyChain() {
        return new TaskChain(new Goal("g", "iron equipment",
                List.of(PrimaryGoal.unexpanded("p", "prepare", List.of()))));
    }

    private static List<Subtask> dailyCandidates(TaskChain chain) {
        int revision = chain.nextPlanRevision();
        return RddGoalDriver.buildInjection("p", revision, List.of(
                new SubtaskSpec("daily wheat", Map.of("asset_key", "minecraft:wheat", "minimum", 1),
                        null, NodeType.DAILY, new RecurrenceSpec(2, RecurrenceSpec.MissedPolicy.SKIP, false))));
    }

    private static SideTaskEngine engine() {
        SideTaskEngine engine = new SideTaskEngine(SideTaskEvents.NOOP, token -> token);
        engine.registerType(new DailyMaintenanceSideTaskType());
        return engine;
    }

    /** Same detached token fields as the production Host; no fabricated companion from a goal id. */
    private static MainlineResumeToken mainlineToken(TaskChain chain, UUID id) {
        Subtask current = chain.currentSubtask();
        return new MainlineResumeToken(id, chain.chainInstanceId(), chain.goal().id(),
                chain.currentPrimary() == null ? null : chain.currentPrimary().id(),
                current == null ? null : current.id(), chain.planRevision(), chain.planRevision());
    }

    private static final class World implements SideTaskWorldPort {
        long day = 1;
        int wheat, stockReads, dailyReads;
        UUID dailySubject;
        Boolean dailyResult;
        Map<String, Object> seenDailyCondition;
        public long gameTime() { return day * 24000; }
        public long dayTime() { return 0; }
        public long gameDay() { return day; }
        public boolean hasDayNightCycle() { return true; }
        public String worldId() { return "test-world"; }
        public boolean isSleeping(UUID id) { return false; }
        public boolean isDay() { return true; }
        public Wake pollWake(UUID id) { return Wake.NONE; }
        public int countItem(UUID id, String key) { stockReads++; return wheat; }
        public Boolean dailyCondition(UUID id, Map<String, Object> condition) {
            dailyReads++;
            return SideTaskHost.DailyRegistration.dailyConditionFor(id, dailySubject, true, condition, actual -> {
                seenDailyCondition = actual;
                return dailyResult; // Test boundary only; no duplicate world predicate.
            });
        }
    }

    @Test void rejectedAllDailyExpansionStillRegistersExecutableDaily() {
        TaskChain chain = emptyChain();
        SideTaskEngine engine = engine();
        List<Subtask> candidates = dailyCandidates(chain);
        assertThrows(IllegalArgumentException.class, () -> RddGoalDriver.acceptPlan(candidates,
                subs -> SideTaskHost.DailyRegistration.register(chain, subs, engine),
                () -> chain.expandCurrentPrimary(candidates)));
        assertTrue(chain.currentPrimary().unexpanded());
        assertTrue(chain.satisfiedStages().isEmpty());
        UUID id = UUID.randomUUID();
        engine.tick(id, new World(), mainlineToken(chain, id), false);
        assertEquals(DailyMaintenanceSideTaskType.TYPE_ID, engine.runtime().current(id).orElseThrow().typeId());
    }

    @Test void hostRegisteredDailyDoesNotRunForAnotherGoalsCurrentToken() {
        TaskChain own = emptyChain();
        TaskChain foreign = new TaskChain(new Goal("other-g", "foreign farm", List.of(
                PrimaryGoal.unexpanded("p", "prepare", List.of()))));
        var events = new java.util.ArrayList<String>();
        SideTaskEngine engine = new SideTaskEngine((event, data) -> events.add(event), token -> token);
        engine.registerType(new DailyMaintenanceSideTaskType());
        List<Subtask> foreignDaily = dailyCandidates(foreign);
        SideTaskHost.DailyRegistration.register(foreign, foreignDaily, engine);
        UUID id = UUID.randomUUID();
        World world = new World();
        String foreignId = "daily:other-g:" + Integer.toHexString(foreignDaily.get(0).description().hashCode());
        engine.tick(id, world, mainlineToken(own, id), false);
        assertTrue(engine.current(id).isEmpty());
        assertTrue(events.isEmpty());
        assertNull(engine.handledToday(id, world.worldId(), foreignId, world.day));
        SideTaskHost.DailyRegistration.register(own, dailyCandidates(own), engine);
        engine.tick(id, world, mainlineToken(own, id), false);
        assertEquals(own.goal().id(), engine.current(id).orElseThrow().mainlineToken().goalId());
        assertEquals(1, java.util.Collections.frequency(events, "side_task_due"));
        assertNull(engine.handledToday(id, world.worldId(), foreignId, world.day));
    }

    @Test void repeatedCandidateRegistrationDoesNotMoveDueDay() {
        TaskChain chain = emptyChain();
        SideTaskEngine engine = engine();
        SideTaskHost.DailyRegistration.register(chain, dailyCandidates(chain), engine); // anchor 1
        List<Subtask> retried = dailyCandidates(chain); // retry revision 2
        Subtask old = retried.get(0);
        Subtask updated = old.withCondition(Map.of("asset_key", "minecraft:wheat", "minimum", 2));
        SideTaskHost.DailyRegistration.register(chain, List.of(updated), engine);
        World world = new World();
        world.day = 3; // the original interval-two schedule is due
        UUID id = UUID.randomUUID();
        engine.tick(id, world, mainlineToken(chain, id), false);
        assertTrue(engine.runtime().current(id).isPresent(), "retry must not postpone day 3 to day 4");
        assertEquals("2", engine.runtime().current(id).orElseThrow().data().get("minimum"),
                "preserving the due anchor must still update valid template data");
    }

    @Test void repeatedCandidateRegistrationPreservesActiveAndHandledOccurrence() {
        TaskChain chain = emptyChain();
        SideTaskEngine engine = engine();
        List<Subtask> first = dailyCandidates(chain);
        SideTaskHost.DailyRegistration.register(chain, first, engine);
        UUID id = UUID.randomUUID();
        World world = new World();
        engine.tick(id, world, mainlineToken(chain, id), false);
        String instance = engine.runtime().current(id).orElseThrow().instanceId();
        SideTaskHost.DailyRegistration.register(chain, dailyCandidates(chain), engine);
        engine.tick(id, world, mainlineToken(chain, id), false);
        assertEquals(instance, engine.runtime().current(id).orElseThrow().instanceId());
        world.wheat = 1;
        engine.tick(id, world, mainlineToken(chain, id), false);
        assertTrue(engine.runtime().current(id).isEmpty());
        SideTaskHost.DailyRegistration.register(chain, dailyCandidates(chain), engine);
        engine.tick(id, world, mainlineToken(chain, id), false);
        assertTrue(engine.runtime().current(id).isEmpty(), "handled day must not create a second occurrence");
        String specId = "daily:g:" + Integer.toHexString(first.get(0).description().hashCode());
        assertEquals(Boolean.TRUE, engine.handledToday(id, world.worldId(), specId, world.day));
    }

    @Test void rejectedAllDailyReplacementStillRegistersCandidateAndKeepsOldMain() {
        TaskChain chain = new TaskChain(new Goal("g", "iron equipment", List.of(
                new PrimaryGoal("p", "prepare", List.of(Subtask.hardCoded("old", "iron",
                        Map.of("asset_key", "minecraft:iron_ingot", "minimum", 1)))))));
        chain.startCurrent();
        chain.enterReplanningFromNegotiation("daily proposal");
        SideTaskEngine engine = engine();
        List<Subtask> candidates = dailyCandidates(chain);
        assertThrows(IllegalArgumentException.class, () -> RddGoalDriver.acceptPlan(candidates,
                subs -> SideTaskHost.DailyRegistration.register(chain, subs, engine),
                () -> chain.replaceCurrentSubtasks(candidates)));
        assertEquals(PrimaryGoalStatus.REPLANNING, chain.primaryStatus());
        assertEquals("old", chain.currentSubtask().id());
        assertTrue(chain.satisfiedStages().isEmpty());
        UUID id = UUID.randomUUID();
        engine.tick(id, new World(), mainlineToken(chain, id), false);
        assertTrue(engine.runtime().current(id).isPresent());
    }

    @Test void rejectedAllDailyCannotCommitVerifiedStage(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        TaskChain chain = emptyChain();
        List<Subtask> candidates = dailyCandidates(chain);
        SideTaskEngine engine = engine();
        assertThrows(IllegalArgumentException.class, () -> RddGoalDriver.acceptPlan(candidates,
                subs -> SideTaskHost.DailyRegistration.register(chain, subs, engine),
                () -> chain.expandCurrentPrimary(candidates)));
        var facts = new com.dwinovo.numen.rdd.fact.CompletedFactStore();
        var commit = new StageCompletionCommit(dir.resolve("tasks"), dir.resolve("facts"));
        var result = commit.confirm(UUID.randomUUID(), chain, facts, "all DAILY is not verification", 1L);
        assertFalse(result.committed());
        assertFalse(result.recordedAsCompletion());
        assertEquals(0, facts.stageCount());
        assertTrue(chain.satisfiedStages().isEmpty());
        assertEquals(PrimaryGoalStatus.PENDING, chain.primaryStatus());
    }

    @Test void differentTemplateIsExplicitlyReplacedRatherThanTreatedAsRetry() {
        TaskChain chain = emptyChain();
        SideTaskEngine engine = engine();
        List<Subtask> candidates = dailyCandidates(chain); // new DAILY anchor 1
        String specId = "daily:g:" + Integer.toHexString(candidates.get(0).description().hashCode());
        engine.registerRecurring(new RecurringSideTaskSpec(specId, "old_template", 2, 0,
                RecurringSideTaskSpec.MissedPolicy.SKIP, true, false, Map.of("minimum", "9")), "old_type");
        SideTaskHost.DailyRegistration.register(chain, candidates, engine);
        RecurringSideTaskSpec installed = engine.recurringSpec(specId);
        assertEquals(DailyMaintenanceSideTaskType.TYPE_ID, installed.templateId());
        assertEquals(1, installed.anchorGameDay(), "a different template must take the new candidate anchor");
        assertEquals("1", installed.data().get("minimum"));
        UUID id = UUID.randomUUID();
        engine.tick(id, new World(), mainlineToken(chain, id), false);
        assertEquals(DailyMaintenanceSideTaskType.TYPE_ID, engine.runtime().current(id).orElseThrow().typeId(),
                "registration must replace the actual engine type as well as its spec");
    }

    @Test void oldAllDailyGoalRestoresIntoRuntimeAndRegistersWithoutVerifiedCompletion(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        List<Subtask> candidates = RddGoalDriver.buildInjection("p", 1, List.of(new SubtaskSpec(
                "daily wheat", Map.of("asset_key", "minecraft:wheat", "minimum", 1), null,
                NodeType.DAILY, RecurrenceSpec.daily(false))));
        TaskChain old = new TaskChain(new Goal("g", "retained old goal", List.of(
                new PrimaryGoal("p", "prepare", candidates))));
        var json = com.google.gson.JsonParser.parseString(old.toJson()).getAsJsonObject();
        json.addProperty("primaryStatus", "AWAITING_SUPERVISOR");
        json.getAsJsonObject("statuses").addProperty(candidates.get(0).id(), "COMPLETED");
        json.getAsJsonObject("skipReasons").remove(candidates.get(0).id());
        TaskChain restored = TaskChain.fromJson(json.toString());
        var runtime = new com.dwinovo.numen.rdd.core.RddRuntime(restored,
                new com.dwinovo.numen.rdd.core.AssetRegistry());
        assertSame(restored, runtime.chain());
        assertEquals(com.google.gson.JsonParser.parseString(old.toJson()).getAsJsonObject().get("goal"),
                com.google.gson.JsonParser.parseString(restored.toJson()).getAsJsonObject().get("goal"));
        assertEquals(PrimaryGoalStatus.REPLANNING, restored.primaryStatus());
        assertEquals(SubtaskStatus.COMPLETED, restored.subtaskStatuses().get(candidates.get(0).id()),
                "loading a legacy plan must not rewrite its terminal history");
        assertNull(restored.currentSubtask(), "a DAILY must never be selected as a body task");
        assertNull(restored.snapshot().get("currentSubtaskId"));
        SideTaskEngine engine = engine();
        SideTaskHost.DailyRegistration.registerChain(restored, engine);
        UUID id = UUID.randomUUID();
        engine.tick(id, new World(), mainlineToken(restored, id), false);
        assertTrue(engine.runtime().current(id).isPresent(), "restore must retain the DAILY source for registration");
        var facts = new com.dwinovo.numen.rdd.fact.CompletedFactStore();
        var commit = new StageCompletionCommit(dir.resolve("tasks"), dir.resolve("facts"));
        assertFalse(commit.confirm(id, restored, facts, "old DAILY is not new world evidence", 1L).committed());
        assertEquals(0, facts.stageCount());
        assertTrue(restored.satisfiedStages().isEmpty());
        assertThrows(IllegalArgumentException.class, restored::resumeFromReplanning);
        restored.replaceCurrentSubtasks(List.of(Subtask.hardCoded("new-main", "main iron",
                Map.of("asset_key", "minecraft:iron_ingot", "minimum", 1))));
        restored.startCurrent();
        assertEquals("new-main", restored.currentSubtask().id());
    }

    @Test void rejectedDailyCandidateSurvivesExistingFileAndFreshEngineWithOriginalDueAnchor(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        TaskChain chain = emptyChain();
        SideTaskEngine source = engine();
        List<Subtask> candidates = dailyCandidates(chain); // anchor 1, interval 2
        String specId = "daily:g:" + Integer.toHexString(candidates.get(0).description().hashCode());
        RecurringSideTaskSpec other = new RecurringSideTaskSpec("daily:other-goal:keep", "other_template", 3, 4,
                RecurringSideTaskSpec.MissedPolicy.CATCH_UP, false, true, Map.of("note", "keep other goal"));
        source.registerRecurring(other, "other_type");
        java.nio.file.Path file = dir.resolve("side-tasks.json");
        assertThrows(IllegalArgumentException.class, () -> RddGoalDriver.acceptPlan(candidates, subs -> {
            SideTaskHost.DailyRegistration.register(chain, subs, source);
            try { java.nio.file.Files.writeString(file, source.toJson()); }
            catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
        }, () -> chain.expandCurrentPrimary(candidates)));
        assertTrue(chain.currentPrimary().unexpanded(), "the source cannot be recovered from this rejected main plan");
        assertTrue(java.nio.file.Files.isRegularFile(file));
        SideTaskEngine restored = engine();
        restored.restore(java.nio.file.Files.readString(file));
        assertEquals(1, restored.recurringSpec(specId).anchorGameDay());
        assertEquals(other, restored.recurringSpec(other.scheduleId()));
        List<Subtask> retried = dailyCandidates(chain); // revision 2 must not replace the saved anchor
        SideTaskHost.DailyRegistration.register(chain, List.of(retried.get(0).withCondition(
                Map.of("asset_key", "minecraft:wheat", "minimum", 2))), restored);
        World world = new World();
        UUID id = UUID.randomUUID();
        world.day = 2;
        restored.tick(id, world, mainlineToken(chain, id), false);
        assertTrue(restored.runtime().current(id).isEmpty(), "restoring must not shift the original day 3 to day 2");
        world.day = 3;
        restored.tick(id, world, mainlineToken(chain, id), false);
        assertEquals(DailyMaintenanceSideTaskType.TYPE_ID, restored.runtime().current(id).orElseThrow().typeId());
        assertEquals("2", restored.runtime().current(id).orElseThrow().data().get("minimum"));
        assertEquals(1, restored.recurringSpec(specId).anchorGameDay());
        assertEquals(other, restored.recurringSpec(other.scheduleId()));
    }

    @Test void recurringSnapshotPreservesActiveHandledAndOtherGoalAcrossFreshEngines(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        TaskChain chain = emptyChain();
        SideTaskEngine source = engine();
        List<Subtask> candidates = dailyCandidates(chain);
        String specId = "daily:g:" + Integer.toHexString(candidates.get(0).description().hashCode());
        SideTaskHost.DailyRegistration.register(chain, candidates, source);
        RecurringSideTaskSpec other = new RecurringSideTaskSpec("daily:other:keep", "other", 2, 0,
                RecurringSideTaskSpec.MissedPolicy.SKIP, false, false, Map.of("note", "keep"));
        source.registerRecurring(other, "other_type");
        UUID id = UUID.randomUUID();
        World world = new World();
        source.tick(id, world, mainlineToken(chain, id), false);
        String instance = source.runtime().current(id).orElseThrow().instanceId();
        java.nio.file.Path file = dir.resolve("side-tasks.json");
        java.nio.file.Files.writeString(file, source.toJson());
        SideTaskEngine restored = engine();
        restored.restore(java.nio.file.Files.readString(file));
        assertEquals(instance, restored.runtime().current(id).orElseThrow().instanceId());
        assertEquals(Boolean.TRUE, restored.handledToday(id, world.worldId(), specId, world.day));
        SideTaskHost.DailyRegistration.register(chain, dailyCandidates(chain), restored);
        restored.tick(id, world, mainlineToken(chain, id), false);
        assertEquals(instance, restored.runtime().current(id).orElseThrow().instanceId());
        world.wheat = 1;
        restored.tick(id, world, mainlineToken(chain, id), false);
        assertTrue(restored.runtime().current(id).isEmpty());
        java.nio.file.Files.writeString(file, restored.toJson());
        SideTaskEngine twice = engine();
        twice.restore(java.nio.file.Files.readString(file));
        SideTaskHost.DailyRegistration.register(chain, dailyCandidates(chain), twice);
        twice.tick(id, world, mainlineToken(chain, id), false);
        assertTrue(twice.runtime().current(id).isEmpty(), "saved handled occurrence must not recreate after restart");
        assertEquals(Boolean.TRUE, twice.handledToday(id, world.worldId(), specId, world.day));
        assertEquals(other, twice.recurringSpec(other.scheduleId()));
    }
    @Test void restoredSnapshotWinsOverStaleMixedChainButExplicitCandidatesStillUpdate(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        TaskChain chain = emptyChain();
        SideTaskEngine source = engine();
        List<Subtask> oldDaily = dailyCandidates(chain); // anchor 1, min 1, interval 2
        var mixed = new java.util.ArrayList<>(oldDaily);
        mixed.add(Subtask.hardCoded("old-main", "main iron",
                Map.of("asset_key", "minecraft:iron_ingot", "minimum", 1)));
        RddGoalDriver.acceptPlan(mixed, subs -> SideTaskHost.DailyRegistration.register(chain, subs, source),
                () -> chain.expandCurrentPrimary(mixed));
        chain.startCurrent();
        chain.enterReplanningFromNegotiation("updated daily proposal");
        java.nio.file.Path chainFile = dir.resolve("chain.json");
        java.nio.file.Files.writeString(chainFile, chain.toJson());
        var candidates = RddGoalDriver.buildInjection("p", chain.nextPlanRevision(), List.of(
                new SubtaskSpec("daily wheat", Map.of("asset_key", "minecraft:wheat", "minimum", 2),
                        null, NodeType.DAILY, new RecurrenceSpec(3, RecurrenceSpec.MissedPolicy.SKIP, false))));
        java.nio.file.Path sideFile = dir.resolve("side-tasks.json");
        assertThrows(IllegalArgumentException.class, () -> RddGoalDriver.acceptPlan(candidates, subs -> {
            SideTaskHost.DailyRegistration.register(chain, subs, source);
            try { java.nio.file.Files.writeString(sideFile, source.toJson()); }
            catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
        }, () -> chain.replaceCurrentSubtasks(candidates)));
        assertEquals(mixed, chain.currentPrimary().subtasks());
        TaskChain oldChain = TaskChain.fromJson(java.nio.file.Files.readString(chainFile));
        SideTaskEngine restored = engine();
        restored.restore(java.nio.file.Files.readString(sideFile));
        // Same registration seam used by restoreRuntimes after SideTaskHost.setup restores the snapshot.
        SideTaskHost.DailyRegistration.restoreChain(oldChain, restored);
        String specId = "daily:g:" + Integer.toHexString(oldDaily.get(0).description().hashCode());
        assertEquals("2", restored.recurringSpec(specId).data().get("minimum"));
        assertEquals(3, restored.recurringSpec(specId).intervalGameDays());
        assertEquals(1, restored.recurringSpec(specId).anchorGameDay());
        World world = new World();
        UUID id = UUID.randomUUID();
        world.day = 3;
        restored.tick(id, world, mainlineToken(chain, id), false);
        assertTrue(restored.runtime().current(id).isEmpty(), "stale chain must not restore the old day-3 due time");
        world.day = 4;
        restored.tick(id, world, mainlineToken(chain, id), false);
        assertEquals("2", restored.runtime().current(id).orElseThrow().data().get("minimum"));

        var legacyJson = com.google.gson.JsonParser.parseString(source.toJson()).getAsJsonObject();
        legacyJson.addProperty("schema", 2);
        legacyJson.remove("recurringSpecs");
        legacyJson.remove("recurringTypes");
        SideTaskEngine legacy = engine();
        legacy.restore(legacyJson.toString());
        assertNull(legacy.recurringSpec(specId), "schema 2 never stored templates");
        SideTaskHost.DailyRegistration.restoreChain(oldChain, legacy);
        assertEquals("1", legacy.recurringSpec(specId).data().get("minimum"));
        assertEquals(2, legacy.recurringSpec(specId).intervalGameDays());
        SideTaskHost.DailyRegistration.register(chain, candidates, legacy);
        assertEquals("2", legacy.recurringSpec(specId).data().get("minimum"));
        assertEquals(3, legacy.recurringSpec(specId).intervalGameDays());
        assertEquals(1, legacy.recurringSpec(specId).anchorGameDay());
        SideTaskHost.DailyRegistration.registerChain(oldChain, legacy);
        assertEquals("1", legacy.recurringSpec(specId).data().get("minimum"),
                "normal new-goal registration must retain its explicit update semantics");
    }


    // 2026-11-15 RED: the complete typed condition must persist as one JSON string in data.condition.
    @Test void typedBlockDailyConditionSurvivesRegistrationAndEngineRoundTrip() {
        TaskChain chain = emptyChain();
        Map<String, Object> condition = Map.of(
                "type", "block_nearby",
                "block", "#minecraft:crops",
                "radius", 16.0,
                "dimension", "minecraft:overworld",
                "anchor", List.of(62139.0, 64.0, -62120.0),
                "properties", Map.of("age", "7"));
        assertTrue(com.dwinovo.numen.rdd.core.WorldFactConditions.valid(condition),
                "fixture must be a legal WorldFactConditions predicate");
        Subtask block = new Subtask("d-block", "block crops daily", DetectionMode.HARD_CODED,
                condition, 0, 0, false, null, NodeType.DAILY, RecurrenceSpec.daily(true));
        SideTaskEngine engine = engine();

        SideTaskHost.DailyRegistration.register(chain, List.of(block), engine);

        String specId = "daily:" + chain.goal().id() + ":" + Integer.toHexString(block.description().hashCode());
        RecurringSideTaskSpec spec = engine.recurringSpec(specId);
        assertNotNull(spec, "typed DAILY must register a recurring spec");
        String json = spec.data().get("condition");
        assertNotNull(json, "producer must persist the complete condition as one JSON string");
        assertEquals(condition, new com.google.gson.Gson().fromJson(json, Map.class),
                "condition JSON must keep type/block/radius/dimension/anchor/properties");
        assertNull(spec.data().get("type"), "no parallel scalar type copy is expected");
        assertNull(spec.data().get("block"), "no parallel scalar block copy is expected");
        assertNull(spec.data().get("radius"), "no parallel scalar radius copy is expected");

        SideTaskEngine restored = engine();
        restored.restore(engine.toJson());
        RecurringSideTaskSpec after = restored.recurringSpec(specId);
        assertNotNull(after, "typed spec must survive engine snapshot round-trip");
        String afterJson = after.data().get("condition");
        assertNotNull(afterJson, "restored spec must keep data.condition");
        assertEquals(condition, new com.google.gson.Gson().fromJson(afterJson, Map.class),
                "restored condition JSON must be equivalent to the original map");

        UUID id = UUID.randomUUID();
        World world = new World();
        world.dailySubject = id;
        world.wheat = 99; // Inventory cannot turn an unverified world predicate into success.
        MainlineResumeToken token = mainlineToken(chain, id);
        restored.tick(id, world, token, false);
        SideTask active = restored.current(id).orElseThrow();
        assertEquals(afterJson, active.data().get("condition"), "restored spec reaches the active task");
        assertEquals(token, active.mainlineToken());
        restored.tick(id, world, token, false); // UNKNOWN
        assertTrue(restored.isActive(id), "UNKNOWN must not settle DAILY, even with stock");
        assertEquals(condition, world.seenDailyCondition, "full typed map reaches the host seam");
        world.dailyResult = Boolean.FALSE;
        restored.tick(id, world, token, false);
        assertTrue(restored.isActive(id), "FALSE must not settle DAILY, even with stock");
        world.dailyResult = Boolean.TRUE;
        world.wheat = 0;
        SideTaskVerdict verified = new DailyMaintenanceSideTaskType().evaluate(restored.current(id).orElseThrow(), world);
        assertEquals(SideTaskVerdict.Kind.COMPLETE, verified.kind());
        restored.tick(id, world, token, false);
        assertFalse(restored.isActive(id), "verified typed condition settles the original engine");
        assertEquals(4, world.dailyReads, "only evaluations call the typed port, not creation");
        assertEquals(0, world.stockReads, "typed DAILY must never consult inventory fallback");
    }

    // 2026-11-15: legacy asset_key/item DAILY must stay usable after the same producer.
    @Test void legacyAssetKeyDailyKeepsItemAndMinimum() {
        TaskChain chain = emptyChain();
        Subtask item = new Subtask("d-item", "legacy wheat daily", DetectionMode.HARD_CODED,
                Map.of("asset_key", "minecraft:wheat", "minimum", 1),
                0, 0, false, null, NodeType.DAILY, RecurrenceSpec.daily(true));
        SideTaskEngine engine = engine();

        SideTaskHost.DailyRegistration.register(chain, List.of(item), engine);

        String specId = "daily:" + chain.goal().id() + ":" + Integer.toHexString(item.description().hashCode());
        RecurringSideTaskSpec spec = engine.recurringSpec(specId);
        assertNotNull(spec, "legacy DAILY must still register a recurring spec");
        assertEquals("minecraft:wheat", spec.data().get("asset_key"),
                "legacy asset_key item predicate must stay usable");
        assertEquals("1", spec.data().get("minimum"));
    }

    /**
     * 2026-11-15: daily condition 宿主 seam 的控制用例组 —— 只钉四道闸与「同 Map 委托一次」，
     * 不复制 RddWorldFacts 判定、不伪造世界。
     */
    @Test void dailyConditionForPinsSubjectThreadSchemaAndSingleDelegation() {
        UUID self = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Map<String, Object> valid = Map.of("type", "block_nearby", "block", "minecraft:stone", "radius", 8);
        var calls = new java.util.concurrent.atomic.AtomicInteger();

        assertNull(SideTaskHost.DailyRegistration.dailyConditionFor(other, self, true, valid, c -> { calls.incrementAndGet(); return true; }),
                "wrong subject must be UNKNOWN");
        assertEquals(0, calls.get(), "wrong subject must not reach the matcher");

        assertNull(SideTaskHost.DailyRegistration.dailyConditionFor(self, self, false, valid, c -> { calls.incrementAndGet(); return true; }),
                "non-server thread must be UNKNOWN");
        assertEquals(0, calls.get(), "non-server thread must not reach the matcher");

        assertNull(SideTaskHost.DailyRegistration.dailyConditionFor(self, self, true, null, c -> { calls.incrementAndGet(); return true; }));
        assertNull(SideTaskHost.DailyRegistration.dailyConditionFor(self, self, true,
                Map.of("type", "base", "asset_key", "minecraft:bed"),
                c -> { calls.incrementAndGet(); return true; }), "invalid schema must be UNKNOWN");
        assertEquals(0, calls.get(), "null/invalid schema must not reach the matcher");

        assertNull(SideTaskHost.DailyRegistration.dailyConditionFor(self, self, true,
                Map.of("type", "dimension", "dimension", "minecraft:overworld"),
                c -> { calls.incrementAndGet(); return true; }), "only block_nearby is wired");
        assertEquals(0, calls.get(), "valid non-block type must not reach the matcher");

        var seen = new java.util.ArrayList<Map<String, Object>>();
        assertEquals(Boolean.TRUE, SideTaskHost.DailyRegistration.dailyConditionFor(self, self, true, valid,
                c -> { seen.add(c); return true; }));
        assertEquals(Boolean.FALSE, SideTaskHost.DailyRegistration.dailyConditionFor(self, self, true, valid,
                c -> { seen.add(c); return false; }));
        assertEquals(2, seen.size(), "exactly one delegation per call");
        assertSame(valid, seen.get(0), "same Map instance must be delegated");
        assertSame(valid, seen.get(1), "same Map instance must be delegated");

        assertNull(SideTaskHost.DailyRegistration.dailyConditionFor(self, self, true, valid,
                c -> { throw new IllegalStateException("boom"); }), "matcher failure is UNKNOWN, not completion");
    }

    @Test void equipmentOwnerRejectsIncompleteBatchesBeforeDailyRegistrationOrInstallation() {
        Map<String, String> target = armorTarget("minecraft:iron_helmet");
        Map<String, Object> full = Map.of("type", "equipment", "slots", target, "dimension", "minecraft:overworld");
        var prefix = equipmentPrefix();
        var sequential = new java.util.ArrayList<>(prefix.subList(0, 4));
        for (String slot : List.of("feet", "legs", "chest", "head")) {
            sequential.add(planStep("single " + slot,
                    Map.of("type", "equipment", "slot", slot, "item", target.get(slot)), "MAIN"));
        }
        var badPlans = List.of(
                withEquipmentTail(planStep("wear all four armor pieces", Map.of("type", "equipment",
                        "item", "minecraft:iron_helmet", "slot", "head"), "MAIN")),
                prefix, // No final equipment predicate: do not install the seven-step prefix.
                sequential,
                withEquipmentTail(planStep("optional armor", Map.of("type", "equipment", "slots", target, "optional", true), "MAIN")),
                withEquipmentTail(planStep("daily armor", full, "DAILY")),
                withEquipmentTail(planStep("inventory is not equipped", Map.of("asset_key", "minecraft:iron_helmet", "minimum", 1), "MAIN")),
                withEquipmentTail(planStep("different wanted is not owner coverage", Map.of("type", "equipment",
                        "slots", armorTarget("#minecraft:head_armor")), "MAIN")));
        for (boolean replace : List.of(false, true)) {
            for (var raw : badPlans) {
                TaskChain chain = equipmentChain(target);
                if (replace) {
                    var initial = parseInjection(chain, withEquipmentTail(planStep("full armor", full, "MAIN")));
                    RddGoalDriver.acceptPlan(chain.currentPrimary(), initial, ignored -> {}, () -> chain.expandCurrentPrimary(initial));
                    chain.startCurrent();
                    chain.enterReplanningFromNegotiation("fixture-only replacement");
                }
                var candidates = parseInjection(chain, raw);
                String before = chain.toJson();
                SideTaskEngine daily = engine();
                int[] calls = {0, 0};
                assertThrows(IllegalArgumentException.class, () -> RddGoalDriver.acceptPlan(chain.currentPrimary(), candidates, subs -> {
                    calls[0]++;
                    SideTaskHost.DailyRegistration.register(chain, subs, daily);
                }, () -> {
                    calls[1]++;
                    if (replace) chain.replaceCurrentSubtasks(candidates);
                    else chain.expandCurrentPrimary(candidates);
                }), raw.toString());
                assertArrayEquals(new int[]{0, 0}, calls, "coverage rejection must precede both side effects");
                assertEquals(before, chain.toJson(), "no prefix or partial replacement may be installed");
                String dailyId = "daily:" + chain.goal().id() + ":" + Integer.toHexString("fixture daily wheat".hashCode());
                assertNull(daily.recurringSpec(dailyId));
                TaskChain restored = TaskChain.fromJson(chain.toJson());
                assertEquals(target, restored.currentPrimary().equipmentTarget());
                for (int i = 0; i < chain.currentPrimary().subtasks().size(); i++) {
                    assertEquals(chain.currentPrimary().subtasks().get(i).id(), restored.currentPrimary().subtasks().get(i).id());
                    assertEquals(conditionValues(chain.currentPrimary().subtasks().get(i).condition()),
                            conditionValues(restored.currentPrimary().subtasks().get(i).condition()));
                }
            }
        }
    }

    @Test void exactEquipmentOwnerCoverageSurvivesExpansionReplacementAndRestoreWhileLegacyStaysLegacy() {
        for (String head : List.of("minecraft:iron_helmet", "#minecraft:head_armor")) {
            Map<String, String> target = armorTarget(head);
            TaskChain chain = equipmentChain(target);
            Map<String, Object> condition = Map.of("type", "equipment", "slots", target,
                    "dimension", "minecraft:overworld", "optional", false);
            SideTaskEngine daily = engine();
            for (boolean replace : List.of(false, true)) {
                if (replace) {
                    chain.resumeFromRecovering();
                    chain.enterReplanningFromNegotiation("fixture-only replacement");
                }
                TaskChain owner = chain;
                var candidates = parseInjection(owner, withEquipmentTail(planStep("full armor", condition, "MAIN")));
                var order = new java.util.ArrayList<String>();
                RddGoalDriver.acceptPlan(owner.currentPrimary(), candidates, subs -> {
                    order.add("daily");
                    SideTaskHost.DailyRegistration.register(owner, subs, daily);
                }, () -> {
                    order.add("install");
                    if (replace) owner.replaceCurrentSubtasks(candidates);
                    else owner.expandCurrentPrimary(candidates);
                });
                assertEquals(List.of("daily", "install"), order);
                owner.startCurrent();
                chain = TaskChain.fromJson(owner.toJson());
                assertEquals(target, chain.currentPrimary().equipmentTarget());
                assertEquals(8, chain.currentPrimary().subtasks().size());
                assertEquals(condition, chain.currentPrimary().subtasks().getLast().condition(), "dimension and exact id/#tag values must persist");
                assertTrue(chain.satisfiedStages().isEmpty(), "admission and restore are not completion evidence");
            }
        }
        // Same wording/predicate without an explicit owner target remains a legal legacy plan.
        TaskChain legacy = emptyChain();
        var headOnly = parseInjection(legacy, withEquipmentTail(planStep("wear all four armor pieces",
                Map.of("type", "equipment", "item", "minecraft:iron_helmet", "slot", "head"), "MAIN")));
        RddGoalDriver.acceptPlan(legacy.currentPrimary(), headOnly, ignored -> {}, () -> legacy.expandCurrentPrimary(headOnly));
        assertNull(TaskChain.fromJson(legacy.toJson()).currentPrimary().equipmentTarget());
        assertEquals("head", legacy.currentPrimary().subtasks().getLast().condition().get("slot"));
    }

    private static Map<String, String> armorTarget(String head) {
        return Map.of("feet", "minecraft:iron_boots", "legs", "minecraft:iron_leggings",
                "chest", "minecraft:iron_chestplate", "head", head);
    }

    private static TaskChain equipmentChain(Map<String, String> target) {
        return new TaskChain(new Goal("g", "fixture equipment owner", List.of(
                PrimaryGoal.unexpanded("p", "fixture wear all four armor pieces", List.of(), target))));
    }

    private static Map<String, Object> planStep(String description, Map<String, Object> condition, String nodeType) {
        return Map.of("description", description, "condition", condition, "node_type", nodeType);
    }

    /** Synthetic eight-step shape; not a claim to reproduce the original runtime JSON. */
    private static List<Map<String, Object>> equipmentPrefix() {
        var prefix = new java.util.ArrayList<Map<String, Object>>();
        prefix.add(planStep("fixture daily wheat", Map.of("asset_key", "minecraft:wheat", "minimum", 1), "DAILY"));
        for (int i = 1; i < 7; i++) prefix.add(planStep("fixture preparation " + i,
                Map.of("asset_key", "minecraft:iron_ingot", "minimum", i), "MAIN"));
        return prefix;
    }

    private static List<Map<String, Object>> withEquipmentTail(Map<String, Object> tail) {
        var raw = new java.util.ArrayList<>(equipmentPrefix());
        raw.add(tail);
        return raw;
    }

    private static List<Subtask> parseInjection(TaskChain chain, List<Map<String, Object>> raw) {
        var parsed = RddDecomposer.parse(new com.google.gson.Gson().toJson(Map.of("subtasks", raw)));
        assertEquals(raw.size(), parsed.size(), "fixtures must reach admission, not disappear during parsing");
        return RddGoalDriver.buildInjection(chain.currentPrimary().id(), chain.nextPlanRevision(), parsed);
    }
}
