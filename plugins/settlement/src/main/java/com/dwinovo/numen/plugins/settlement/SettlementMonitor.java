package com.dwinovo.numen.plugins.settlement;

import com.dwinovo.numen.monitor.MonitoringJournal;

import java.util.Map;

/**
 * 基地与设施的<b>观测出口</b>（用户 2026-10-10 点名「接到监测台」）。
 *
 * <p>把 settlement 的语义事件写进 {@code config/numen/monitor/settlement.jsonl}（category =
 * {@code settlement}），监测台 8776 的「2.4 基地与设施」页只读展示。走通用的
 * {@link MonitoringJournal}（有界队列 + 分区文件 + 单写线程），<b>写失败静默</b>——
 * 观测不是执行依赖，坏了不该让施工停手。
 *
 * <p>为什么不用 {@code tools.jsonl}：那里已有 settlement 的 {@code tool_call}/{@code tool_result}
 * （通用工具面），但没有「哪个模板/哪一格/受理还是被拒/收口还差几格」这类<b>语义</b>，
 * 所以单开一条 settlement 流。
 */
public final class SettlementMonitor {

    private SettlementMonitor() {}

    public static void publish(String type, Map<String, ?> data) {
        try {
            MonitoringJournal.get().publish("settlement", type, data == null ? Map.of() : data);
        } catch (RuntimeException | LinkageError ignored) {
            // 观测失败不影响基地主流程
        }
    }
}
