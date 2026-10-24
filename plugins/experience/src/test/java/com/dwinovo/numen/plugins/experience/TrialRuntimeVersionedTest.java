package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.experience.api.UsageStats;
import com.dwinovo.numen.experience.api.UsageState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LC1（78 号 §3）：呈现→归因样本带真实正文版本指纹。
 * 钉死：①带版本呈现的样本 version 非空；②旧式 notePresented 兼容（空版本 = LEGACY）；
 * ③UsageLedger 按 version 分开统计不混算。
 */
class TrialRuntimeVersionedTest {

    private static final UUID C = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @org.junit.jupiter.api.BeforeEach
    void clean() {
        TrialRuntime.resetForTest();
    }

    @Test
    void 带版本呈现_五态样本携带指纹() {
        String ref = "exp:execution|带版本样本";
        TrialRuntime.noteVersionedPresented(C, List.of(
                new TrialRuntime.VersionedPresentation(ref, "cv1", 1_000L)), 1_000L);
        TrialRuntime.onTaskFinished(C, UsageState.SUCCESS, "task-1", 2_000L);

        UsageStats st = TrialRuntime.usageStats(ref);
        assertEquals(1, st.success());
        // derive(ref) 便利版按 version 过滤：cv1 样本应完整可见
        UsageStats byVersion = com.dwinovo.numen.experience.api.UsageLedger.derive(
                ref, "cv1", samplesOf(ref));
        assertEquals(1, byVersion.success(), "带版本的样本要能按版本派生账");
    }

    @Test
    void 旧式呈现_样本版本为空_LEGACY口径() {
        String ref = "exp:execution|旧式样本";
        TrialRuntime.notePresented(C, List.of(ref), 1_000L);
        TrialRuntime.onTaskFinished(C, UsageState.SUCCESS, "task-2", 2_000L);

        UsageStats legacy = com.dwinovo.numen.experience.api.UsageLedger.derive(
                ref, "", samplesOf(ref));
        assertEquals(1, legacy.success(), "旧路径样本 version 如实为空串，不猜");
        UsageStats v1 = com.dwinovo.numen.experience.api.UsageLedger.derive(
                ref, "cv1", samplesOf(ref));
        assertEquals(0, v1.success(), "LEGACY 样本不算进任何真实版本的账");
    }

    @Test
    void 跨版本样本分开统计_不混算() {
        String ref = "exp:execution|跨版本";
        // v1 一次成功 + v2 一次失败：两个版本各自的账不许互相掺
        TrialRuntime.noteVersionedPresented(C, List.of(
                new TrialRuntime.VersionedPresentation(ref, "cv1", 1_000L)), 1_000L);
        TrialRuntime.onTaskFinished(C, UsageState.SUCCESS, "task-a", 2_000L);
        TrialRuntime.noteVersionedPresented(C, List.of(
                new TrialRuntime.VersionedPresentation(ref, "cv2", 10_000L)), 10_000L);
        TrialRuntime.onTaskFinished(C, UsageState.FAILURE, "task-b", 20_000L);

        UsageStats v1 = com.dwinovo.numen.experience.api.UsageLedger.derive(
                ref, "cv1", samplesOf(ref));
        UsageStats v2 = com.dwinovo.numen.experience.api.UsageLedger.derive(
                ref, "cv2", samplesOf(ref));
        assertEquals(1, v1.success(), "cv1 的成功");
        assertEquals(0, v1.failure(), "cv2 的失败不许混进 cv1 的账");
        assertEquals(1, v2.failure(), "cv2 的失败");
        assertEquals(0, v2.success(), "cv1 的成功不许混进 cv2 的账");
    }

    @Test
    void resetForTest清三账() {
        String ref = "exp:execution|重置卫生";
        TrialRuntime.notePresented(C, List.of(ref), 1_000L);
        TrialRuntime.onTaskFinished(C, UsageState.SUCCESS, "t", 2_000L);
        assertTrue(TrialRuntime.usageStats(ref).success() > 0, "前置：五态账有记录");
        TrialRuntime.resetForTest();
        assertEquals(0, TrialRuntime.usageStats(ref).success(),
                "USAGE 也要清 —— 旧 reset 只清 PENDING/USES，五态账漏了（测试卫生洞）");
    }

    /** 从 USAGE 拿样本的测试后门：直接借 derive 的 samples 参数路径，这里从 usageStats 反推不可行，
     *  用包内可见的静态字段最直接。 */
    private static List<com.dwinovo.numen.experience.api.UsageSample> samplesOf(String ref) {
        return TrialRuntime.samplesForTest(ref);
    }
}
