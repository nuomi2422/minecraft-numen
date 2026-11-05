package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 2026-11-05：内容去重的签名必须**与 key 顺序无关**。
 *
 * <p>实机翻车现场：第一版用 LinkedHashMap 保序，内容一样但 map 构建顺序不同 → 签名不同 →
 * asset_snapshot 仍然每 30 秒一条。这里钉住"顺序无关 + 易变字段被剥掉"。
 */
class RddMonitorSignatureTest {

    @Test
    void keyOrderDoesNotMatter() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("companionId", "c1");
        Map<String, Object> innerA = new LinkedHashMap<>();
        innerA.put("x", 1);
        innerA.put("y", 2);
        a.put("observed", innerA);

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("observed", Map.of("y", 2, "x", 1));
        b.put("companionId", "c1");

        assertEquals(RddMonitor.signature(a), RddMonitor.signature(b), "key 顺序不该影响签名");
    }

    @Test
    void volatileFieldsAreStripped() {
        Map<String, Object> a = Map.of("assets", List.of(
                Map.of("assetId", "a1",
                        "observation", Map.of("observationId", "obs-1", "observedAt", 100L, "value", Map.of("n", 1)))));
        Map<String, Object> b = Map.of("assets", List.of(
                Map.of("assetId", "a1",
                        "observation", Map.of("observationId", "obs-2", "observedAt", 200L, "value", Map.of("n", 1)))));
        assertEquals(RddMonitor.signature(a), RddMonitor.signature(b),
                "observationId/observedAt 每拍都变，不能进签名");
    }

    @Test
    void realChangeStillChangesSignature() {
        Map<String, Object> a = Map.of("assets", List.of(Map.of("assetId", "a1", "status", "ok")));
        Map<String, Object> b = Map.of("assets", List.of(Map.of("assetId", "a1", "status", "gone")));
        assertNotEquals(RddMonitor.signature(a), RddMonitor.signature(b), "内容真变了必须不同");
    }
}
