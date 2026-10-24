package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.experience.api.UsageEventCodec;
import com.dwinovo.numen.experience.api.UsageSample;
import com.dwinovo.numen.experience.api.UsageLedger;
import com.dwinovo.numen.experience.api.UsageState;
import com.dwinovo.numen.experience.api.UsageStats;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LC3（78 号 §7 判据）：日常样本能复算 —— 事件是唯一真账，内存只是缓存。
 * 钉死：TrialRuntime 记的每条五态样本都会镜像成 usage_recorded 事件（注入收集器），
 * 事件经 UsageEventCodec 解回样本后，与内存账<b>逐字段一致</b>，
 * 两边各自 derive 出的五态账相同 —— 重算不是承诺，是过测试的事实。
 */
class UsageRecomputeTest {

    private static final UUID C = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @org.junit.jupiter.api.BeforeEach
    void clean() {
        TrialRuntime.resetForTest();
    }

    @Test
    void 内存账与事件重算逐字段一致() {
        List<Map<String, Object>> events = new ArrayList<>();
        TrialRuntime.eventSink((type, data) -> {
            if ("usage_recorded".equals(type)) {
                events.add(data);
            }
        });

        // 三个版本、三种终态、含一次取消 —— 覆盖五态账的真实形态
        String ref = "exp:execution|复算样本";
        TrialRuntime.noteVersionedPresented(C, List.of(
                new TrialRuntime.VersionedPresentation(ref, "cv1", 1_000L)), 1_000L);
        TrialRuntime.onTaskFinished(C, UsageState.SUCCESS, "task-1", 2_000L);
        TrialRuntime.noteVersionedPresented(C, List.of(
                new TrialRuntime.VersionedPresentation(ref, "cv2", 10_000L)), 10_000L);
        TrialRuntime.onTaskFinished(C, UsageState.FAILURE, "task-2", 20_000L);
        TrialRuntime.noteVersionedPresented(C, List.of(
                new TrialRuntime.VersionedPresentation(ref, "cv2", 30_000L)), 30_000L);
        TrialRuntime.onTaskFinished(C, UsageState.CANCELED, "task-3", 40_000L);

        // ① 内存账：3 条样本（SUCCESS/FAILURE/CANCELED 各一）
        List<UsageSample> inMemory = TrialRuntime.samplesForTest(ref);
        assertEquals(3, inMemory.size(), "三次收尾各记一条");
        // ② 事件面：每条样本都镜像了（无丢失）
        assertEquals(3, events.size(), "事件数必须等于样本数——丢一条事件重算就缺角");
        // ③ 逐字段一致
        List<UsageSample> replayed = UsageEventCodec.fromEvents(events);
        assertEquals(inMemory.size(), replayed.size());
        for (int i = 0; i < inMemory.size(); i++) {
            UsageSample a = inMemory.get(i);
            UsageSample b = replayed.get(i);
            assertEquals(a.entryRef(), b.entryRef(), "ref 第" + i + "条不一致");
            assertEquals(a.version(), b.version(), "version 第" + i + "条不一致");
            assertEquals(a.artifactId(), b.artifactId(), "artifact 第" + i + "条不一致");
            assertEquals(a.state(), b.state(), "state 第" + i + "条不一致");
            assertEquals(a.attempt(), b.attempt(), "attempt 第" + i + "条不一致");
            assertEquals(a.atMillis(), b.atMillis(), "at 第" + i + "条不一致");
        }
        // ④ 两边各自 derive 的五态账一致（重算真正可用）
        UsageStats memStats = UsageLedger.derive(ref, "cv2", TrialRuntime.samplesForTest(ref));
        UsageStats evStats = UsageLedger.derive(ref, "cv2", replayed);
        assertEquals(memStats.success(), evStats.success());
        assertEquals(memStats.failure(), evStats.failure());
        assertEquals(memStats.canceled(), evStats.canceled());
        assertEquals(memStats.unknown(), evStats.unknown());
        assertEquals(1, evStats.failure(), "cv2 一次 FAILURE + 一次 CANCELED：失败单列");
        assertEquals(1, evStats.canceled(), "取消单列，不并进失败");
        // ⑤ 清掉出口，后续测试不受污染
        TrialRuntime.eventSink(null);
    }

    @Test
    void 坏行容错_缺ref跳过_坏state落UNKNOWN() {
        List<Map<String, Object>> raw = new ArrayList<>();
        raw.add(Map.of("ref", "exp:a", "version", "v1", "artifact", "t1",
                "state", "SUCCESS", "attempt", 1, "at", 100L));
        raw.add(Map.of());   // 缺 ref → 跳过
        raw.add(Map.of("ref", "exp:b", "state", "TOTALLY_UNKNOWN_STATE", "at", 200L));  // 坏 state → UNKNOWN
        raw.add(Map.of("ref", "exp:c", "state", "CANCELED", "attempt", "not-a-number", "at", "300"));  // 坏 attempt → 默认 1，at 字符串能读

        List<UsageSample> parsed = UsageEventCodec.fromEvents(raw);
        assertEquals(3, parsed.size(), "缺 ref 的那行跳过，其余三条保留");
        assertEquals(UsageState.SUCCESS, parsed.get(0).state());
        assertEquals(UsageState.UNKNOWN, parsed.get(1).state(), "坏 state 落 UNKNOWN 不猜");
        assertEquals(1, parsed.get(1).attempt(), "缺 attempt 默认 1");
        assertEquals(UsageState.CANCELED, parsed.get(2).state());
        assertEquals(1, parsed.get(2).attempt(), "坏 attempt 默认 1（不抛、不猜成别的数）");
        assertEquals(300L, parsed.get(2).atMillis(), "at 的字符串数字要能读");
        assertEquals("", parsed.get(1).version(), "缺 version 如实空串（LEGACY 口径）");
        assertEquals("", parsed.get(1).artifactId());
    }

    @Test
    void 出口失败不影响记账() {
        // sink 抛异常：内存账必须照常 —— 事件是镜像，不是记账的前提
        TrialRuntime.eventSink((type, data) -> { throw new RuntimeException("monitor down"); });
        String ref = "exp:execution|出口失败样本";
        TrialRuntime.noteVersionedPresented(C, List.of(
                new TrialRuntime.VersionedPresentation(ref, "cv1", 1_000L)), 1_000L);
        List<com.dwinovo.numen.experience.api.TrialUse> uses =
                TrialRuntime.onTaskFinished(C, UsageState.SUCCESS, "t-x", 2_000L);
        assertEquals(1, uses.size(), "归因照常完成");
        assertEquals(1, TrialRuntime.usageStats(ref).success(), "五态账照常入账");
        TrialRuntime.eventSink(null);
    }
}
