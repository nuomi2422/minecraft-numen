package com.dwinovo.numen.api.observation;

import java.util.List;

/**
 * 增量读请求（041 §24.3 / 042 R3）：**版本化**的 changes 入口。
 *
 * <p>为什么要有它：v1 的 `changes(after,...)` 语义是"观测 revision"，v2 是"日志 streamSeq + epoch"，
 * 两者不能靠同一个方法偷偷换语义（那就是 042 说的"悄悄改枚举语义"）。
 *
 * <p><b>after 与 epoch 必须成对</b>：schemaVersion=2 时 epoch 缺失 → 服务端直接返回 resetRequired，
 * 绝不默认"当前 epoch"。理由：重启后序号会重发，缺 epoch 的游标不可信（Readiness 那边刚踩过同一个坑）。
 *
 * @param schemaVersion 1 = 老语义（走旧的 changes 方法）；2 = streamSeq/epoch 语义
 * @param after         v2：上次读到的 streamSeq；v1：上次读到的 revision
 * @param epoch         v2 必填：消费者上次看到的 streamEpoch
 * @param topics        主题过滤（空=不过滤）
 * @param limit         最多返回条数（0=宿主默认）
 */
public record ChangesQuery(int schemaVersion, long after, String epoch,
                           List<String> topics, int limit) {

    public static final int SCHEMA_V1 = 1;
    public static final int SCHEMA_V2 = 2;

    public ChangesQuery {
        topics = topics == null ? List.of() : List.copyOf(topics);
        epoch = epoch == null ? "" : epoch.trim();
        if (schemaVersion <= 0) schemaVersion = SCHEMA_V1;
        if (limit < 0) limit = 0;
        if (after < 0) after = 0;
    }

    /** 便捷构造：v2 请求（epoch 必填，缺失就交给服务端判 reset）。 */
    public static ChangesQuery v2(long after, String epoch, List<String> topics, int limit) {
        return new ChangesQuery(SCHEMA_V2, after, epoch, topics, limit);
    }

    // 刻意**不提供 isV2()**：二分开关会把 schemaVersion=99 推进 else 分支 → 静默降级成 v1。
    // 分流必须显式三分：==SCHEMA_V1 / ==SCHEMA_V2 / 其余 ERROR（见 VersionedChangesMapper）。
}
