package com.dwinovo.numen.plugins.learner.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证据引用：类型前缀决定它能不能当场核验。
 */
class EvidenceRefTest {

    @Test
    void 三种前缀各归各类() {
        assertEquals(EvidenceRef.Kind.BLOCK, EvidenceRef.parse("block:dc-turn-3").kind());
        assertEquals(EvidenceRef.Kind.EVENT, EvidenceRef.parse("event:dead-123").kind());
        assertEquals(EvidenceRef.Kind.CONTEXT, EvidenceRef.parse("context:dc-turn-3").kind());
    }

    @Test
    void 认不出的前缀是OTHER而不是BLOCK() {
        // 默认成 BLOCK 会让一条写错的引用看起来"能查到"
        assertEquals(EvidenceRef.Kind.OTHER, EvidenceRef.parse("whatever:xyz").kind());
        assertEquals(EvidenceRef.Kind.OTHER, EvidenceRef.parse("no-colon-at-all").kind());
        assertFalse(EvidenceRef.parse("whatever:xyz").verifiableOffline());
    }

    @Test
    void 大小写与前缀空白容忍() {
        assertEquals(EvidenceRef.Kind.BLOCK, EvidenceRef.parse("  BLOCK : dc-1 ").kind());
    }

    @Test
    void 空引用直接拒绝() {
        assertThrows(IllegalArgumentException.class, () -> EvidenceRef.parse(""));
        assertThrows(IllegalArgumentException.class, () -> EvidenceRef.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceRef(EvidenceRef.Kind.BLOCK, " "));
    }

    @Test
    void 序列化可还原() {
        EvidenceRef r = EvidenceRef.parse("block:dc-1");
        assertEquals("block:dc-1", r.toString());
        assertEquals(r.key(), EvidenceRef.parse(r.toString()).key());
    }

    @Test
    void 只有块与上下文能离线核验() {
        assertTrue(EvidenceRef.parse("block:x").verifiableOffline());
        assertTrue(EvidenceRef.parse("context:x").verifiableOffline());
        assertFalse(EvidenceRef.parse("event:x").verifiableOffline());
    }
}
