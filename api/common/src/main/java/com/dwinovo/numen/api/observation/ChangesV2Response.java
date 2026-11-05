package com.dwinovo.numen.api.observation;

import java.util.List;

/**
 * 增量读结果（v2，041 §24.3）。
 *
 * <p>与 v1 {@link WorldObservationService.ChangesResponse} 的关键差别：
 * <ul>
 *   <li>事件带 {@code streamSeq}（日志自己的单调序号），与观测 revision 分开；</li>
 *   <li><b>{@code resetRequired=true} 表示"游标不可信"</b>（epoch 变化/淘汰/缺 epoch），
 *       消费方必须做一次有界重同步——**不能**把空列表当"没变化"；</li>
 *   <li>{@code watermark} 是当前日志尾部序号，用于"快照 + 增量"接缝；</li>
 *   <li>{@code hasMore} 表示还有未读事件。</li>
 * </ul>
 *
 * <p><b>陷阱</b>：`status=OK` **不代表可以当"没变化"用**——`resetRequired=true` 时游标不可信，
 * 必须做一次有界重同步。只看 status 的消费者会把重置当成"一切正常"，这是最安静的错。
 */
public record ChangesV2Response(WorldObservationService.Status status,
                                List<Event> events,
                                long nextCursor,
                                boolean resetRequired,
                                long watermark,
                                boolean hasMore,
                                String streamEpoch,
                                String reason) {

    /** 一条 v2 事件。 */
    public record Event(long streamSeq, String topic, String key, String kind,
                        long observationRevision, long epochMs) {
        public Event {
            topic = topic == null ? "" : topic;
            key = key == null ? "" : key;
            kind = kind == null ? "UPDATED" : kind;
        }
    }

    public ChangesV2Response {
        status = status == null ? WorldObservationService.Status.ERROR : status;
        events = events == null ? List.of() : List.copyOf(events);
        streamEpoch = streamEpoch == null ? "" : streamEpoch;
    }

    public static ChangesV2Response error(String reason) {
        return new ChangesV2Response(WorldObservationService.Status.ERROR, List.of(),
                0L, false, 0L, false, "", reason);
    }
}
