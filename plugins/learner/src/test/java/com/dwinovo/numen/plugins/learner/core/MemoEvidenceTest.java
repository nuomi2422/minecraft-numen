package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提交侧证据字段的归一与显示（2026-11-06，监督者实测后补）。
 *
 * <p>守三件事：
 * <ol>
 *   <li>老队列文件没有 evidence 键 ⇒ 归一成空表，不炸；</li>
 *   <li>event: 前缀、重复、超长、超条数都被归一，形状不对的不进库；</li>
 *   <li>复盘提示里能看到证据 id —— 它是契约取证的首选来源，不印等于让它猜。</li>
 * </ol>
 */
class MemoEvidenceTest {

    @Test
    void legacyMemoWithoutEvidenceStaysEmpty() {
        Memo m = new Memo("m-1", "p", "s", "t", "snap", 1L);
        assertEquals(List.of(), m.evidence());
    }

    @Test
    void stripsEventPrefixAndDedupes() {
        Memo m = new Memo("m-2", "p", "s", "t", "snap", 1L, "learning",
                List.of("event:tools-1-0", " tools-1-0 ", "tools-2-0"));
        assertEquals(List.of("tools-1-0", "tools-2-0"), m.evidence());
    }

    @Test
    void dropsBlankAndOverlongAndCapsAtEight() {
        List<String> raw = new java.util.ArrayList<>();
        raw.add("   ");
        raw.add("x".repeat(81));
        for (int i = 0; i < 12; i++) {
            raw.add("tools-" + i + "-0");
        }
        Memo m = new Memo("m-3", "p", "s", "t", "snap", 1L, "learning", raw);
        assertEquals(8, m.evidence().size());
        assertFalse(m.evidence().contains("x".repeat(81)));
    }

    @Test
    void promptBlockShowsEvidenceWhenPresent() {
        Memo with = new Memo("m-4", "p", "s", "t", "snap", 1L, "learning",
                List.of("tools-9-0", "learner-9-0"));
        String block = with.toPromptBlock();
        assertTrue(block.contains("证据: tools-9-0, learner-9-0"), block);

        Memo without = new Memo("m-5", "p", "s", "t", "snap", 1L);
        assertFalse(without.toPromptBlock().contains("证据:"));
    }
}
