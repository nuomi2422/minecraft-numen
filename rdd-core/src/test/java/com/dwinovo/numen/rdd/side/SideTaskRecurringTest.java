package com.dwinovo.numen.rdd.side;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-08: recurring spec due/complete/expire/next-day/cross-world/body-busy/death gates. */
class SideTaskRecurringTest {
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static final class DailyType implements SideTaskType {
        private final boolean completeOnNext;
        private final long deadline;
        DailyType(boolean completeOnNext, long deadline) { this.completeOnNext = completeOnNext; this.deadline = deadline; }
        @Override public String typeId() { return "daily_test"; }
        @Override public long deadlineTicks() { return deadline; }
        @Override public SideTask create(CreationContext ctx) {
            return new SideTask(ctx.companionId(), ctx.instanceId(), typeId(), ctx.generation(),
                    ctx.scheduledGameDay(), ctx.gameTime(), ctx.deadlineGameTime(),
                    SideTaskState.ACTIVE, "todo", Map.of(), ctx.mainlineToken());
        }
        @Override public SideTaskVerdict evaluate(SideTask task, SideTaskWorldPort world) {
            if (task.pastDeadline(world.gameTime())) return SideTaskVerdict.skip("expired");
            return completeOnNext ? SideTaskVerdict.complete("done") : SideTaskVerdict.keep("todo", Map.of());
        }
        @Override public String renderContext(SideTask task, SideTaskWorldPort world) { return "daily"; }
    }

    private static SideTaskEngine engine(RecordingEvents events, SideTaskType type) {
        SideTaskEngine e = new SideTaskEngine(events, token -> token);
        e.registerType(type);
        e.registerRecurring(new RecurringSideTaskSpec("daily", type.typeId(), 1, 0,
                RecurringSideTaskSpec.MissedPolicy.SKIP, true), type.typeId());
        return e;
    }

    @Test void dueThenCompleteThenNextDay() {
        RecordingEvents ev = new RecordingEvents();
        SideTaskEngine e = engine(ev, new DailyType(true, 200));
        FakeWorldPort w = new FakeWorldPort().at(100, 0);
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C));
        assertTrue(ev.saw("side_task_due"));
        assertEquals(1, ev.count("side_task_created"));
        e.tick(C, w, null, false);
        assertFalse(e.isActive(C));
        assertTrue(ev.saw("side_task_completed"));
        e.tick(C, w, null, false);
        assertEquals(1, ev.count("side_task_created"), "同日不得重复");
        w.at(500, 1);
        e.tick(C, w, null, false);
        assertEquals(2, ev.count("side_task_created"), "次日应再生成");
    }

    @Test void bodyBusyAndDeathOccupyingBlockGeneration() {
        RecordingEvents ev = new RecordingEvents();
        SideTaskEngine e = engine(ev, new DailyType(false, 200));
        FakeWorldPort w = new FakeWorldPort().at(100, 0).bodyBusy(true);
        e.tick(C, w, null, false);
        assertFalse(e.isActive(C));
        w.bodyBusy(false);
        e.tick(C, w, null, true);
        assertFalse(e.isActive(C), "death 占用时不生成");
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C));
    }

    @Test void expireSkipsWithReasonAndWorldKeySeparates() {
        RecordingEvents ev = new RecordingEvents();
        SideTaskEngine e = engine(ev, new DailyType(false, 0));
        FakeWorldPort w = new FakeWorldPort().at(100, 0).world("minecraft:overworld");
        e.tick(C, w, null, false);
        e.tick(C, w, null, false); // past deadline -> skipped
        assertFalse(e.isActive(C));
        assertTrue(ev.saw("side_task_skipped"));
        int at = ev.events.indexOf("side_task_skipped");
        assertTrue(at >= 0);
        assertFalse(String.valueOf(ev.payloads.get(at).get("reason")).isBlank(), "skipped 要带 reason");
        // 同一 gameDay 换世界不得被 overworld 的 handled 吃掉
        w.world("minecraft:the_nether");
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C), "跨世界键必须独立");
    }

    @Test void promptOnlyStillSupportedForNonExecutableHints() {
        RecordingEvents ev = new RecordingEvents();
        SideTaskEngine e = new SideTaskEngine(ev, token -> token);
        e.registerRecurring(new RecurringSideTaskSpec("p", "daily_hint", 1, 0,
                RecurringSideTaskSpec.MissedPolicy.SKIP, true, true), "daily_hint");
        FakeWorldPort w = new FakeWorldPort().at(100, 0);
        e.tick(C, w, null, false);
        assertFalse(e.isActive(C));
        assertEquals(1, ev.count("side_task_due"));
    }

    @Test void dailyTypeCreatesExecutableSideTaskAndCompletes() {
        RecordingEvents ev = new RecordingEvents();
        SideTaskEngine e = new SideTaskEngine(ev, token -> token);
        e.registerType(new DailyMaintenanceSideTaskType());
        var data = Map.of("description", "收麦", "asset_key", "minecraft:wheat", "minimum", "1");
        e.registerRecurring(new RecurringSideTaskSpec("d", DailyMaintenanceSideTaskType.TYPE_ID, 1, 0,
                RecurringSideTaskSpec.MissedPolicy.SKIP, true, false, data),
                DailyMaintenanceSideTaskType.TYPE_ID);
        FakeWorldPort w = new FakeWorldPort().at(100, 0);
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C), "daily 必须真的创建 active 支线");
        assertTrue(ev.saw("side_task_created"));
        assertTrue(ev.saw("side_task_activated"));
        w.item("minecraft:wheat", 1);
        e.tick(C, w, null, false);
        assertFalse(e.isActive(C));
        assertTrue(ev.saw("side_task_completed"));
        assertTrue(ev.saw("side_task_resumed_mainline"));
    }

    @Test void dailyTypeTtlExpiresSilently() {
        RecordingEvents ev = new RecordingEvents();
        SideTaskEngine e = new SideTaskEngine(ev, token -> token);
        e.registerType(new DailyMaintenanceSideTaskType());
        e.registerRecurring(new RecurringSideTaskSpec("d2", DailyMaintenanceSideTaskType.TYPE_ID, 1, 0,
                RecurringSideTaskSpec.MissedPolicy.SKIP, true, false,
                Map.of("description", "x", "asset_key", "minecraft:wheat", "minimum", "9")),
                DailyMaintenanceSideTaskType.TYPE_ID);
        FakeWorldPort w = new FakeWorldPort().at(100, 0);
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C));
        w.at(100 + 20L * 60L * 10L + 1, 0);
        e.tick(C, w, null, false);
        assertFalse(e.isActive(C));
        assertTrue(ev.saw("side_task_skipped"));
    }

    @Test void activeDailyYieldsWhenMainlineNeedsBody() {
        RecordingEvents ev = new RecordingEvents();
        SideTaskEngine e = new SideTaskEngine(ev, token -> token);
        e.registerType(new DailyMaintenanceSideTaskType());
        e.registerRecurring(new RecurringSideTaskSpec("dy", DailyMaintenanceSideTaskType.TYPE_ID, 1, 0,
                RecurringSideTaskSpec.MissedPolicy.SKIP, true, false,
                Map.of("description", "x", "asset_key", "minecraft:wheat", "minimum", "9")),
                DailyMaintenanceSideTaskType.TYPE_ID);
        FakeWorldPort w = new FakeWorldPort().at(100, 0);
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C));
        w.bodyBusy(true);
        e.tick(C, w, null, false);
        assertFalse(e.isActive(C), "主线要身体时 daily 必须让位");
        assertTrue(ev.saw("side_task_cancelled"));
        assertTrue(ev.saw("side_task_resumed_mainline"));
    }
}
