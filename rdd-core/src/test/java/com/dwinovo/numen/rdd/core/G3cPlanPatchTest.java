package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G3c 契约（75号 §15.3 / IC06）：计划补丁只碰「还没开始」的节点，提交走版本 CAS，失败不留半更新。
 */
class G3cPlanPatchTest {

    private static Map<String, Object> cond(String key, int min) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("asset_key", key);
        c.put("minimum", min);
        return c;
    }

    private static TaskChain chain() {
        return new TaskChain(new Goal("g", "试", List.of(
                new PrimaryGoal("p0", "第一段", List.of(
                        Subtask.hardCoded("s1", "石头", cond("minecraft:stone", 1)),
                        Subtask.hardCoded("s2", "木头", cond("minecraft:oak_log", 2)),
                        Subtask.hardCoded("s3", "土", cond("minecraft:dirt", 3))), List.of()))));
    }

    private static PlanPatch patch(String id, long base, PlanPatch.Op... ops) {
        return new PlanPatch(id, "p0", base, List.of(ops), "测试");
    }

    // ── propose：只校验、不改状态 ──

    @Test
    void proposeNeverMutates() {
        TaskChain c = chain();
        long v = c.stateVersion();
        int before = c.currentPrimary().subtasks().size();

        var r = c.proposePatch(patch("p1", v, PlanPatch.Op.add("s4", "新节点", cond("minecraft:coal", 1))));
        assertTrue(r.ok(), r.why());
        assertEquals(v, c.stateVersion(), "propose 不得改版本");
        assertEquals(before, c.currentPrimary().subtasks().size(), "propose 不得改计划");
    }

    @Test
    void staleBaseVersionIsRejected() {
        TaskChain c = chain();
        c.startCurrent();   // 版本前进
        var r = c.proposePatch(patch("p1", 0L, PlanPatch.Op.drop("s2")));
        assertFalse(r.ok());
        assertEquals("STALE_VERSION", r.code());
    }

    @Test
    void cannotPatchCompletedOrRunningNodes() {
        TaskChain c = chain();
        long v = c.stateVersion();
        c.startCurrent();
        c.applyHardCodedResult("s1", true);   // s1 完成（链推进；下一个节点此时尚未 start）
        long v2 = c.stateVersion();

        var onCompleted = c.proposePatch(patch("p1", v2, PlanPatch.Op.replace("s1", "改", cond("minecraft:dirt", 1))));
        assertFalse(onCompleted.ok());
        assertEquals("NODE_NOT_PENDING", onCompleted.code());

        // 真正在跑的节点：新链 startCurrent 后 s1 就是 RUNNING
        TaskChain running = chain();
        running.startCurrent();
        long rv = running.stateVersion();
        var onRunning = running.proposePatch(patch("p2", rv, PlanPatch.Op.drop("s1")));
        assertFalse(onRunning.ok(), "在跑的节点不许被补丁删掉");
        assertEquals("NODE_NOT_PENDING", onRunning.code());
        assertTrue(v2 > v);
    }

    // ── commit：CAS 原子应用 ──

    @Test
    void commitAppliesAtomicallyAndBumpsVersion() {
        TaskChain c = chain();
        long v = c.stateVersion();
        PlanPatch p = patch("p1", v,
                PlanPatch.Op.add("s4", "煤", cond("minecraft:coal", 1)),
                PlanPatch.Op.drop("s3"));

        var r = c.commitPatch(p, v);
        assertTrue(r.ok(), r.why());
        assertTrue(r.stateVersion() > v, "提交成功要推进版本");

        List<String> ids = c.currentPrimary().subtasks().stream().map(Subtask::id).toList();
        assertEquals(List.of("s1", "s2", "s4"), ids, "ADD 追加在末尾、DROP 删除目标");
        assertNull(c.activeExecutionId());
        assertEquals(Map.of("p1", r.stateVersion()), c.committedPatchesView(), "补丁身份可查");
    }

    @Test
    void samePatchCannotBeCommittedTwice() {
        TaskChain c = chain();
        long v = c.stateVersion();
        PlanPatch p = patch("p1", v, PlanPatch.Op.add("s4", "煤", cond("minecraft:coal", 1)));
        assertTrue(c.commitPatch(p, v).ok());
        var again = c.commitPatch(p, v);
        assertFalse(again.ok(), "同一补丁重投必须拒绝（幂等键）");
        assertEquals("STALE_VERSION", again.code(), "版本已前进：先被 CAS 挡下");
    }

    @Test
    void twoPatchesOnSameBaseOnlyOneWins() {
        TaskChain c = chain();
        long v = c.stateVersion();
        PlanPatch a = patch("pa", v, PlanPatch.Op.add("x1", "甲", cond("minecraft:coal", 1)));
        PlanPatch b = patch("pb", v, PlanPatch.Op.add("x2", "乙", cond("minecraft:iron_ore", 1)));

        assertTrue(c.commitPatch(a, v).ok(), "先到的补丁成功");
        var second = c.commitPatch(b, v);
        assertFalse(second.ok(), "同 baseVersion 的第二个补丁必须失败（IC06）");
        assertEquals("STALE_VERSION", second.code());
        assertFalse(c.currentPrimary().subtasks().stream().anyMatch(s -> s.id().equals("x2")),
                "失败的补丁不得留下半更新");
    }

    @Test
    void patchCannotEmptyThePrimary() {
        TaskChain c = new TaskChain(new Goal("g2", "试2", List.of(
                new PrimaryGoal("p0", "唯一", List.of(
                        Subtask.hardCoded("only", "唯一节点", cond("minecraft:stone", 1))), List.of()))));
        long v = c.stateVersion();
        var r = c.commitPatch(patch("p1", v, PlanPatch.Op.drop("only")), v);
        assertFalse(r.ok());
        assertEquals("WOULD_EMPTY", r.code());
        assertEquals(1, c.currentPrimary().subtasks().size());
    }

    @Test
    void duplicateAndMissingNodesAreRejected() {
        TaskChain c = chain();
        long v = c.stateVersion();
        assertEquals("DUPLICATE_ID",
                c.proposePatch(patch("p1", v, PlanPatch.Op.add("s1", "撞号", cond("minecraft:coal", 1)))).code());
        assertEquals("NODE_MISSING",
                c.proposePatch(patch("p2", v, PlanPatch.Op.drop("ghost"))).code());
    }

    @Test
    void replaceKeepsIdentityAndBodySlot() {
        TaskChain c = chain();
        long v = c.stateVersion();
        var r = c.commitPatch(patch("p1", v,
                PlanPatch.Op.replace("s2", "橡木 4 个", cond("minecraft:oak_log", 4))), v);
        assertTrue(r.ok(), r.why());
        Subtask s2 = c.currentPrimary().subtasks().stream()
                .filter(s -> s.id().equals("s2")).findFirst().orElseThrow();
        assertEquals("橡木 4 个", s2.description(), "同 id 原地替换（未开始的节点）");
        assertEquals(4, ((Number) s2.condition().get("minimum")).intValue());
    }
}
