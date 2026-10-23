package com.dwinovo.numen.rdd.side.recovery;

import com.dwinovo.numen.rdd.side.SideTask;
import com.dwinovo.numen.rdd.side.SideTaskDecision;
import com.dwinovo.numen.rdd.side.SideTaskType;
import com.dwinovo.numen.rdd.side.SideTaskVerdict;
import com.dwinovo.numen.rdd.side.SideTaskWorldPort;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 恢复支线的纯 JVM 行为：点名 → 创建带负载 → 按「背包里有没有」判完成。
 *
 * <p>这组测试守的是主人的那条语义：**丢了东西就插支线，补回来才结束，别去动主线计划**。
 */
class RecoverySideTaskTypeTest {

    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    /** 只回答 countItem 的最小端口；其余按「白天、不动」处理。 */
    private static final class Port implements SideTaskWorldPort {
        final Map<String, Integer> counts = new HashMap<>();

        Port with(String item, int n) {
            counts.put(item, n);
            return this;
        }

        @Override public long gameTime() { return 1000L; }
        @Override public long dayTime() { return 1000L; }
        @Override public long gameDay() { return 0L; }
        @Override public boolean hasDayNightCycle() { return true; }
        @Override public String worldId() { return "minecraft:overworld"; }
        @Override public boolean isSleeping(UUID companionId) { return false; }
        @Override public boolean isDay() { return true; }
        @Override public Wake pollWake(UUID companionId) { return null; }
        @Override public int countItem(UUID companionId, String itemId) {
            return counts.getOrDefault(itemId, 0);
        }
    }

    private static SideTask task(Map<String, String> data) {
        return new SideTask(C, "recovery-1-" + C.toString().substring(0, 8), RecoverySideTaskType.TYPE_ID,
                1L, 0L, 1000L, 1000L + RecoverySideTaskType.DEADLINE_TICKS,
                com.dwinovo.numen.rdd.side.SideTaskState.ACTIVE,
                RecoverySideTaskType.PHASE_SEEKING, data, null);
    }

    @Test
    void triggerOnlyFiresWhenExpected() {
        RecoverySideTaskTrigger trigger = new RecoverySideTaskTrigger();
        RecoverySideTaskTrigger.Context ctx =
                new RecoverySideTaskTrigger.Context(C, "minecraft:overworld", 0L, 1000L, true);

        assertFalse(trigger.fires(ctx), "没点名就不该触发");
        RecoverySideTaskTrigger.expect(C, "minecraft:bread", 2);
        assertTrue(trigger.fires(ctx), "点名后应该触发（只看不取）");
        assertTrue(trigger.fires(ctx), "fires 不得消费负载——真正取走的是 create()");

        Map<String, String> taken = RecoverySideTaskTrigger.take(C);
        assertEquals("minecraft:bread", taken.get("item"));
        assertEquals("2", taken.get("count"));
        assertFalse(trigger.fires(ctx), "取走之后不该再触发");
    }

    @Test
    void createCarriesPendingPayload() {
        RecoverySideTaskTrigger.expect(C, "minecraft:diamond", 3);
        RecoverySideTaskType type = new RecoverySideTaskType();
        SideTask created = type.create(new SideTaskType.CreationContext(
                C, "recovery-1", 1L, 0L, 1000L, 1000L + type.deadlineTicks(), null, Map.of()));

        assertEquals("minecraft:diamond", created.data().get("item"));
        assertEquals("3", created.data().get("count"));
        assertEquals(RecoverySideTaskType.PHASE_SEEKING, created.phase());
        assertEquals(RecoverySideTaskType.TYPE_ID, created.typeId());
        // 4096 char 上下文里必须点出目标物品，否则执行 AI 不知道该找什么
        assertTrue(type.renderContext(created, new Port()).contains("minecraft:diamond"));
    }

    @Test
    void completesOnlyWhenItemIsBack() {
        RecoverySideTaskType type = new RecoverySideTaskType();
        SideTask t = task(Map.of("item", "minecraft:bread", "count", "1"));

        SideTaskVerdict missing = type.evaluate(t, new Port());
        assertEquals(SideTaskVerdict.Kind.CONTINUE, missing.kind(), "没有面包时不能算完成");
        assertEquals("0", missing.data().get("have"));

        SideTaskVerdict back = type.evaluate(t, new Port().with("minecraft:bread", 1));
        assertEquals(SideTaskVerdict.Kind.COMPLETE, back.kind(), "面包回来了就该完成");
        assertTrue(back.evidence().contains("minecraft:bread"));
    }

    @Test
    void countShortfallIsReported() {
        RecoverySideTaskType type = new RecoverySideTaskType();
        SideTask t = task(Map.of("item", "minecraft:cobblestone", "count", "8"));

        SideTaskVerdict v = type.evaluate(t, new Port().with("minecraft:cobblestone", 3));
        assertEquals(SideTaskVerdict.Kind.CONTINUE, v.kind(), "只有 3 块，不够 8 块");
        assertEquals("3", v.data().get("have"));
        assertEquals("8", v.data().get("need"));
    }

    @Test
    void noItemSpecifiedNeverCompletes() {
        RecoverySideTaskType type = new RecoverySideTaskType();
        SideTask t = task(Map.of());
        SideTaskVerdict v = type.evaluate(t, new Port().with("minecraft:bread", 64));
        // 没写目标物品 = 没有可判定的完成条件，只能等超时收掉（不许瞎判成功）
        assertEquals(SideTaskVerdict.Kind.CONTINUE, v.kind());
    }

    @Test
    void decideAlwaysExecutes() {
        RecoverySideTaskType type = new RecoverySideTaskType();
        SideTaskDecision d = type.decide(C, new Port());
        assertEquals(SideTaskDecision.Kind.EXECUTE, d.kind());
    }

    @Test
    void deadlineIsBounded() {
        // 掉落物 5 分钟消失；支线保护超时必须有界，否则主线会被冻死
        assertTrue(RecoverySideTaskType.DEADLINE_TICKS > 0);
        assertTrue(RecoverySideTaskType.DEADLINE_TICKS <= 24_000L, "上限 20 分钟");
        assertTrue(List.of("SEEKING_DROPS", "COLLECTING").contains(RecoverySideTaskType.PHASE_SEEKING));
    }
}
