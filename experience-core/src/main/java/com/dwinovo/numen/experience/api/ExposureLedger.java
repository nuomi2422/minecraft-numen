package com.dwinovo.numen.experience.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LC1（78 号工作单 §3.1）：<b>正文交付账本</b> —— 「这条经验这一版正文，模型真的收到过吗」
 * 的会话内回执。终结引用校验（verify 晋级门）只认这里。
 *
 * <h2>它补的是哪个洞</h2>
 * {@code TrialRuntime.notePresented} 记的是「渲染进请求」——渲染段随后可能被预算裁掉
 * （78 号 §2.1：EntityAgentLoop 把 executor_knowledge 标成可丢段），所以「呈现过」推不出
 * 「收到过」。本账本只收<b>工具回复通道</b>的交付：{@code experience_expand} 的成功回复、
 * {@code experience_recall} 的成功回复——工具结果不走预算裁剪，回了就是到了。
 *
 * <h2>为什么 EXPANDED / AUTO_EXPANDED 分开记</h2>
 * 工作单 §3.1：「系统主动注入的正文同样记 AUTO_EXPANDED，不伪装为模型主动选择」。
 * 两种都能过晋级门（正文确实到了模型手里），但事后审计「她是不是主动想看」时要分得清。
 *
 * <h2>截断不算完整交付</h2>
 * {@code truncated=true} 的展开只证明「送到了半截」，可能缺关键步骤（工作单 §3.1）。
 * 照记（可审计），但 {@link #delivered} 不认它。
 *
 * <h2>会话内、不落盘</h2>
 * 与 {@code PresentationReceipt} / {@code TrialRuntime} 同一哲学：跨重启的交付早就不在
 * 当前请求里，「曾看过」不等于「当前可依据」。掉电的代价是「这轮晋级要多展开一次」，
 * 不是「记错了」。真账从 monitor 事件（expanded / auto_expanded）随时可对。
 *
 * <h2>上限</h2>每同伴最多 {@link #MAX_PER_COMPANION} 条，超出按最旧驱逐 —— 经验库实测
 * 二十来条，一次会话的交付远用不满；上限只是防长跑会话内存无界。
 */
public final class ExposureLedger {

    /** 模型主动展开（{@code experience_expand} 成功回复）。 */
    public static final String KIND_EXPANDED = "EXPANDED";
    /** 系统随召回主动交付的正文（{@code experience_recall} 成功回复）。 */
    public static final String KIND_AUTO_EXPANDED = "AUTO_EXPANDED";
    /**
     * LC1（78 号 §3 exposureKind 三分类补全）：目录/渲染层曝光——只给标题与适用线索，
     * 正文没交付。**审计用，不算回执**（78 号 §2.1：渲染段可能被预算裁掉，
     * 「呈现过」推不出「收到过」）。把它记进来是为了让「这条经验在目录里出现过」
     * 成为可对账的事实，而不是为了凑计数。
     */
    public static final String KIND_DIRECTORY = "DIRECTORY";

    /** 每同伴保留的交付条数上限（超出驱逐最旧）。 */
    static final int MAX_PER_COMPANION = 256;

    private static final Map<UUID, Deque<Delivery>> LEDGERS = new LinkedHashMap<>();

    private ExposureLedger() {}

    /**
     * 一次交付：哪个 ref、哪一版正文（{@link ContentIdentity} 指纹）、什么方式、
     * 哪次调用、是否截断、何时。version 为空串 = 调用方拿不到条目（如 ref 打不中），
     * 如实记空，不猜。
     */
    public record Delivery(String entryRef, String version, String kind,
                           String toolCallId, boolean truncated, long atMillis) {
        public Delivery {
            entryRef = entryRef == null ? "" : entryRef.trim();
            version = version == null ? "" : version.trim();
            kind = kind == null ? "" : kind.trim();
            toolCallId = toolCallId == null ? "" : toolCallId.trim();
        }

        /** 这条交付算不算「正文完整到达」：非截断 + 两种正文通道之一（DIRECTORY 不算）。 */
        public boolean countsAsReceipt() {
            return !truncated
                    && (KIND_EXPANDED.equals(kind) || KIND_AUTO_EXPANDED.equals(kind));
        }
    }

    /** 记一次交付。companion/ref 为空直接不记（没有归因对象）。 */
    public static synchronized void record(UUID companion, Delivery d) {
        if (companion == null || d == null || d.entryRef().isBlank()) {
            return;
        }
        Deque<Delivery> q = LEDGERS.computeIfAbsent(companion, k -> new ArrayDeque<>());
        q.addLast(d);
        while (q.size() > MAX_PER_COMPANION) {
            q.removeFirst();
        }
    }

    /**
     * 晋级门判据：这条 ref 的<b>指定版本</b>正文，本会话内有过一次完整交付吗。
     *
     * <p>版本必须全等 —— 交付过旧版本不算（工作单：跨版本引用拒绝归因）。
     * 截断的、非正文通道的都不算。</p>
     */
    public static synchronized boolean delivered(UUID companion, String entryRef, String version) {
        return latestReceipt(companion, entryRef, version, Long.MAX_VALUE) != null;
    }

    /**
     * 归因样本要标版本时用：这条 ref 在 {@code beforeMillis} 之前最近一次完整交付的版本。
     * 没有交付返回 {@code ""}（LEGACY 口径 —— 宁可标「不知道」，不按名字猜）。
     */
    public static synchronized String latestDeliveredVersion(UUID companion, String entryRef,
                                                             long beforeMillis) {
        Delivery d = latestReceipt(companion, entryRef, null, beforeMillis);
        return d == null ? "" : d.version();
    }

    /** 某条 ref 交付过哪些版本（去重、按首次出现序）—— verify 拒绝时给「你交付的是旧版」的确切证据。 */
    public static synchronized List<String> deliveredVersions(UUID companion, String entryRef) {
        List<String> out = new ArrayList<>();
        Deque<Delivery> q = LEDGERS.get(companion);
        if (q == null) {
            return out;
        }
        for (Delivery d : q) {
            if (d.entryRef().equals(safeRef(entryRef)) && !d.version().isBlank() && !out.contains(d.version())) {
                out.add(d.version());
            }
        }
        return out;
    }

    /** 某条 ref 的全部交付记录（只读快照，最旧在前）。 */
    public static synchronized List<Delivery> deliveries(UUID companion, String entryRef) {
        List<Delivery> out = new ArrayList<>();
        Deque<Delivery> q = LEDGERS.get(companion);
        if (q == null) {
            return out;
        }
        for (Delivery d : q) {
            if (d.entryRef().equals(safeRef(entryRef))) {
                out.add(d);
            }
        }
        return out;
    }

    /** 面板/工具读数。 */
    public static synchronized Map<String, Object> readout(UUID companion) {
        Map<String, Object> out = new LinkedHashMap<>();
        Deque<Delivery> q = LEDGERS.get(companion);
        int receipts = 0;
        int truncated = 0;
        int directory = 0;
        if (q != null) {
            for (Delivery d : q) {
                if (d.countsAsReceipt()) {
                    receipts++;
                } else if (KIND_DIRECTORY.equals(d.kind())) {
                    directory++;
                } else if (d.truncated()) {
                    truncated++;
                }
            }
        }
        out.put("session_only", true);
        out.put("deliveries", q == null ? 0 : q.size());
        out.put("full_receipts", receipts);
        out.put("directory_exposures", directory);
        out.put("truncated_deliveries", truncated);
        out.put("max_per_companion", MAX_PER_COMPANION);
        return out;
    }

    /** 清空（只给测试用；运行时没有「清空交付账」这回事）。 */
    static synchronized void resetForTest() {
        LEDGERS.clear();
    }

    /**
     * 最近一条满足条件的完整交付：ref 全等、countsAsReceipt、
     * 时间不晚于 beforeMillis；version 非 null 时还要求版本全等。
     */
    private static synchronized Delivery latestReceipt(UUID companion, String entryRef,
                                                       String version, long beforeMillis) {
        if (companion == null || entryRef == null || entryRef.isBlank()) {
            return null;
        }
        Deque<Delivery> q = LEDGERS.get(companion);
        if (q == null) {
            return null;
        }
        Delivery best = null;
        for (Delivery d : q) {
            if (!d.entryRef().equals(entryRef.trim())) continue;
            if (!d.countsAsReceipt()) continue;
            if (d.atMillis() > beforeMillis) continue;
            if (version != null && !d.version().equals(version.trim())) continue;
            if (best == null || d.atMillis() >= best.atMillis()) {
                best = d;
            }
        }
        return best;
    }

    private static String safeRef(String s) {
        return s == null ? "" : s.trim();
    }
}
