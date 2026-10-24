package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.experience.api.UsageLedger;
import com.dwinovo.numen.experience.api.UsageState;
import com.dwinovo.numen.experience.api.UsageStats;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LC6（78 号 §6/§7「恢复原版本后仍可查历史结果」）：正文 v1 → 改 v2（漂移重置）→ 改回 v1
 * —— v1 的历史验证/样本不许因中间出现过 v2 而消失。五态账按版本分账天然支持，
 * 这条钉子把「恢复」的语义显式钉死：改回 v1 时，指纹回到旧值，历史样本仍挂旧指纹下。
 */
class VersionRestoreHistoryTest {

    private static final UUID C = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @org.junit.jupiter.api.BeforeEach
    void clean() {
        TrialRuntime.resetForTest();
    }

    @Test
    void 改回旧版本后_旧版本的历史样本仍可查() {
        String ref = "exp:execution|版本恢复样本";
        String v1 = "c" + Integer.toHexString(("版本甲内容").hashCode());
        // v1 时期：一次成功
        TrialRuntime.noteVersionedPresented(C, List.of(
                new TrialRuntime.VersionedPresentation(ref, v1, 1_000L)), 1_000L);
        TrialRuntime.onTaskFinished(C, UsageState.SUCCESS, "task-v1", 2_000L);
        // v2 时期：一次失败（与 v1 无关的账）
        String v2 = "c" + Integer.toHexString(("版本乙内容").hashCode());
        TrialRuntime.noteVersionedPresented(C, List.of(
                new TrialRuntime.VersionedPresentation(ref, v2, 10_000L)), 10_000L);
        TrialRuntime.onTaskFinished(C, UsageState.FAILURE, "task-v2", 20_000L);

        // 「恢复原版本」：此后新样本会带 v1 指纹；查历史时 v1 的成功必须还在
        List<com.dwinovo.numen.experience.api.UsageSample> samples = TrialRuntime.samplesForTest(ref);
        UsageStats v1Stats = UsageLedger.derive(ref, v1, samples);
        UsageStats v2Stats = UsageLedger.derive(ref, v2, samples);

        assertEquals(1, v1Stats.success(), "v1 的历史成功必须仍可查——改走 v2 再改回，不许抹历史");
        assertEquals(0, v1Stats.failure(), "v2 的失败不许混进 v1 的账");
        assertEquals(1, v2Stats.failure(), "v2 的账独立保留");
        assertEquals(0, v2Stats.success(), "v1 的成功不许混进 v2 的账");
        // 结论维度也分开：v1 的结论是 SUCCESS，v2 是 FAILURE
        assertEquals(UsageState.SUCCESS, v1Stats.conclusion());
        assertEquals(UsageState.FAILURE, v2Stats.conclusion());
    }
}
