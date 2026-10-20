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
 * G3b 契约（IC75-r1 IC03 后半句 + §15.2 stateVersion 最小落地）。
 *
 * <p>钉三件事：陈旧尝试的结果不推进节点（只计数）、stateVersion 只在安全提交点 +1、
 * 快照/持久化都能读到尝试次数与状态版本（监测台不用翻文件）。
 */
class G3bAttemptCasTest {

    private static TaskChain chain() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("asset_key", "minecraft:stone");
        c.put("minimum", 1);
        return new TaskChain(new Goal("g", "试", List.of(
                new PrimaryGoal("p0", "第一段", List.of(
                        Subtask.hardCoded("s1", "石头", c),
                        Subtask.hardCoded("s2", "第二步", c)), List.of()))));
    }

    // ── IC03 后半句：旧尝试的迟到结果不得推进新尝试 ──

    @Test
    void staleAttemptResultIsIgnoredButCounted() {
        TaskChain c = chain();
        c.startCurrent();                       // attempt 1
        c.markFailed("s1", "第一次失败");
        c.retrySubtask("s1");
        c.startCurrent();                       // attempt 2
        assertEquals(2, c.attempts("s1"));

        // 旧尝试（1）的迟到结果：不得把 attempt-2 的节点标成完成
        assertFalse(c.applyHardCodedResult("s1", true, 1),
                "陈旧结果必须被忽略（返回 false）");
        assertEquals(com.dwinovo.numen.rdd.api.SubtaskStatus.RUNNING, c.currentSubtaskStatus(),
                "节点状态不得被旧结果推进");
        assertEquals(1, c.staleResultsIgnored(), "忽略要计数（区分「没跑」与「被挡」）");

        // 当前尝试（2）的结果照常生效
        assertTrue(c.applyHardCodedResult("s1", true, 2));
        assertEquals("COMPLETED", statusOf(c, "s1"), "当前尝试的结果照常推进节点");
    }

    @Test
    void resultFromTheUnknownFutureAttemptIsAlsoIgnored() {
        TaskChain c = chain();
        c.startCurrent();                       // attempt 1
        assertFalse(c.applyHardCodedResult("s1", true, 7), "未来尝试号同样不是当前尝试");
        assertEquals(1, c.staleResultsIgnored());
    }

    @Test
    void legacyTwoArgCallStillWorks() {
        TaskChain c = chain();
        c.startCurrent();
        assertTrue(c.applyHardCodedResult("s1", true), "两参调用仍按当前尝试处理（兼容）");
        assertEquals(0, c.staleResultsIgnored());
    }

    // ── stateVersion：只在安全提交点 +1 ──

    @Test
    void stateVersionBumpsOnCommitsAndNeverOnReads() {
        TaskChain c = chain();
        long v0 = c.stateVersion();
        assertEquals(0, v0, "新建链还没提交过状态");

        c.snapshot();
        c.toJson();
        c.currentSubtask();
        c.currentSubtaskStatus();
        assertEquals(v0, c.stateVersion(), "读/序列化不得改 stateVersion（75号 §15.2）");

        c.startCurrent();
        long afterStart = c.stateVersion();
        assertTrue(afterStart > v0, "开跑是一次安全提交");

        c.applyHardCodedResult("s1", true);
        assertTrue(c.stateVersion() > afterStart, "节点终态提交");

        // 展开注入：用未展开的一级单独造链（已展开的一级会拒绝重展开，守卫本身没变）
        TaskChain lazy = new TaskChain(new Goal("g2", "试2", List.of(
                PrimaryGoal.unexpanded("p1", "懒阶段", List.of()))));
        long beforeExpand = lazy.stateVersion();
        lazy.expandCurrentPrimary(List.of(Subtask.hardCoded("e1", "注入",
                Map.of("asset_key", "minecraft:dirt", "minimum", 1))));
        assertTrue(lazy.stateVersion() > beforeExpand, "展开注入是计划结构提交");
    }

    /** 从快照里读某个二级的状态（c.statuses() 不是公开 API）。 */
    private static String statusOf(TaskChain chain, String subtaskId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> view = chain.snapshot();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> primaries = (List<Map<String, Object>>) view.get("primaries");
        for (Map<String, Object> p : primaries) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> subs = (List<Map<String, Object>>) p.get("subtasks");
            for (Map<String, Object> s : subs) {
                if (subtaskId.equals(s.get("id"))) {
                    return String.valueOf(s.get("status"));
                }
            }
        }
        throw new AssertionError("subtask not found: " + subtaskId);
    }

    @Test
    void stateVersionSurvivesRoundTrip() {
        TaskChain c = chain();
        c.startCurrent();
        long before = c.stateVersion();
        TaskChain restored = TaskChain.fromJson(c.toJson());
        assertEquals(before, restored.stateVersion(), "版本必须随链持久化（判据活过重启）");
    }

    @Test
    void oldSaveWithoutStateVersionLoadsAsZero() {
        TaskChain c = chain();
        String legacy = c.toJson()
                .replaceAll(",\"stateVersion\":\\d+", "")
                .replaceAll(",\"staleResultsIgnored\":\\d+", "");
        assertFalse(legacy.contains("stateVersion"));
        TaskChain restored = TaskChain.fromJson(legacy);
        assertEquals(0, restored.stateVersion(), "旧存档缺字段 = 0，不伪造提交历史");
        assertEquals(0, restored.staleResultsIgnored());
    }

    // ── 快照可观测（G3a 债）──

    @Test
    void snapshotExposesAttemptsAndVersions() {
        TaskChain c = chain();
        c.startCurrent();
        Map<String, Object> view = c.snapshot();
        assertEquals(1L, view.get("stateVersion"));
        assertEquals(0, view.get("staleResultsIgnored"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> primaries = (List<Map<String, Object>>) view.get("primaries");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subs = (List<Map<String, Object>>) primaries.get(0).get("subtasks");
        assertEquals(1, subs.get(0).get("attempts"), "当前二级 attempts=1 进快照");
        assertEquals(0, subs.get(1).get("attempts"), "未开跑的二级 attempts=0");
    }
}
