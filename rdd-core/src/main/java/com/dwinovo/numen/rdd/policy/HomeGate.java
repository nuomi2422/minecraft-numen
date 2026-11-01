package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.List;

/**
 * 基地阶段对高风险动作的硬依赖（167 §B3/§B6）。
 *
 * <p>与 {@link RiskGate} 的分工：RiskGate 回答"身上带的够不够"，这里回答"家里有没有底"。
 * 两者都不许互相冒充——所以 {@code ESTABLISHED} 从来不是放行理由，反过来"物资齐了"也不代表家里有备用。
 *
 * <p>只收紧：不满足时往 missing 里追加一行并置 allowed=false；满足时**原样返回**原裁决。
 */
public final class HomeGate {

    private HomeGate() {}

    /** 高风险动作要求的最低基地阶段：至少有备用（{@link HomeStage#hasRecovery()}）。 */
    public static final HomeStage MIN_FOR_RISK = HomeStage.STOCKED;

    public static RiskGate.Verdict enforce(RiskGate.Verdict verdict, HomeStage stage) {
        if (verdict == null) return null;
        RiskLevel lv = verdict.level();
        boolean risky = lv == RiskLevel.NETHER || lv == RiskLevel.END;
        if (!risky) return verdict;
        HomeStage st = stage == null ? HomeStage.NO_HOME : stage;
        if (st.hasRecovery()) return verdict;   // 已经到 STOCKED/LIT/ESTABLISHED
        List<String> missing = new ArrayList<>(verdict.missing());
        missing.add("home_stage=" + st.name() + " need " + MIN_FOR_RISK.name()
                + "（基地没有备用：先建箱、备齐最低清单）");
        List<String> prep = new ArrayList<>(verdict.prepTasks());
        prep.add("先把基地做到 " + MIN_FOR_RISK.name() + "（备用箱命中 "
                + ReserveKit.MINIMUM.keySet().stream().sorted().toList() + "），再进"
                + lv.name().toLowerCase());
        return new RiskGate.Verdict(false, lv, List.copyOf(missing), List.copyOf(prep));
    }
}
