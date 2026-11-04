package com.dwinovo.numen.worldstate.core.changes.v2;

import java.util.Set;

/** 变化日志接口（v2）：只追加、游标单调、断档显式 reset。 */
public interface ChangeFeed {

    void append(ChangeEvent event);

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
