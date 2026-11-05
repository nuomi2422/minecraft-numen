package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-05 现场：签名长度相同、hash 每拍都变 → 集合顺序不同。签名必须与顺序无关。 */
class RddMonitorSignatureOrderTest {

    @Test
    void listOrderDoesNotChangeSignature() {
        var a = Map.of("assets", List.of(Map.of("id", "a1"), Map.of("id", "b2")));
        var b = Map.of("assets", List.of(Map.of("id", "b2"), Map.of("id", "a1")));
        assertEquals(RddMonitor.signature(a), RddMonitor.signature(b), "顺序不同但内容相同 → 签名必须相同");
    }

    @Test
    void nestedListOrderAlsoIgnored() {
        var a = Map.of("x", List.of(List.of("p", "q"), List.of("r")));
        var b = Map.of("x", List.of(List.of("r"), List.of("q", "p")));
        assertEquals(RddMonitor.signature(a), RddMonitor.signature(b));
    }
}
