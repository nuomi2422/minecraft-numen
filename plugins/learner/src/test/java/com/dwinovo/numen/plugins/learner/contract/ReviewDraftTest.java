package com.dwinovo.numen.plugins.learner.contract;

import com.dwinovo.numen.plugins.learner.blocks.EnvBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复盘草稿的证据硬约束（158 §5）。
 *
 * <p>钉死：没有断言/没有证据/决策点不引上下文 → REJECT；
 * 证据核不了 → PASS_WITH_UNRESOLVED（<b>不</b>折成 PASS）。
 */
class ReviewDraftTest {

    private static final Function<String, Optional<EnvBlock>> LOOKUP = id -> switch (id) {
        case "dc-turn-3" -> Optional.of(TestBlocks.decisionContext("dc-turn-3"));
        case "summary-s-1" -> Optional.of(TestBlocks.summary("summary-s-1"));
        default -> Optional.empty();
    };

    private static ReviewDraft.Claim claim(ReviewDraft.Section s, String text, String... refs) {
        List<EvidenceRef> ev = java.util.Arrays.stream(refs).map(EvidenceRef::parse).toList();
        return new ReviewDraft.Claim(s, text, ev);
    }

    @Test
    void 全齐是PASS() {
        ReviewDraft.Draft d = new ReviewDraft.Draft("s-1", List.of(
                claim(ReviewDraft.Section.FACT, "被僵尸打死在洞口", "block:dc-turn-3"),
                claim(ReviewDraft.Section.DIRECT_CAUSE, "血量不足仍继续挖矿", "block:dc-turn-3"),
                claim(ReviewDraft.Section.DECISION_POINT, "当时不该在夜里出门", "block:dc-turn-3")));

        ReviewDraft.Validation v = ReviewDraft.validate(d, LOOKUP);
        assertEquals(ReviewDraft.Status.PASS, v.status());
        assertTrue(v.accepted());
        assertTrue(v.unresolved().isEmpty());
    }

    @Test
    void 一条断言都没有是REJECT() {
        ReviewDraft.Validation v = ReviewDraft.validate(new ReviewDraft.Draft("s-1", List.of()), LOOKUP);
        assertEquals(ReviewDraft.Status.REJECT, v.status());
        assertEquals(List.of("no_claims"), v.codes());
    }

    @Test
    void 没有事实段是REJECT() {
        ReviewDraft.Validation v = ReviewDraft.validate(new ReviewDraft.Draft("s-1", List.of(
                claim(ReviewDraft.Section.GAP, "缺撤退路线", "block:dc-turn-3"))), LOOKUP);
        assertEquals(ReviewDraft.Status.REJECT, v.status());
        assertTrue(v.codes().contains("no_fact"));
    }

    @Test
    void 断言没有证据是REJECT() {
        ReviewDraft.Validation v = ReviewDraft.validate(new ReviewDraft.Draft("s-1", List.of(
                claim(ReviewDraft.Section.FACT, "被僵尸打死"))), LOOKUP);
        assertEquals(ReviewDraft.Status.REJECT, v.status());
        assertTrue(v.codes().contains("claim_without_evidence"));
    }

    @Test
    void 决策点不引用决策上下文是REJECT() {
        ReviewDraft.Validation v = ReviewDraft.validate(new ReviewDraft.Draft("s-1", List.of(
                claim(ReviewDraft.Section.FACT, "被僵尸打死", "block:dc-turn-3"),
                claim(ReviewDraft.Section.DECISION_POINT, "应该早点回家", "block:summary-s-1"))), LOOKUP);

        assertEquals(ReviewDraft.Status.REJECT, v.status(), "样本摘要证明不了「执行者当时收到了什么」");
        assertTrue(v.codes().contains("decision_point_without_context"));
    }

    @Test
    void 引用不存在的块是PASS_WITH_UNRESOLVED不是PASS() {
        ReviewDraft.Validation v = ReviewDraft.validate(new ReviewDraft.Draft("s-1", List.of(
                claim(ReviewDraft.Section.FACT, "被僵尸打死", "block:dc-turn-3"),
                claim(ReviewDraft.Section.UPSTREAM, "洞口没有照明", "block:no-such"))), LOOKUP);

        assertEquals(ReviewDraft.Status.PASS_WITH_UNRESOLVED, v.status(), "核不了就是核不了，不许折成通过");
        assertTrue(v.unresolved().contains("block:no-such"));
        assertTrue(v.accepted());
    }

    @Test
    void 引用原始事件是PASS_WITH_UNRESOLVED() {
        ReviewDraft.Validation v = ReviewDraft.validate(new ReviewDraft.Draft("s-1", List.of(
                claim(ReviewDraft.Section.FACT, "被僵尸打死", "block:dc-turn-3"),
                claim(ReviewDraft.Section.CONTRIBUTING, "死亡前一秒在挖矿", "event:dead-123"))), LOOKUP);

        assertEquals(ReviewDraft.Status.PASS_WITH_UNRESOLVED, v.status());
        assertTrue(v.unresolved().contains("event:dead-123"));
    }

    @Test
    void 空正文的断言是REJECT() {
        ReviewDraft.Validation v = ReviewDraft.validate(new ReviewDraft.Draft("s-1", List.of(
                claim(ReviewDraft.Section.FACT, "被僵尸打死", "block:dc-turn-3"),
                claim(ReviewDraft.Section.GAP, "   ", "block:dc-turn-3"))), LOOKUP);
        assertEquals(ReviewDraft.Status.REJECT, v.status());
        assertTrue(v.codes().contains("empty_claim"));
    }

    @Test
    void 草稿没绑样本是REJECT() {
        ReviewDraft.Validation v = ReviewDraft.validate(new ReviewDraft.Draft("", List.of(
                claim(ReviewDraft.Section.FACT, "x", "block:dc-turn-3"))), LOOKUP);
        assertEquals(ReviewDraft.Status.REJECT, v.status());
        assertEquals(List.of("no_sample"), v.codes());
        assertFalse(v.accepted());
    }

    @Test
    void 查不到块时不崩只是未核实() {
        ReviewDraft.Validation v = ReviewDraft.validate(new ReviewDraft.Draft("s-1", List.of(
                claim(ReviewDraft.Section.FACT, "x", "block:dc-turn-3"))), null);
        assertEquals(ReviewDraft.Status.PASS_WITH_UNRESOLVED, v.status());
    }
}
