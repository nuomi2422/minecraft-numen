package com.dwinovo.numen.experience.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LC1（78 号 §7 判据）：晋级/归因门的四判定 —— 纯函数离线钉死。
 * OK / RETRACTED / NEVER_DELIVERED / CONTENT_DRIFT。
 */
class PromotionGateTest {

    private static final String REF = "exp:execution|某条经验";

    @Test
    void 当前版本完整交付_放行() {
        PromotionGate.Decision d = PromotionGate.check(REF, "v2", false, "v2", List.of("v1", "v2"));
        assertTrue(d.allowed());
        assertEquals("OK", d.code());
    }

    @Test
    void 撤回条目拒绝归因() {
        PromotionGate.Decision d = PromotionGate.check(REF, "v2", true, "v2", List.of("v2"));
        assertFalse(d.allowed());
        assertEquals("RETRACTED", d.code());
        assertTrue(d.message().contains("撤回"), "拒绝要给人话：" + d.message());
    }

    @Test
    void 从未交付_拒绝并给补证指引() {
        PromotionGate.Decision d = PromotionGate.check(REF, "v2", false, "", List.of());
        assertFalse(d.allowed());
        assertEquals("NEVER_DELIVERED", d.code());
        assertTrue(d.message().contains("experience_expand"),
                "拒绝必须说清怎么补证，不许只说不行：" + d.message());
    }

    @Test
    void 交付过旧版本_跨版本拒绝() {
        PromotionGate.Decision d = PromotionGate.check(REF, "v2", false, "v1", List.of("v1"));
        assertFalse(d.allowed());
        assertEquals("CONTENT_DRIFT", d.code());
        assertTrue(d.message().contains("旧版本"), "要说清「你看过的是旧版」：" + d.message());
        assertEquals(List.of("v1"), d.deliveredVersions(), "交付过的版本随判定带出供审计");
    }

    @Test
    void 版本拿不到时不猜_按从未交付处理() {
        // currentVersion 空串（极端：指纹算不出）+ 有交付：宁可保守拒绝，不放行
        PromotionGate.Decision d = PromotionGate.check(REF, "", false, "v1", List.of("v1"));
        assertFalse(d.allowed(), "版本对不上号时宁可拒绝，不许猜成匹配");
    }
}
