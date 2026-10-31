package com.dwinovo.numen.plugins.learner.sample;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * `learner_status` 里样本层那一段的<strong>纯渲染</strong>。
 *
 * <h2>为什么要把"渲染"单独拎出来</h2>
 * 工具类是 {@code NumenTool}，要 MC 运行时才能跑，等于这段逻辑一行都测不到 ——
 * 而它恰恰是最容易撒谎的一段：读数写错、把「没启用」渲染成「0 条样本」、
 * 把缺失字段补成 0，都会让面板看起来正常而事实相反。
 * 所以这里只吃一个 Map、只吐一个 Map，可以离线穷举。
 *
 * <h2>两条不许编的线</h2>
 * <ul>
 *   <li><b>缺失的键保持缺失</b>：不补 0。`samples=0` 与 `samples 未知` 是两件事，
 *       前者读作「确实还没样本」，后者读作「没读到」。</li>
 *   <li><b>note 必须跟着开关走</b>：关着的时候如果只给计数，读的人会以为学习已经在跑。
 *       样本层默认关，这行字是防误读的主要手段。</li>
 * </ul>
 *
 * <p>纯 JVM、无 IO，可离线单测。
 */
public final class SampleStatusView {

    /** 关着时的说明。措辞刻意说得难听：它的唯一作用是防止被读成"已生效"。 */
    public static final String NOTE_DISABLED =
            "样本层关闭（默认）：代码已接线但没生效，别把这里当成学习已经在跑";

    /** 开着时的说明。即使开着，也要说清还没有消费者。 */
    public static final String NOTE_ENABLED =
            "样本层开启：样本在写，但当前没有消费者（复盘调度与 TTL 归档在 D/H 段）";

    /** 会被透传的键（白名单顺序即渲染顺序）。 */
    private static final List<String> KEYS = List.of(
            "enabled", "gateReason", "samples", "occurrences",
            "admitted", "merged", "skipped", "failed", "errors", "lastError");

    private SampleStatusView() {
    }

    /**
     * 渲染面板段。
     *
     * @param raw {@link SampleIntakeRuntime#stats()} 的输出；{@code null} 视为「没读到」
     * @return 只含白名单键 + 必有的 {@code note}；缺失的键<b>不</b>补默认值
     */
    public static Map<String, Object> render(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw != null) {
            for (String k : KEYS) {
                if (raw.containsKey(k)) {
                    out.put(k, raw.get(k));
                }
            }
        }
        boolean enabled = Boolean.TRUE.equals(out.get("enabled"));
        out.put("note", enabled ? NOTE_ENABLED : NOTE_DISABLED);
        if (raw == null) {
            // 不是"0 条样本"，是"根本没读到运行时"。这两件事在面板上必须能分开。
            out.put("unreadable", true);
        }
        return out;
    }

    /** 一行摘要（日志/回执用）。缺字段时如实写 ?，不写 0。 */
    public static String summary(Map<String, Object> raw) {
        Map<String, Object> r = render(raw);
        return "samples=" + orUnknown(r.get("samples"))
                + " occurrences=" + orUnknown(r.get("occurrences"))
                + " enabled=" + Boolean.TRUE.equals(r.get("enabled"))
                + " errors=" + orUnknown(r.get("errors"));
    }

    private static String orUnknown(Object v) {
        return v == null ? "?" : String.valueOf(v);
    }
}
