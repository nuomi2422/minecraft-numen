package com.dwinovo.numen.worldstate.core.changes.v2;

import java.util.List;

/**
 * 一页变化（v2）。
 *
 * <p><b>resetRequired</b>：游标已不可用（日志清空/保留期淘汰/epoch 切换），
 * 调用方必须做一次有界重同步——**不能**把空列表当成"没有变化"。
 *
 * <p><b>nextCursor</b> 推进到"最后一条**检查过**的事件"，而不是最后一条命中的；
 * 否则 topic 过滤会把游标卡死（v1 的第二个错）。
 *
 * @param watermark 当前日志尾部序号（做「快照 + 增量」接缝时当水位用）
 * @param hasMore   是否还有未读事件
 */
public record ChangesPage(List<ChangeEvent> events, long nextCursor, boolean resetRequired,
                          long watermark, boolean hasMore, String streamEpoch) {

    public ChangesPage {
        events = events == null ? List.of() : List.copyOf(events);
        streamEpoch = streamEpoch == null ? "" : streamEpoch;
    }
}
