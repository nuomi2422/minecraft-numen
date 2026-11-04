package com.dwinovo.numen.worldstate.core.changes.v2;

import java.util.Set;

/** 变化日志接口（v2）：只追加、游标单调、断档显式 reset。 */
public interface ChangeFeed {

    /**
     * 追加一条变化，返回日志分配的序号。
     *
     * <p>调用方**不要**自己发号：序号与"内容已写表"必须同处一个临界区（041 §18.5）。
     * 存储侧在持锁状态下调用本方法，因此序号分配天然落在同一临界区里。
     */
    long append(String topic, String key, ChangeEvent.Kind kind, long observationRevision, long epochMs);

    /**
     * @param cursor 调用方已处理到的 streamSeq（0=从头）
     * @param epoch  调用方上次看到的 streamEpoch；与当前不一致 → resetRequired
     * @param topics 主题过滤（null/空=不过滤）
     * @param limit  最多返回条数
     */
    ChangesPage query(long cursor, String epoch, Set<String> topics, int limit);

    long watermark();

    String streamEpoch();

    int size();
}
