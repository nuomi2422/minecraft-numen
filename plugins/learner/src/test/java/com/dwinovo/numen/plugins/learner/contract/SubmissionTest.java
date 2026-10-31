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
 * 提交外壳：内外共用同一个口、无产物是合法结局、产物逐个独立判定。
 */
class SubmissionTest {

    private static final Function<String, Optional<EnvBlock>> LOOKUP = id -> switch (id) {
        case "dc-turn-3" -> Optional.of(TestBlocks.decisionContext("dc-turn-3"));
        case "summary-s-1" -> Optional.of(TestBlocks.summary("summary-s-1"));
        default -> Optional.empty();
    };

    private static ReviewDraft.Draft review(String... refs) {
        List<EvidenceRef> ev = java.util.Arrays.stream(refs).map(EvidenceRef::parse).toList();
        return new ReviewDraft.Draft("s-1", List.of(
                new ReviewDraft.Claim(ReviewDraft.Section.FACT, "被僵尸打死", ev),
                new ReviewDraft.Claim(ReviewDraft.Section.DIRECT_CAUSE, "血量不足", ev)));
    }

    private static ArtifactDraft artifact() {
        return new ArtifactDraft(ArtifactKind.EXPERIENCE, "夜间别出门", "夜里在洞口停留会被围",
                "正文", List.of("夜间"), List.of(), List.of(), "",
                List.of(EvidenceRef.parse("block:dc-turn-3")), List.of("s-1"), "EXECUTOR");
    }

    @Test
    void 内外两种模式共用同一套校验() {
        Submission.Draft internal = new Submission.Draft(Submission.Mode.INTERNAL, "s-1",
                review("block:dc-turn-3"), List.of(artifact()));
        Submission.Draft external = new Submission.Draft(Submission.Mode.EXTERNAL, "s-1",
                review("block:dc-turn-3"), List.of(artifact()));

        assertEquals(Submission.validate(internal, LOOKUP).status(), Submission.validate(external, LOOKUP).status());
        assertTrue(Submission.validate(internal, LOOKUP).accepted());
    }

    @Test
    void 没有产物是合法结局() {
        Submission.Draft none = new Submission.Draft(Submission.Mode.INTERNAL, "s-1",
                review("block:dc-turn-3"), List.of());
        Submission.Result r = Submission.validate(none, LOOKUP);
        assertTrue(r.accepted(), "158 §3.2：同一样本可以不产出，别逼着造垃圾条目");
        assertEquals(ReviewDraft.Status.PASS, r.status());
    }

    @Test
    void 坏产物不连坐其它产物但整单不通过() {
        ArtifactDraft bad = new ArtifactDraft(ArtifactKind.AUTO_ACTION, "x", "p", "b",
                List.of(), List.of(), List.of(), "", List.of(), List.of(), "");
        Submission.Draft d = new Submission.Draft(Submission.Mode.INTERNAL, "s-1",
                review("block:dc-turn-3"), List.of(artifact(), bad));

        Submission.Result r = Submission.validate(d, LOOKUP);
        assertFalse(r.accepted());
        assertTrue(r.codes().contains("artifact#1:missing_field:preconditions"), "问题要能定位到第几个产物");
    }

    @Test
    void 复盘不合格整单不合格() {
        Submission.Draft d = new Submission.Draft(Submission.Mode.INTERNAL, "s-1",
                new ReviewDraft.Draft("s-1", List.of()), List.of(artifact()));
        Submission.Result r = Submission.validate(d, LOOKUP);
        assertFalse(r.accepted());
        assertTrue(r.codes().contains("no_claims"));
    }

    @Test
    void 核不了的证据让整单停在PASS_WITH_UNRESOLVED() {
        Submission.Draft d = new Submission.Draft(Submission.Mode.EXTERNAL, "s-1",
                review("block:dc-turn-3", "event:dead-1"), List.of(artifact()));
        Submission.Result r = Submission.validate(d, LOOKUP);

        assertTrue(r.accepted());
        assertEquals(ReviewDraft.Status.PASS_WITH_UNRESOLVED, r.status());
        assertTrue(r.unresolved().contains("event:dead-1"));
    }

    @Test
    void 没绑样本直接拒() {
        Submission.Draft d = new Submission.Draft(Submission.Mode.INTERNAL, "",
                review("block:dc-turn-3"), List.of());
        assertEquals(List.of("no_sample"), Submission.validate(d, LOOKUP).codes());
        assertFalse(Submission.validate(null, LOOKUP).accepted());
    }
}
