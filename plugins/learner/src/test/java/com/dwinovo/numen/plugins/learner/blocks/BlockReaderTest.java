package com.dwinovo.numen.plugins.learner.blocks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 环境块读取原语（159 D）+ 读取账本（158 §4 末段）。
 *
 * <p>钉死四件事：目录不带正文；失败也记账；翻页有边界且锚点缺失留痕；
 * <b>没展开 = 没读</b>（会话结束时机械补记跳过）。
 */
class BlockReaderTest {

    private static EnvBlock block(BlockKind kind, String id, String body) {
        return new EnvBlock(kind, id, 1_700_000_000_000L, "context.jsonl",
                EnvBlock.Coverage.COMPLETE, EnvBlock.Freshness.CURRENT, EnvBlock.Confidence.MEDIUM,
                kind.zh() + "-" + id, body, false);
    }

    private static List<EnvBlock> three() {
        return List.of(
                block(BlockKind.LOCATION, "b-loc", "pos=0,64,0"),
                block(BlockKind.BODY_STATE, "b-body", "hp=3"),
                block(BlockKind.DECISION_CONTEXT, "b-dc", "<runtime_state/>"));
    }

    @Test
    void 打开即记账() {
        ReadLedger l = new ReadLedger();
        BlockReader r = BlockReader.open("s-1", three(), l, 100L);
        assertEquals(1, l.countOf(ReadLedger.Action.SAMPLE_OPEN));
        assertEquals("s-1", r.sampleId());
        assertEquals(3, r.blockCount());
    }

    @Test
    void 目录只给摘要不给正文() {
        ReadLedger l = new ReadLedger();
        BlockReader r = BlockReader.open("s-1", three(), l, 100L);
        List<EnvBlock> dir = r.list(101L);

        assertEquals(3, dir.size());
        for (EnvBlock b : dir) {
            assertNull(b.body(), "目录里带正文会把每次复盘都变成整份环境塞进上下文");
        }
        assertEquals(1, l.countOf(ReadLedger.Action.BLOCK_LIST));
    }

    @Test
    void 展开给正文并记账() {
        ReadLedger l = new ReadLedger();
        BlockReader r = BlockReader.open("s-1", three(), l, 100L);
        Optional<EnvBlock> b = r.expand("b-body", 102L);

        assertTrue(b.isPresent());
        assertEquals("hp=3", b.get().body());
        assertEquals(1, l.countOf(ReadLedger.Action.BLOCK_EXPAND));
        assertEquals(List.of("b-body"), r.expandedIds());
    }

    @Test
    void 展开不存在的块要记失败而不是静默返回空() {
        ReadLedger l = new ReadLedger();
        BlockReader r = BlockReader.open("s-1", three(), l, 100L);

        assertTrue(r.expand("b-nope", 102L).isEmpty());
        assertEquals(1, l.countOf(ReadLedger.Action.BLOCK_EXPAND_FAILED), "「想看看不到」必须留痕");
        assertEquals(0, l.countOf(ReadLedger.Action.BLOCK_EXPAND));
    }

    @Test
    void 翻页有边界且到端点返回空() {
        ReadLedger l = new ReadLedger();
        BlockReader r = BlockReader.open("s-1", three(), l, 100L);

        assertEquals("b-body", r.timelineNext("b-loc", 1L).orElseThrow());
        assertEquals("b-dc", r.timelineNext("b-body", 2L).orElseThrow());
        assertTrue(r.timelineNext("b-dc", 3L).isEmpty(), "到底了就是空，不许回绕");
        assertEquals("b-loc", r.timelinePrevious("b-body", 4L).orElseThrow());
        assertTrue(r.timelinePrevious("b-loc", 5L).isEmpty());
        assertEquals(3, l.countOf(ReadLedger.Action.TIMELINE_NEXT));
        assertEquals(2, l.countOf(ReadLedger.Action.TIMELINE_PREVIOUS));
    }

    @Test
    void 锚点不存在也留痕() {
        ReadLedger l = new ReadLedger();
        BlockReader r = BlockReader.open("s-1", three(), l, 100L);
        assertTrue(r.timelineNext("nope", 1L).isEmpty());
        assertEquals("anchor_missing", l.entriesOf(ReadLedger.Action.TIMELINE_NEXT).get(0).note());
    }

    @Test
    void 没展开的块在结束时机械补记跳过() {
        ReadLedger l = new ReadLedger();
        BlockReader r = BlockReader.open("s-1", three(), l, 100L);
        r.list(1L);
        r.expand("b-body", 2L);
        r.endSession(3L);

        List<ReadLedger.Entry> skips = l.entriesOf(ReadLedger.Action.BLOCK_SKIP);
        assertEquals(2, skips.size(), "三块里展开了一块，另外两块必须补记跳过");
        assertTrue(skips.stream().noneMatch(e -> e.blockId().equals("b-body")), "展开过的不许记跳过");
    }

    @Test
    void 重复结束不会重复补记() {
        ReadLedger l = new ReadLedger();
        BlockReader r = BlockReader.open("s-1", three(), l, 100L);
        r.endSession(2L);
        r.endSession(3L);
        assertEquals(3, l.countOf(ReadLedger.Action.BLOCK_SKIP));
    }

    @Test
    void 会话结束后不许再读() {
        ReadLedger l = new ReadLedger();
        BlockReader r = BlockReader.open("s-1", three(), l, 100L);
        r.endSession(2L);
        assertThrows(IllegalStateException.class, () -> r.list(3L));
        assertThrows(IllegalStateException.class, () -> r.expand("b-loc", 3L));
    }

    @Test
    void 重复blockId直接拒绝() {
        List<EnvBlock> dup = List.of(block(BlockKind.LOCATION, "same", "a"),
                block(BlockKind.BODY_STATE, "same", "b"));
        assertThrows(IllegalArgumentException.class,
                () -> BlockReader.open("s-1", dup, new ReadLedger(), 1L));
    }

    @Test
    void 未知元数据渲染成UNKNOWN而不是0() {
        EnvBlock b = new EnvBlock(BlockKind.INVENTORY, "b-inv", 0L, "", null, null, null,
                "背包 0 件？", "body", false);
        String line = b.renderLine();
        assertTrue(line.contains("coverage=UNKNOWN"));
        assertTrue(line.contains("freshness=UNKNOWN"));
        assertTrue(line.contains("confidence=UNKNOWN"));
        assertTrue(line.contains("capturedAt=UNKNOWN"));
        assertTrue(line.contains("source=UNKNOWN"));
        assertFalse(line.contains("=0 "), "UNKNOWN 不许写成 ZERO（158 §4）");
    }
}
