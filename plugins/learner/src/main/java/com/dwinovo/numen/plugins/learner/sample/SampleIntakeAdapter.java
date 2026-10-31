package com.dwinovo.numen.plugins.learner.sample;

import com.dwinovo.numen.plugins.learner.core.CandidateMemo;
import com.dwinovo.numen.plugins.learner.core.FeedbackEvent;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 三条 intake 口 → 同一个样本候选的<strong>适配器</strong>（160 §6/§7）。
 *
 * <h2>为什么要有这个类</h2>
 * 161 §6.5 记的现状：手动口 `learner_intake` 遇风暴直接丢、自动口 `LearnerAutoIntake` 暂存回灌、
 * `ToolFailureIntake` 两个门都不走 —— <b>同一事实三条路径三种结论</b>。
 * 只要三条口各自决定「算不算一次出现、算不算同一件事」，occurrence 合并就注定漏，
 * 而且漏的方式随入口漂移（今天这样、明天那样）。
 *
 * <p>所以把<b>判定</b>与<b>映射</b>收到一处：三条口只负责把手里的事实填进
 * {@link Envelope} 和一个已知形状，剩下的「新建/并入/疑似重复」全部交给
 * {@link SampleAdmission#decide}。适配器<b>只做格式与 ID 映射</b>，
 * 不判断质量、不决定是否发布、不改任何旧入口行为（160 §7）。
 *
 * <h2>三条不许编的线（沿用 {@link CandidateMemo} 的同一纪律）</h2>
 * <ul>
 *   <li>缺同伴 → 直接返回 {@code present()==false}，<b>不</b>编一个 id；</li>
 *   <li>时间戳解析不了 → 用调用方给的登记时刻，并记 {@code ts_unparsed} 警告，
 *       <b>不</b>假装它是原始时间；</li>
 *   <li>没有归因字段 → cause 记 {@code unattributed}（一个字面量，不是推测），
 *       并记 {@code no_attribution} 警告。</li>
 * </ul>
 *
 * <h2>一个已知取舍：无归因事件会按「同类型 + 同时间桶」合并</h2>
 * 158 §6 说「判断不了是否相同则保留独立样本并标记」。但重复上报必须合并
 * （159 §3 剧本 1：同一死亡上报三次 = 一个样本三条 occurrence），而「无归因」
 * 恰恰无法区分「同一条被上报三次」与「三条不同的无归因事件」。
 * 这里选择<b>先合并</b>（保幂等，代价是无归因事件在桶内的复发会被并成一条），
 * 并把这件事写进警告让统计看得见。反过来选会导致重启后样本翻倍，
 * 那是更难修、且会污染所有复发率指标的错误。
 *
 * <p>纯 JVM、无 MC/NUMEN 依赖、无 IO，可离线单测。
 */
public final class SampleIntakeAdapter {

    /** 无归因时 cause 的字面量。它是<b>标记</b>，不是推测出的归因。 */
    public static final String UNATTRIBUTED = "unattributed";

    public static final String SRC_MANUAL = "manual_intake";
    public static final String SRC_AUTO = "auto_intake";
    public static final String SRC_TOOL_FAILURE = "tool_failure";

    private SampleIntakeAdapter() {
    }

    /**
     * 一次 intake 的上下文：这些字段三条口都拿得到（拿不到就用空串，由 {@link SampleEvent}
     * 统一记 UNKNOWN）。<b>不在这里补默认值</b>，补了就等于伪造。
     *
     * @param nowMillis 登记时刻（仅用于时间戳解析失败时的兜底与事件时间）
     */
    public record Envelope(String runId, String goalId, String planVersion,
                           String worldId, String dimension, String source, long nowMillis) {
        public Envelope {
            runId = runId == null ? "" : runId.trim();
            goalId = goalId == null ? "" : goalId.trim();
            planVersion = planVersion == null ? "" : planVersion.trim();
            worldId = worldId == null ? "" : worldId.trim();
            dimension = dimension == null ? "" : dimension.trim();
            source = (source == null || source.isBlank()) ? "unknown" : source.trim();
        }
    }

    /**
     * 映射结果。
     *
     * @param candidate 为 {@code null} 表示这条事实<b>不能</b>成为样本（例如缺同伴）
     * @param warnings  降级/缺失的事实，必须能被统计出来，不许静默
     */
    public record Mapped(SampleAdmission.Candidate candidate, List<String> warnings) {
        public Mapped {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean present() {
            return candidate != null;
        }

        public static Mapped skip(String reason) {
            return new Mapped(null, List.of(reason));
        }
    }

    // ------------------------------------------------------------------ 事件

    /** 手动口 / 自动口共用：一条 {@link FeedbackEvent} → 样本候选。 */
    public static Mapped fromEvent(FeedbackEvent ev, Envelope env) {
        if (ev == null) {
            return Mapped.skip("no_event");
        }
        if (env == null) {
            return Mapped.skip("no_envelope");
        }
        List<String> warnings = new ArrayList<>();

        String companion = CandidateMemo.companionOf(ev);
        if (companion == null || companion.isBlank()) {
            // 编一个同伴 id 会让这条样本永远无法回链到任何真实主体。
            return Mapped.skip("missing_companion");
        }

        String cause = causeOf(ev);
        if (cause.isBlank()) {
            warnings.add("no_attribution");
            cause = UNATTRIBUTED;
        }

        long at = parseTimestamp(ev.ts());
        if (at < 0) {
            warnings.add("ts_unparsed");
            at = env.nowMillis();
        }

        String world = pick(env.worldId(), ev, "worldId", "world");
        String dim = pick(env.dimension(), ev, "dimension", "dim");

        SampleAdmission.Candidate c = new SampleAdmission.Candidate(
                companion,
                nz(ev.sourceType()).isBlank() ? "unknown_event" : ev.sourceType(),
                detailOf(ev),
                cause,
                env.runId(), env.goalId(), env.planVersion(),
                world, dim,
                at,
                ev.eventId(),
                env.source());
        return new Mapped(c, warnings);
    }

    // -------------------------------------------------------------- 工具失败

    /**
     * 工具失败口：`ToolFailureIntake` 手上的是「同一个 (同伴,工具,错误形态) 在窗口内失败 N 次」。
     *
     * <p>为什么用 {@code lastAt} 而不是 {@code firstAt} 做发生时间：窗口是"最近还在发生"的证据，
     * 用首次时间会把一条持续到现在的失败塞进很久以前的时间桶，和当时的其它事件错误合并。
     */
    public static Mapped fromToolFailure(String companionId,
                                         String tool,
                                         String errorClass,
                                         int count,
                                         long lastAtMillis,
                                         String firstEventId,
                                         Envelope env) {
        if (env == null) {
            return Mapped.skip("no_envelope");
        }
        if (companionId == null || companionId.isBlank()) {
            return Mapped.skip("missing_companion");
        }
        if (tool == null || tool.isBlank()) {
            return Mapped.skip("missing_tool");
        }
        List<String> warnings = new ArrayList<>();
        if (firstEventId == null || firstEventId.isBlank()) {
            warnings.add("missing_evidence_ref");
        }
        String cause = tool.trim() + "|" + (errorClass == null || errorClass.isBlank() ? "unknown_error" : errorClass.trim());
        SampleAdmission.Candidate c = new SampleAdmission.Candidate(
                companionId.trim(),
                SRC_TOOL_FAILURE,
                "工具 " + tool.trim() + " 连续失败 " + count + " 次（同一错误形态）",
                cause,
                env.runId(), env.goalId(), env.planVersion(),
                env.worldId(), env.dimension(),
                lastAtMillis,
                firstEventId == null ? "" : firstEventId.trim(),
                env.source());
        return new Mapped(c, warnings);
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 归因签名：按<b>固定顺序</b>取第一个存在的归因字段，多个用 {@code |} 连接。
     *
     * <p>为什么固定顺序：指纹要能跨进程复算，字段拼接顺序一变就是另一条指纹。
     */
    public static String causeOf(FeedbackEvent ev) {
        if (ev == null) {
            return "";
        }
        Map<String, Object> obs = ev.observation();
        String[] keys = {"deathCauseId", "deathAttacker", "deathKind", "reason", "task", "tool"};
        List<String> parts = new ArrayList<>();
        for (String k : keys) {
            Object v = CandidateMemo.firstPresent(obs, k);
            if (v != null && !String.valueOf(v).isBlank()) {
                parts.add(k + "=" + String.valueOf(v).trim());
            }
        }
        return String.join("|", parts);
    }

    /** 触发细节：只复述事件自带的字段，不加推测。 */
    public static String detailOf(FeedbackEvent ev) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(nz(ev.kind())).append('/').append(nz(ev.sourceType())).append("] ");
        String cause = causeOf(ev);
        sb.append(cause.isBlank() ? "事件没有带任何归因字段" : cause);
        return sb.toString();
    }

    /**
     * 解析源事件时间：纯数字按 epoch 毫秒；ISO-8601（含 Z / 偏移）按 Instant；
     * 其余返回 {@code -1}（由调用方兜底并记警告）。
     */
    public static long parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1L;
        }
        String s = raw.trim();
        boolean digits = true;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((i == 0 && ch == '-') || Character.isDigit(ch)) {
                continue;
            }
            digits = false;
            break;
        }
        if (digits) {
            try {
                long v = Long.parseLong(s);
                return v > 0 ? v : -1L;
            } catch (NumberFormatException e) {
                return -1L;
            }
        }
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException e) {
            return -1L;
        }
    }

    private static String pick(String fromEnvelope, FeedbackEvent ev, String... keys) {
        if (fromEnvelope != null && !fromEnvelope.isBlank()) {
            return fromEnvelope;
        }
        for (String k : keys) {
            Object v = CandidateMemo.firstPresent(ev.observation(), k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v).trim();
            }
        }
        return "";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
