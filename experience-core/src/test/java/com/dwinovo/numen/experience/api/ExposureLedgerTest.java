package com.dwinovo.numen.experience.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LC1（78 号 §3.1）：正文交付账本 —— 晋级门只认这里。
 * 钉死：完整交付才算回执；截断不算；跨版本不算；AUTO 与 EXPANDED 都算但分开记；
 * 上限驱逐最旧；readout 如实。
 */
class ExposureLedgerTest {

    private static final UUID C = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String REF = "exp:execution|某条经验";

    @BeforeEach
    void clean() {
        ExposureLedger.resetForTest();
    }

    private static ExposureLedger.Delivery d(String ref, String version, String kind,
                                              boolean truncated, long at) {
        return new ExposureLedger.Delivery(ref, version, kind, "call-1", truncated, at);
    }

    @Test
    void 完整展开交付算回执() {
        ExposureLedger.record(C, d(REF, "v1", ExposureLedger.KIND_EXPANDED, false, 1000L));
        assertTrue(ExposureLedger.delivered(C, REF, "v1"), "非截断 EXPANDED = 正文完整到达");
        assertEquals("v1", ExposureLedger.latestDeliveredVersion(C, REF, 2000L));
    }

    @Test
    void 截断交付记账但不算完整回执() {
        ExposureLedger.record(C, d(REF, "v1", ExposureLedger.KIND_EXPANDED, true, 1000L));
        assertFalse(ExposureLedger.delivered(C, REF, "v1"), "半截正文可能缺关键步骤，不许过晋级门");
        assertEquals(1, ExposureLedger.deliveries(C, REF).size(), "但事实要留下（可审计）");
        assertEquals(1, ExposureLedger.readout(C).get("truncated_deliveries"));
    }

    @Test
    void 跨版本交付不认当前版本() {
        ExposureLedger.record(C, d(REF, "v1", ExposureLedger.KIND_EXPANDED, false, 1000L));
        assertFalse(ExposureLedger.delivered(C, REF, "v2"),
                "交付过旧版本 ≠ 当前版本收到过（78 号：跨版本引用拒绝归因）");
        assertEquals(List.of("v1"), ExposureLedger.deliveredVersions(C, REF),
                "但交付过的版本要能查出来（审计/给模型确切指引）");
    }

    @Test
    void 召回随带的正文也算交付但不伪装为主动展开() {
        ExposureLedger.record(C, d(REF, "v1", ExposureLedger.KIND_AUTO_EXPANDED, false, 1000L));
        assertTrue(ExposureLedger.delivered(C, REF, "v1"),
                "系统随召回交付的正文确实到了 —— 过门，但 kind 分开记供审计");
        assertEquals("AUTO_EXPANDED", ExposureLedger.deliveries(C, REF).get(0).kind());
    }

    @Test
    void latestDeliveredVersion取指定时刻前最近一次() {
        ExposureLedger.record(C, d(REF, "v1", ExposureLedger.KIND_EXPANDED, false, 1000L));
        ExposureLedger.record(C, d(REF, "v2", ExposureLedger.KIND_EXPANDED, false, 2000L));
        assertEquals("v1", ExposureLedger.latestDeliveredVersion(C, REF, 1500L), "只看 1500 前的");
        assertEquals("v2", ExposureLedger.latestDeliveredVersion(C, REF, 9999L), "取最近一次");
    }

    @Test
    void 同伴之间隔离() {
        UUID other = UUID.randomUUID();
        ExposureLedger.record(C, d(REF, "v1", ExposureLedger.KIND_EXPANDED, false, 1000L));
        assertFalse(ExposureLedger.delivered(other, REF, "v1"), "A 的交付不得到 B 的晋级门用");
    }

    @Test
    void 上限驱逐最旧_回执计数仍准() {
        for (int i = 0; i < ExposureLedger.MAX_PER_COMPANION + 10; i++) {
            ExposureLedger.record(C, d(REF + i, "v", ExposureLedger.KIND_EXPANDED, false, i));
        }
        assertEquals(ExposureLedger.MAX_PER_COMPANION,
                (Integer) ExposureLedger.readout(C).get("deliveries"),
                "总数封顶在 MAX_PER_COMPANION，不无限增长");
        // 最旧的 REF+0 已被驱逐：查不到
        assertFalse(ExposureLedger.delivered(C, REF + 0, "v"), "最旧的被驱逐");
        // 最新的还在
        assertTrue(ExposureLedger.delivered(C, REF + (ExposureLedger.MAX_PER_COMPANION + 9), "v"),
                "最新的必须还在");
    }

    @Test
    void DIRECTORY曝光只审计_不算回执() {
        // LC1（78 号 §3 exposureKind 三分类）：目录层曝光——记下来可对账，但不许过晋级门
        ExposureLedger.record(C, d(REF, "v1", ExposureLedger.KIND_DIRECTORY, false, 1000L));
        assertFalse(ExposureLedger.delivered(C, REF, "v1"),
                "目录只给标题与线索，正文没交付——渲染段可能被预算裁掉，不算「收到」");
        assertEquals(1, ExposureLedger.deliveries(C, REF).size(), "但曝光事实要留（可审计）");
        assertEquals(1, ExposureLedger.readout(C).get("directory_exposures"),
                "readout 单列目录曝光数");
        // 目录曝光后再真展开：回执通道不受影响
        ExposureLedger.record(C, d(REF, "v1", ExposureLedger.KIND_EXPANDED, false, 2000L));
        assertTrue(ExposureLedger.delivered(C, REF, "v1"), "真展开照常算回执");
        assertEquals(1, ExposureLedger.readout(C).get("full_receipts"));
    }

    @Test
    void 空ref不记() {
        ExposureLedger.record(C, d("", "v1", ExposureLedger.KIND_EXPANDED, false, 1000L));
        ExposureLedger.record(C, d(null, "v1", ExposureLedger.KIND_EXPANDED, false, 1000L));
        ExposureLedger.record(null, d(REF, "v1", ExposureLedger.KIND_EXPANDED, false, 1000L));
        assertEquals(0, ExposureLedger.readout(C).get("deliveries"));
    }

}
