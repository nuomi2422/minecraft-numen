package com.dwinovo.numen.rdd.fact;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共同事实 shadow 对账的离线检查（第三批 N1，2026-10-05）。
 *
 * <p>守三件事：
 * <ol>
 *   <li><b>只读</b> —— 对账绝不改任何一侧文件；</li>
 *   <li><b>读不到 ≠ 一致</b> —— 少一侧必须把报告标成不可信，否则空报告会骗人；</li>
 *   <li><b>「真没做」不报</b> —— 报差异不能靠凑数，否则没人看。</li>
 * </ol>
 */
class FactShadowReconcilerTest {

    private static Path tmp(String n) {
        return Path.of(System.getProperty("java.io.tmpdir"), "shadow-" + n + "-" + System.nanoTime());
    }

    private static Path facts(Path dir, String json) throws Exception {
        Files.createDirectories(dir);
        Path f = dir.resolve("facts.json");
        Files.writeString(f, json, StandardCharsets.UTF_8);
        return f;
    }

    private static Path usage(Path dir, String... lines) throws Exception {
        Files.createDirectories(dir);
        Path f = dir.resolve("usage-ledger.jsonl");
        Files.writeString(f, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void completedButUnused_isReported() throws Exception {
        Path d = tmp("a");
        Path f = facts(d, "{\"stages\":{\"hp_guard\":{\"status\":\"COMPLETED\"}}}");
        Path u = usage(d);
        var r = FactShadowReconciler.reconcile(f, u);
        assertEquals(1, r.divergences().size(), "完成却没人用，该报: " + r.divergences());
        assertEquals(FactShadowReconciler.Severity.COMPLETED_BUT_UNUSED,
                r.divergences().get(0).severity());
        assertTrue(r.trustworthy(), "两侧都读到 ⇒ 可信");
        assertFalse(r.clean(), "有差异就不算 clean");
    }

    @Test
    void completedAndUsedSuccessfully_isClean() throws Exception {
        Path d = tmp("b");
        Path f = facts(d, "{\"stages\":{\"hp_guard\":{\"status\":\"COMPLETED\"}}}");
        Path u = usage(d, "{\"artifact_id\":\"hp_guard\",\"phase\":\"RESULT\",\"outcome\":\"SUCCESS\"}");
        var r = FactShadowReconciler.reconcile(f, u);
        assertTrue(r.clean(), "完成且成功用过 ⇒ 没有差异: " + r.divergences());
    }

    @Test
    void completedButFailed_isTopSeverity() throws Exception {
        Path d = tmp("c");
        Path f = facts(d, "{\"stages\":{\"hp_guard\":{\"status\":\"COMPLETED\"}}}");
        Path u = usage(d, "{\"artifact_id\":\"hp_guard\",\"phase\":\"RESULT\",\"outcome\":\"FAIL\"}");
        var r = FactShadowReconciler.reconcile(f, u);
        assertEquals(FactShadowReconciler.Severity.COMPLETED_BUT_FAILED,
                r.divergences().get(0).severity(), "完成却失败 ⇒ 最可疑，排最前");
    }

    @Test
    void usedButNotRecorded_isReported() throws Exception {
        Path d = tmp("d");
        Path f = facts(d, "{\"stages\":{\"other\":{\"status\":\"PENDING\"}}}");
        Path u = usage(d, "{\"artifact_id\":\"hp_guard\",\"phase\":\"RESULT\",\"outcome\":\"SUCCESS\"}");
        var r = FactShadowReconciler.reconcile(f, u);
        assertEquals(1, r.divergences().size(), "用过但事实没记 ⇒ 下一轮可能重复劳动");
        assertEquals(FactShadowReconciler.Severity.USED_BUT_NOT_RECORDED,
                r.divergences().get(0).severity());
        assertEquals("usage", r.divergences().get(0).side(), "差异归到使用侧，便于定位");
    }

    @Test
    void neitherSide_hasIt_isNotReported() throws Exception {
        // ★ 报差异不能靠凑数：「真没做」不是问题
        Path d = tmp("e");
        Path f = facts(d, "{\"stages\":{\"a\":{\"status\":\"PENDING\"}}}");
        Path u = usage(d);
        var r = FactShadowReconciler.reconcile(f, u);
        assertTrue(r.clean(), "两边都没有 ⇒ 真没做，不该报出来充数: " + r.divergences());
    }

    @Test
    void missingSide_makesReportUntrustworthy() throws Exception {
        Path d = tmp("f");
        Path f = facts(d, "{\"stages\":{\"a\":{\"status\":\"COMPLETED\"}}}");
        Path missingUsage = d.resolve("no-such-ledger.jsonl");

        var r = FactShadowReconciler.reconcile(f, missingUsage);
        assertFalse(r.trustworthy(), "★ 读不到就不该被当成结论（空报告会骗人）");
        assertTrue(r.toMap().containsKey("warning"), "报告里要写明缺哪侧: " + r.toMap());
        assertTrue(String.valueOf(r.toMap().get("warning")).contains("usage"),
                "要说清缺的是使用账本那一侧");
    }

    @Test
    void reconcile_doesNotModifyEitherSide() throws Exception {
        Path d = tmp("g");
        String fj = "{\"stages\":{\"a\":{\"status\":\"COMPLETED\"}}}";
        String ul = "{\"artifact_id\":\"zzz\",\"phase\":\"RESULT\",\"outcome\":\"SUCCESS\"}";
        Path f = facts(d, fj);
        Path u = usage(d, ul);
        long fBefore = Files.getLastModifiedTime(f).toMillis();
        long uBefore = Files.getLastModifiedTime(u).toMillis();
        String fText = Files.readString(f, StandardCharsets.UTF_8);
        String uText = Files.readString(u, StandardCharsets.UTF_8);

        FactShadowReconciler.reconcile(f, u);

        assertEquals(fBefore, Files.getLastModifiedTime(f).toMillis(), "★ shadow 不许改事实文件");
        assertEquals(uBefore, Files.getLastModifiedTime(u).toMillis(), "★ shadow 不许改账本文件");
        assertEquals(fText, Files.readString(f, StandardCharsets.UTF_8));
        assertEquals(uText, Files.readString(u, StandardCharsets.UTF_8));
    }

    @Test
    void corruptSide_yieldsEmptyButNotACrash() throws Exception {
        Path d = tmp("h");
        Path f = facts(d, "{not json at all");
        Path u = usage(d, "also not json");
        var r = FactShadowReconciler.reconcile(f, u);
        // 坏文件不许拖垮对账，但「读到了却解析不出」也不能伪装成一致
        assertTrue(r.divergences().isEmpty(), "坏文件不该产出差异结论");
        assertTrue(r.trustworthy() || r.toMap().containsKey("warning"),
                "要么可信，要么明说不可信 —— 不能两者都不是");
    }
}