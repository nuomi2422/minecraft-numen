package com.dwinovo.numen.worldstate.core.changes.v2;

/**
 * v2 变化事件（031 §17 / 042 R3）。
 *
 * <p>关键点：{@code streamSeq} 是**日志自己的单调序号**，与观测修订号 {@code observationRevision}
 * 分开——观测 revision 是"每个 key 的版本"，不能当全局时间线用（v1 的错就在这里）。
 *
 * @param streamSeq           日志内唯一且单调递增
 * @param kind                CREATED / UPDATED / INVALIDATE（GONE 保留但暂不产生）
 * @param observationRevision 引用哪一版观测（诊断用，不参与游标比较）
 * @param epochMs             事件发生时间（毫秒）
 */
public record ChangeEvent(long streamSeq, String topic, String key, Kind kind,
                          long observationRevision, long epochMs) {

    public enum Kind {
        /** 该键第一次有观测。 */
        CREATED,
        /** 该键已有观测，本次写入被接受且更新。 */
        UPDATED,
        /** 缓存失效/淘汰：这条观测不再可信——**不是**"世界里没了"。 */
        INVALIDATE,
        /** 保留：表示世界层面的真实消失；当前没有产生它的 API。 */
        GONE
    }

    public ChangeEvent {
        topic = topic == null ? "" : topic;
        key = key == null ? "" : key;
        kind = kind == null ? Kind.UPDATED : kind;
    }
}
