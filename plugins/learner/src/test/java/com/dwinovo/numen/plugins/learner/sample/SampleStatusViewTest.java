package com.dwinovo.numen.plugins.learner.sample;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面板渲染：不许把「没启用」画成「0 条」，不许把「没读到」画成 0。
 *
 * <p>这是最容易撒谎的一段代码，所以每条都钉死。
 */
class SampleStatusViewTest {

    private static Map<String, Object> stats(boolean enabled, int samples, int occurrences) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("gateReason", enabled ? "enabled_file" : "no_file");
        m.put("samples", samples);
        m.put("occurrences", occurrences);
        m.put("admitted", samples);
        m.put("merged", 0);
        m.put("skipped", 0);
        m.put("failed", 0);
        m.put("errors", 0);
        m.put("lastError", "");
        return m;
    }

    @Test
    void 关着的时候必须说明没生效() {
        Map<String, Object> r = SampleStatusView.render(stats(false, 0, 0));
        assertEquals(SampleStatusView.NOTE_DISABLED, r.get("note"));
        assertEquals(false, r.get("enabled"));
    }

    @Test
    void 开着也要说明还没有消费者() {
        Map<String, Object> r = SampleStatusView.render(stats(true, 3, 5));
        assertEquals(SampleStatusView.NOTE_ENABLED, r.get("note"));
        assertEquals(3, r.get("samples"));
    }

    @Test
    void 缺失的键不补0() {
        Map<String, Object> sparse = new LinkedHashMap<>();
        sparse.put("enabled", true);
        Map<String, Object> r = SampleStatusView.render(sparse);
        assertFalse(r.containsKey("samples"), "没读到 ≠ 0 条");
        assertFalse(r.containsKey("occurrences"));
        assertTrue(r.containsKey("note"));
    }

    @Test
    void 没读到运行时单独标记() {
        Map<String, Object> r = SampleStatusView.render(null);
        assertEquals(true, r.get("unreadable"));
        assertFalse(r.containsKey("samples"));
        assertEquals(SampleStatusView.NOTE_DISABLED, r.get("note"));
    }

    @Test
    void 白名单之外的键不透传() {
        Map<String, Object> raw = stats(true, 1, 1);
        raw.put("secret_param", "不应该出现在面板里");
        assertFalse(SampleStatusView.render(raw).containsKey("secret_param"));
    }

    @Test
    void 摘要里缺字段写问号而不是0() {
        Map<String, Object> sparse = new LinkedHashMap<>();
        sparse.put("enabled", false);
        assertEquals("samples=? occurrences=? enabled=false errors=?", SampleStatusView.summary(sparse));
        assertEquals("samples=2 occurrences=4 enabled=true errors=0", SampleStatusView.summary(stats(true, 2, 4)));
    }
}
