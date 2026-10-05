package com.dwinovo.numen.plugins.learner.core;

import com.dwinovo.numen.api.carrier.CarrierChain;
import com.dwinovo.numen.api.carrier.CarrierRuleStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 携带器审批流的离线检查（2026-10-05）。
 *
 * <p>重点守三件事：
 * <ol>
 *   <li><b>未批准就不生效</b> —— 这是审批流存在的全部理由；</li>
 *   <li><b>批准必须带理由</b> —— 没理由的批准无法追责；</li>
 *   <li><b>批准完真的生效</b> —— 「批准成功但运行时没变化」是最坏的哑故障。</li>
 * </ol>
 */
class CarrierApprovalFlowTest {

    private static final UUID C = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static Path tmp(String n) {
        return Path.of(System.getProperty("java.io.tmpdir"),
                "carrier-approval-" + n + "-" + System.nanoTime());
    }

    private static CarrierArtifactAdopter adopterWithDraft(Path dir, String body) {
        ArtifactOutbox ob = new ArtifactOutbox(dir.resolve("outbox"));
        ob.submit(ArtifactOutbox.Kind.CARRIER, C, "rev-1", "m-1", "my_carrier", body);
        return new CarrierArtifactAdopter(dir, ob);
    }

    private static String draftJson(String when, String... carry) {
        StringBuilder sb = new StringBuilder("{\"name\":\"my_carrier\",\"when\":\"" + when + "\",\"carry\":[");
        for (int i = 0; i < carry.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(carry[i]).append('"');
        }
        return sb.append("],\"fix\":[]}").toString();
    }

    @Test
    void submit_registersCandidate_butDoesNotTakeEffect() {
        Path dir = tmp("submit");
        // ★ 先 install 自己的目录：生效链是**静态**的（OVERRIDE），
        //   不先装就会读到上一个测试残留的规则 → 跨测试泄漏、结果与执行顺序有关。
        //   顺序与生产一致（LearnerPlugin.setup 也是先 install 再让 adopter 干活）。
        CarrierRuleStore.install(dir);
        CarrierArtifactAdopter ad = adopterWithDraft(dir, draftJson("hp<=4", "oak_log"));

        var rep = ad.adoptAll();
        assertEquals(1, rep.scanned(), "应当扫到 1 条草稿");
        assertEquals(1, rep.submitted(), "应当登记成 1 条候选");
        assertEquals(1, ad.candidates().size(), "候选表里应有 1 条");
        assertEquals("PENDING", ad.candidateStatus(rep.rows().get(0).artifactId()));

        // ★ 关键断言：还没批准 ⇒ 生效链里不许有它
        for (CarrierChain.Rule r : CarrierRuleStore.effective()) {
            assertFalse("my_carrier".equals(r.name()),
                    "没批准就进生效链 = 审批流形同虚设");
        }
    }

    @Test
    void approve_requiresReason() {
        Path dir = tmp("approve_requiresReason");
        CarrierRuleStore.install(dir);
        CarrierArtifactAdopter ad = adopterWithDraft(dir, draftJson("hp<=4", "oak_log"));
        var rep = ad.adoptAll();
        String id = rep.rows().get(0).artifactId();

        var e = assertThrows(IllegalArgumentException.class, () -> ad.approve(id, "  "));
        assertTrue(e.getMessage().contains("理由"),
                "报错要说清是缺理由，调用方才知道怎么改: " + e.getMessage());
        assertEquals("PENDING", ad.candidateStatus(id), "被拒的批准不许改变状态");
    }

    @Test
    void approve_takesEffectAndReloadWorks() {
        Path dir = tmp("effect");
        CarrierArtifactAdopter ad = adopterWithDraft(dir, draftJson("hp<=4", "oak_log", "torch"));
        var rep = ad.adoptAll();
        String id = rep.rows().get(0).artifactId();

        CarrierRuleStore.install(dir);
        var rule = ad.approve(id, "低血量时带木头照明，实机有用");

        assertEquals("my_carrier", rule.name());
        assertEquals(java.util.List.of("oak_log", "torch"), rule.carry());
        // 批准后必须真在生效链里 —— 否则就是「批准成功但游戏里没变化」
        boolean found = CarrierRuleStore.effective().stream()
                .anyMatch(r -> "my_carrier".equals(r.name()));
        assertTrue(found, "批准后规则必须在生效链里（否则审批是假的）");

        // 且谓词真能用：低血量成立、不成立两种都要验
        var low = CarrierChain.factsOf(Map.of("hp", "3"), "hp=3");
        assertTrue(rule.applies().test(low), "hp<=4 在 hp=3 时应当成立");
        var high = CarrierChain.factsOf(Map.of("hp", "20"), "hp=20");
        assertFalse(rule.applies().test(high), "hp<=4 在 hp=20 时不该成立");

        // 候选状态要变成 APPROVED，留痕
        assertEquals("APPROVED", ad.candidateStatus(id));
    }

    @Test
    void reject_marksRejected_keepsRecord_andDoesNotTakeEffect() {
        Path dir = tmp("reject");
        CarrierArtifactAdopter ad = adopterWithDraft(dir, draftJson("hp<=4", "oak_log"));
        var rep = ad.adoptAll();
        String id = rep.rows().get(0).artifactId();

        CarrierRuleStore.install(dir);
        ad.reject(id, "带木头不解决实际问题");

        assertEquals("REJECTED", ad.candidateStatus(id));
        assertEquals(1, ad.candidates().size(), "拒收不许删记录 —— 拒了要能回答「当时为什么拒」");
        for (CarrierChain.Rule r : CarrierRuleStore.effective()) {
            assertFalse("my_carrier".equals(r.name()), "拒收的规则绝不许进生效链");
        }
    }

    @Test
    void badDraft_isRejectedWithReason_notSilentlyDropped() {
        Path dir = tmp("bad");
        CarrierArtifactAdopter ad = adopterWithDraft(dir, "我想写个携带器，条件是血少的时候");
        var rep = ad.adoptAll();
        assertEquals(1, rep.scanned());
        assertEquals(0, rep.submitted(), "散文登记不了");
        assertEquals(1, rep.failed(), "失败必须计数，不许静默");
        assertEquals("REJECTED", rep.rows().get(0).status());
        assertTrue(rep.rows().get(0).detail().length() > 10, "拒收必须带原因: " + rep.rows().get(0));
    }

    @Test
    void draftWithoutWhen_isRejected() {
        // 没条件的规则会对所有情况生效 —— 那不是携带器，是全局开关
        var e = assertThrows(IllegalArgumentException.class,
                () -> CarrierRuleStore.Draft.parse("{\"name\":\"x\",\"carry\":[\"torch\"]}"));
        assertTrue(e.getMessage().contains("when"), "要说清缺 when: " + e.getMessage());
    }

    @Test
    void draftWithUnknownCondition_isRejected_notIgnored() {
        // 忽略不认识的词 = 规则在本不该生效时生效，比报错危险得多
        var e = assertThrows(IllegalArgumentException.class,
                () -> CarrierRuleStore.Draft.parse(draftJson("moon_is_full", "torch")));
        assertTrue(e.getMessage().contains("moon_is_full"),
                "报错要点名那个不认识的词: " + e.getMessage());
    }

    @Test
    void approve_sameNameTwice_failsLoudly() {
        Path dir = tmp("dup");
        CarrierRuleStore.install(dir);
        CarrierArtifactAdopter ad = adopterWithDraft(dir, draftJson("hp<=4", "oak_log"));
        var rep = ad.adoptAll();
        ad.approve(rep.rows().get(0).artifactId(), "第一次批准");

        var e = assertThrows(IllegalStateException.class,
                () -> ad.approve(rep.rows().get(0).artifactId(), "再批一次"));
        assertTrue(e.getMessage().contains("已批准过"), "重复批准要点名原因: " + e.getMessage());
    }
}