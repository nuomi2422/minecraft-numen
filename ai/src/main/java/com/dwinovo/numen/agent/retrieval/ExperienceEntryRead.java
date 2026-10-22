package com.dwinovo.numen.agent.retrieval;

import java.util.List;

/**
 * 一条「源条目」这样形态的记录 —— 让 `ExperienceRetrievalProvider` 可以脱离
 * recall/store 本身去算检索（背景：`ai` 模块不 import `experience-core`，见
 * {@link ExperienceEntrySource} 注释）。
 *
 * <p>来源侧 import 侧要给的一个 entry；它是纯 record 的**transition**，不是存货。
 */
public record ExperienceEntryRead(
        String entryRef,
        EntryKind kind,
        String title,
        String summary,
        List<String> applicableConditions,
        List<String> excludedConditions,
        List<String> evidenceRefs,
        Maturity maturity,
        int verifiedCount,
        boolean retracted,
        String bodyContent,
        String contentHash,
        String source,
        // G6a：是否已发布（只有 PUBLISHED 能进 EXECUTOR 视图）。ai 不认识 experience-core 的枚举，
        // 所以这里用布尔投影；映射由插件侧桥完成。
        boolean published,
        // G6a-2：是否可被任何视图检索（REVOKED=false）。published 分不出 DRAFT 与 REVOKED，
        // 这是独立的一维 —— 实机就是靠它才发现「撤回的还能被规划看到」。
        boolean retrievable,
        // ★ G6c：是否处于「试用」。G6c 之前 TRIAL 被 published=false 挡在执行视图外——
        //   于是试用条目**永远不会被用到**，也就永远攒不出试用证据（等于死状态）。
        //   现在 trial 独立成维：TRIAL 可进执行视图（带「试用中」标记），但**不等于已发布**。
        boolean trial) {

    /** 兼容 15 参构造（G6c 之前的所有调用点）：trial 缺省 false。 */
    public ExperienceEntryRead(String entryRef, EntryKind kind, String title, String summary,
                               List<String> applicableConditions, List<String> excludedConditions,
                               List<String> evidenceRefs, Maturity maturity, int verifiedCount,
                               boolean retracted, String bodyContent, String contentHash,
                               String source, boolean published, boolean retrievable) {
        this(entryRef, kind, title, summary, applicableConditions, excludedConditions, evidenceRefs,
                maturity, verifiedCount, retracted, bodyContent, contentHash, source,
                published, retrievable, false);
    }

    public ExperienceEntryRead {
        applicableConditions = frozen(applicableConditions);
        excludedConditions = frozen(excludedConditions);
        evidenceRefs = frozen(evidenceRefs);
    }

    private static List<String> frozen(List<String> v) {
        return v == null ? List.of() : List.copyOf(v);
    }
}
