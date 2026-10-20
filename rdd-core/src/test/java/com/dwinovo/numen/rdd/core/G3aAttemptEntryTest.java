package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G3a 契约（IC75-r1 IC03）：<b>每一次真正的 PENDING→RUNNING 迁移，attempts 恰好 +1</b>。
 *
 * <p>钉四条路径（普通 start / 激活 / 带 counts / 带 snapshot）与三条反例
 * （读状态不增、非 PENDING 再开抛、恢复不重算）。
 */
class G3aAttemptEntryTest {

    private static TaskChain chain() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("asset_key", "minecraft:stone");
        c.put("minimum", 1);
        return new TaskChain(new Goal("g", "试", List.of(
                new PrimaryGoal("p0", "第一段", List.of(Subtask.hardCoded("s1", "石头", c)), List.of()),
                new PrimaryGoal("p1", "第二段", List.of(Subtask.hardCoded("s2", "木头", c)), List.of()))));
    }

    private static PlanningAssetSnapshot snapshot(int stone) {
        return PlanningAssetSnapshot.from(Map.of("minecraft:stone", stone), new AssetRegistry());
    }

    // ── 四条启动路径各计一次 ──

    @Test
    void plainStartCountsExactlyOnce() {
        TaskChain c = chain();
        assertEquals(0, c.attempts("s1"), "开跑前 0");
        c.startCurrent();
        assertEquals(1, c.attempts("s1"));
        assertThrows(IllegalStateException.class, c::startCurrent,
                "已在 RUNNING 再开必须抛（不许静默 +1）");
        assertEquals(1, c.attempts("s1"), "失败的再开不得改动计数");
    }

    @Test
    void activationCountsExactlyOnce() {
        TaskChain c = chain();
        assertTrue(c.activateCurrent(Map.of("minecraft:stone", 1)));
        assertEquals(1, c.attempts("s1"), "激活 = 开跑一次（IC03：各路径计数一致）");
    }

    @Test
    void activationWithRegistryCountsExactlyOnce() {
        TaskChain c = chain();
        AssetRegistry assets = new AssetRegistry();
        assets.apply(new com.dwinovo.numen.rdd.api.Observation("o1", "inventory_scan", "test",
                        "minecraft:overworld", 1L, Map.of("count", 2)),
                "minecraft:stone", com.dwinovo.numen.rdd.api.AssetScope.GLOBAL, null);
        assertTrue(c.activateCurrentWithRegistry(assets));
        assertEquals(1, c.attempts("s1"));
    }

    @Test
    void activationWithSnapshotCountsExactlyOnce() {
        TaskChain c = chain();
        assertTrue(c.activateCurrentWithSnapshot(snapshot(3)));
        assertEquals(1, c.attempts("s1"));
    }

    @Test
    void countsVariantDoesNotDoubleCount() {
        TaskChain c = chain();
        c.startCurrentWithCounts(Map.of("minecraft:stone", 1));
        assertEquals(1, c.attempts("s1"), "startCurrentWithCounts 走 startCurrent，只能算一次");
    }

    // ── 反例：读不增、恢复不重算、retry 才 +1 ──

    @Test
    void readingStatusNeverChangesAttempts() {
        TaskChain c = chain();
        c.startCurrent();
        int before = c.attempts("s1");
        c.snapshot();
        c.toJson();
        c.currentSubtask();
        c.currentSubtaskStatus();
        c.currentPrimary();
        assertEquals(before, c.attempts("s1"), "读/轮询不得增加尝试计数（IC03）");
    }

    @Test
    void retryCountsAsANewAttempt() {
        TaskChain c = chain();
        c.startCurrent();
        c.markFailed("s1", "一次失败");
        c.retrySubtask("s1");
        c.startCurrent();
        assertEquals(2, c.attempts("s1"), "重试 = 第二次真正开跑");
    }

    @Test
    void restoreKeepsCountsAndDoesNotInventOne() {
        TaskChain c = chain();
        c.startCurrent();
        TaskChain restored = TaskChain.fromJson(c.toJson());
        assertEquals(1, restored.attempts("s1"), "恢复沿用磁盘计数，不重算");
        // 恢复后主链在 RECOVERING：必须先由宿主拍板，不能直接再开一次「白送」计数
        assertThrows(IllegalStateException.class, restored::startCurrent);
        assertEquals(1, restored.attempts("s1"));
    }

    @Test
    void activationStillHonoursDependencyGate() {
        // 依赖门看的是 waitFor（前置资产），不是二级的完成条件 —— 拿 waitFor 造闸门
        Map<String, Object> cond = new LinkedHashMap<>();
        cond.put("asset_key", "minecraft:stone");
        cond.put("minimum", 1);
        TaskChain c = new TaskChain(new Goal("g3", "试3", List.of(
                new PrimaryGoal("p0", "采石", List.of(Subtask.hardCoded("s1", "石头", cond)),
                        List.of(new AssetRequirement("minecraft:stone", 1))))));
        assertFalse(c.activateCurrent(Map.of()), "没有石头：闸门拦下，不进入 RUNNING");
        assertEquals(0, c.attempts("s1"), "被闸门拦下不算一次开跑");
        assertTrue(c.activateCurrent(Map.of("minecraft:stone", 1)));
        assertEquals(1, c.attempts("s1"));
    }

    @Test
    void baselinePolicyIsUnchangedByTheUnification() {
        // plain activate 照旧不拍基线（调用方随后显式 capture）；带 snapshot 的两条路照旧拍
        TaskChain plain = chain();
        plain.activateCurrent(Map.of("minecraft:stone", 1));
        assertNull(plain.acquireBaseline("s1"), "plain activate 不拍基线（既有语义）");

        TaskChain withSnap = chain();
        withSnap.activateCurrentWithSnapshot(snapshot(3));
        assertNotNull(withSnap.acquireBaseline("s1"), "带 snapshot 的激活照旧锁基线");
    }

    @Test
    void attemptRequiresPendingEvenForActivation() {
        TaskChain c = chain();
        c.activateCurrent(Map.of("minecraft:stone", 1));
        // 同一 primary 已 ACTIVE：再激活应抛（既有守卫），不产生第二次计数
        assertThrows(IllegalStateException.class, () -> c.activateCurrent(Map.of("minecraft:stone", 1)));
        assertEquals(1, c.attempts("s1"));
    }

    @Test
    void waitForStillGatesWithSameSemantics() {
        Map<String, Object> cond = new LinkedHashMap<>();
        cond.put("asset_key", "minecraft:torch");
        cond.put("minimum", 16);
        TaskChain c = new TaskChain(new Goal("g2", "试2", List.of(
                new PrimaryGoal("p0", "下矿", List.of(Subtask.hardCoded("s1", "火把", cond)),
                        List.of(new AssetRequirement("minecraft:torch", 16))))));
        assertFalse(c.activateCurrent(Map.of("minecraft:torch", 4)), "前置不足：WAITING 且不算开跑");
        assertEquals(0, c.attempts("s1"));
        assertTrue(c.activateCurrent(Map.of("minecraft:torch", 16)));
        assertEquals(1, c.attempts("s1"));
    }
}
