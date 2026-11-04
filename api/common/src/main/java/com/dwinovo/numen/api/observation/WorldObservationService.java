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
     */
    record Response(Status status, String topic, String snapshotId, long observedTick,
                    Coverage coverage, String reason, List<Item> items) {

        public Response {
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
            return new Response(Status.ERROR, topic, "", 0L, Coverage.UNKNOWN, reason, List.of());
        }

        public static Response ok(String topic, String snapshotId, long observedTick,
                                  Coverage coverage, List<Item> items) {
            return new Response(Status.OK, topic, snapshotId, observedTick, coverage, null, items);
        }

        /** OK 且读到了内容才算"有东西"；空列表不等于"没有"，要看 coverage。 */
        public boolean hasItems() {
            return status == Status.OK && !items.isEmpty();
        }
    }

    /** 读一次（只读缓存）。实现不得抛异常。 */
    Response read(Request request);

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
}
