package com.dwinovo.numen.experience.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * LC3：五态样本与事件的编解码契约（键名是跨进程契约，这里钉死防漂移）。
 */
class UsageEventCodecTest {

    @Test
    void 全字段往返一致() {
        UsageSample s = new UsageSample("exp:execution|某条", 2, "", "task-9",
                "cv3", UsageState.INTERRUPTED, 1234567890L);
        Map<String, Object> ev = UsageEventCodec.toEvent(s);
        assertEquals("exp:execution|某条", ev.get("ref"));
        assertEquals("cv3", ev.get("version"));
        assertEquals("task-9", ev.get("artifact"));
        assertEquals("INTERRUPTED", ev.get("state"));
        assertEquals(2, ev.get("attempt"));
        assertEquals(1234567890L, ev.get("at"));
        UsageSample back = UsageEventCodec.fromEvent(ev);
        assertEquals(s.entryRef(), back.entryRef());
        assertEquals(s.version(), back.version());
        assertEquals(s.artifactId(), back.artifactId());
        assertEquals(s.state(), back.state());
        assertEquals(s.attempt(), back.attempt());
        assertEquals(s.atMillis(), back.atMillis());
    }

    @Test
    void null与空入参不出样本() {
        assertNull(UsageEventCodec.fromEvent(null));
        assertNull(UsageEventCodec.fromEvent(Map.of()));
        assertEquals(0, UsageEventCodec.fromEvents(null).size());
        assertEquals(0, UsageEventCodec.fromEvents(List.of(Map.of(), Map.of())).size(),
                "缺 ref 的行全部跳过");
    }

    @Test
    void 空串字段是合法事件_不误伤() {
        // LEGACY 样本（version 空）也是合法事件 —— 编解码不许把空串当坏行
        UsageSample legacy = new UsageSample("exp:x", 1, "", "", "",
                UsageState.UNKNOWN, 0L);
        Map<String, Object> ev = UsageEventCodec.toEvent(legacy);
        UsageSample back = UsageEventCodec.fromEvent(ev);
        assertEquals("", back.version());
        assertEquals("", back.artifactId());
        assertEquals(UsageState.UNKNOWN, back.state());
    }
}
