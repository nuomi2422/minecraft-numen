package com.dwinovo.numen.api.observation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一世界观测的**公开读口**（039 U1 第一步，2026-11-05）。
 *
 * <p>契约要点（来自 039 §1–§4，按最小可用裁剪）：
 * <ul>
 *   <li><b>不得抛异常</b>：读不到用 {@link Status#ERROR} + reason 表达；</li>
 *   <li><b>OK 不等于条件成立</b>：这里只回答"看到了什么"，不回答"任务该不该完成"；</li>
 *   <li><b>缺字段不填 0/false</b>：没观测到的字段进 {@link Item#unknownFields()}；</li>
 *   <li><b>局部覆盖不算全场清零</b>：`coverage` 为 PARTIAL 时不能据此证明"不存在"；</li>
 *   <li><b>consumer 只是审计标签</b>，不是授权凭证；权限由宿主上下文绑定。</li>
 * </ul>
 *
 * <p>实现由宿主（world-state 插件）提供，经 {@code NumenApi.capability(WorldObservationService.class, impl)} 暴露；
 * 调用方找不到实现时应显式降级，**不要**自己扫世界兜底（039 §6 明令禁止在采集失败时同步扫世界）。
 */
public interface WorldObservationService {

    /** 传输/采集状态。OK 只代表读到了，<b>不代表</b>任何业务条件成立。 */
    enum Status { OK, PARTIAL, PENDING, ERROR }

    /** 覆盖程度。{@link #PARTIAL} 时不能证明"不存在"。 */
    enum Coverage { FULL, PARTIAL, UNKNOWN }

    /**
     * 一次读请求。
     *
     * @param consumer   谁在问（审计标签，如 "rdd-env"）
     * @param dimension  维度 id
     * @param topic      主题（由宿主catalog 决定，未知主题返回 ERROR）
     * @param selector   类型化的选择条件（空表=该主题默认范围）
     * @param maxAgeMs   允许的最大陈旧度；0/负=不限制
     * @param maxItems   最多返回条目；0/负=宿主默认
     * @param deadlineMs 预算；0/负=宿主默认
     */
    record Request(String consumer, String dimension, String topic,
                   Map<String, String> selector, long maxAgeMs, int maxItems, long deadlineMs) {

        public Request {
            consumer = blankTo(consumer, "unknown");
            dimension = blankTo(dimension, "");
            topic = blankTo(topic, "");
            selector = selector == null ? Map.of() : Map.copyOf(selector);
            if (topic.isBlank()) {
                throw new IllegalArgumentException("topic required");
            }
        }

        private static String blankTo(String v, String fallback) {
            return v == null || v.isBlank() ? fallback : v.trim();
        }
    }

    /**
     * 一条观测。
     *
     * @param values         KNOWN 的字段（不保证齐全）
     * @param unknownFields  这次没观测到 / 不支持的字段——**缺字段不等于 0/false**
     * @param staleFields    有值但超过 maxAge 的字段
     */
    record Item(Map<String, Object> values, Set<String> unknownFields, Set<String> staleFields) {

        public Item {
            values = values == null ? Map.of() : Map.copyOf(values);
            unknownFields = unknownFields == null ? Set.of() : Set.copyOf(unknownFields);
            staleFields = staleFields == null ? Set.of() : Set.copyOf(staleFields);
        }

        public static Item of(Map<String, Object> values) {
            return new Item(values, Set.of(), Set.of());
        }
    }

    /**
     * 一次读的结果。
     *
     * @param status       传输/采集状态
     * @param topic        回显主题
     * @param snapshotId   同一时刻的快照标识；跨 provider 拼不出同刻时可为空
     * @param observedTick 观测时刻（游戏刻/毫秒，由宿主声明）
     * @param coverage     覆盖程度
     * @param reason       ERROR/PENDING/PARTIAL 的人话原因
     * @param watermark    取快照时变化日志的尾部序号（041 §18.1 / R3-3 接缝）。v1 消费者可忽略（恒 0）。
     *                     <b>两种取法，各看前提</b>：
     *                     <ul>
     *                       <li><b>有共享锁</b>（存储与日志同一临界区）：锁内"先快照、后取水位"——无重复、无遗漏；</li>
     *                       <li><b>没有共享锁</b>（本仓现状）：必须"<b>先取水位</b>、再读快照"——
     *                           中间写入的 streamSeq &gt; 水位，必进 changes（可重复、不可遗漏）；
     *                           反过来先快照后取水位，中间写入的 streamSeq ≤ 水位，会被过滤掉（漏）。</li>
     *                     </ul>
     *                     不写前提直接说"快照后取"，下一个没有共享锁的实现照做就会造出夹缝。
     */
    record Response(Status status, String topic, String snapshotId, long observedTick,
                    Coverage coverage, String reason, List<Item> items, long watermark, ObservationMetadata metadata) {

        /** Compatibility for existing plugins; absent provenance stays explicitly UNKNOWN. */
        public Response(Status status, String topic, String snapshotId, long observedTick,
                        Coverage coverage, String reason, List<Item> items, long watermark) {
            this(status, topic, snapshotId, observedTick, coverage, reason, items, watermark, ObservationMetadata.unknown());
        }

        public Response {
            metadata = metadata == null ? ObservationMetadata.unknown() : metadata;
            if (status == null) {
                throw new IllegalArgumentException("status required");
            }
            topic = topic == null ? "" : topic;
            snapshotId = snapshotId == null ? "" : snapshotId;
            coverage = coverage == null ? Coverage.UNKNOWN : coverage;
            items = items == null ? List.of() : List.copyOf(items);
            if (status == Status.ERROR && (reason == null || reason.isBlank())) {
                throw new IllegalArgumentException("ERROR response requires a reason");
            }
        }

        public static Response error(String topic, String reason) {
            return new Response(Status.ERROR, topic, "", 0L, Coverage.UNKNOWN, reason, List.of(), 0L);
        }

        public static Response ok(String topic, String snapshotId, long observedTick,
                                  Coverage coverage, List<Item> items) {
            return new Response(Status.OK, topic, snapshotId, observedTick, coverage, null, items, 0L);
        }

        /** OK 且读到了内容才算"有东西"；空列表不等于"没有"，要看 coverage。 */
        public boolean hasItems() {
            return status == Status.OK && !items.isEmpty();
        }
    }

    /** 读一次（只读缓存）。实现不得抛异常。 */
    Response read(Request request);

    /** Bounded cached reads; never calls refresh (039/B1, 2026-11-05). */
    default ObservationBatch.Result batchRead(ObservationBatch.Query query) {
        return ObservationBatchReader.read(this, query);
    }

    /** Advertise only wired topics. Empty means a legacy provider has no catalog. */
    default List<String> catalog() { return List.of(); }

    /**
     * 显式刷新：让宿主按自己的预算重新采集一次，再返回可读结果（039 §3）。
     *
     * <p>默认实现明确"不支持"，而不是假装成功——这样旧实现/测试替身不用被迫实现它。
     * 调用方只能在**自己的 tick** 里调，绝不能在上下文组装线程里调（会同步扫世界）。
     */
    default Response refresh(Request request) {
        return Response.error(request == null ? "" : request.topic(),
                "refresh not supported by this provider");
    }

    /**
     * 一条变化记录（041 U4）：**只有被存储接受的写入**才会产生。
     *
     * @param revision 与观测同一修订号（GONE 用独立序号，保证不被游标过滤）
     * @param kind     CREATED / UPDATED / GONE
     */
    record Change(long revision, String topic, String key, String kind, long epochMs) {
        public Change {
            topic = topic == null ? "" : topic;
            key = key == null ? "" : key;
            kind = kind == null ? "UPDATED" : kind;
        }
    }

    /**
     * 增量读的结果。
     *
     * <p><b>{@code gap=true} 表示"历史断档，你得重新同步"</b>——不是"没有变化"。
     * 消费方在 gap 时必须做一次全量 {@link #read}，不能把空列表当"没变"。
     *
     * @param nextCursor             下次请求用的游标（不小于请求里的 after）
     * @param oldestRetainedRevision 当前还保留着的最小 revision（0=无记录）
     */
    record ChangesResponse(Status status, List<Change> changes, long nextCursor, boolean gap,
                           long oldestRetainedRevision, String reason) {
        public ChangesResponse {
            status = status == null ? Status.ERROR : status;
            changes = changes == null ? List.of() : List.copyOf(changes);
        }

        public static ChangesResponse error(String reason) {
            return new ChangesResponse(Status.ERROR, List.of(), 0L, false, 0L, reason);
        }
    }

    /**
     * 读 {@code afterRevision} 之后的变化（只读内存，不扫世界）。
     *
     * <p>默认实现明确"不支持"，避免老实现被迫实现；调用方应把 ERROR 当作"拿不到增量"，
     * 回退成按需 read，而不是当成"没有变化"。
     *
     * @param topics 主题过滤（null/空=不过滤）
     * @param limit  最多返回条数
     */
    default ChangesResponse changes(long afterRevision, List<String> topics, int limit) {
        return ChangesResponse.error("changes not supported by this provider");
    }

    /**
     * **版本化**增量入口（041 §24.3）：v1 走上面那个方法，v2 走这里。
     *
     * <p>版本由调用方声明，服务端按版本分流；不认识的值明确 ERROR，**不静默降级**。
     * 默认实现同样明确"不支持"，避免老实现被迫实现。
     */
    default ChangesV2Response changes(ChangesQuery query) {
        return ChangesV2Response.error("versioned changes not supported by this provider");
    }
}
