package com.dwinovo.numen.plugins.learner.blocks;

import java.util.Objects;

/**
 * 一个环境块（158 §4）。
 *
 * <h2>它必须带齐的五件事</h2>
 * 158 §4 原文：每块带 {@code blockId / capturedAt / source / coverage / freshness / confidence}。
 * 少任何一个，读者就无法判断「这条事实能不能用来下结论」：
 * <ul>
 *   <li>{@code source} —— 它从哪来（worldstate 观测 / context.jsonl / 轨迹切片）；</li>
 *   <li>{@code capturedAt} —— 什么时候采的（不是什么时候读的）；</li>
 *   <li>{@code coverage} —— 覆盖到哪里（扫描范围未发现 ≠ 世界不存在）；</li>
 *   <li>{@code freshness} —— 还算不算当前；</li>
 *   <li>{@code confidence} —— 可信度档位。</li>
 * </ul>
 *
 * <h2>为什么 confidence 是枚举而不是 0~1 的小数</h2>
 * 161 §2 实测：现有 worldstate 的 {@code AssetClaim} 明写「不设 confidence」。
 * 既然没有校准过的概率来源，写 {@code 0.87} 就是假精度 —— 读者会拿它当概率用。
 * 所以这里只给档位（HIGH/MEDIUM/LOW/UNKNOWN），并强制 UNKNOWN 是合法值。
 *
 * <h2>为什么正文与目录分开</h2>
 * 158 §4：目录只给名称、类型、摘要；正文按需展开。
 * 目录里塞正文会让「每次复盘都把整份环境塞进上下文」，那是把 161 §6.6 的坑
 * （必知区被撑爆 → 整轮不发请求）换了个地方踩。
 *
 * @param body  正文；目录模式下为 {@code null}（<b>不是空串</b>：空串代表"展开过但确实没内容"）
 */
public record EnvBlock(
        BlockKind kind,
        String blockId,
        long capturedAt,
        String source,
        Coverage coverage,
        Freshness freshness,
        Confidence confidence,
        String summary,
        String body,
        boolean truncated
) {

    /** 覆盖范围。 */
    public enum Coverage {
        /** 扫全了，范围内确定的。 */
        COMPLETE,
        /** 只扫了一部分（半径、页数、时间窗受限）。 */
        PARTIAL,
        /** 没有覆盖信息。 */
        UNKNOWN,
        /** 明确没有覆盖（没采集）。 */
        NONE
    }

    /** 新鲜度。 */
    public enum Freshness {
        CURRENT,
        STALE,
        UNKNOWN
    }

    /** 可信度档位（不撒谎成概率）。 */
    public enum Confidence {
        HIGH,
        MEDIUM,
        LOW,
        UNKNOWN
    }

    public EnvBlock {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(blockId, "blockId");
        if (blockId.isBlank()) {
            throw new IllegalArgumentException("blockId 不能为空");
        }
        source = source == null ? "" : source.trim();
        summary = summary == null ? "" : summary;
        coverage = coverage == null ? Coverage.UNKNOWN : coverage;
        freshness = freshness == null ? Freshness.UNKNOWN : freshness;
        confidence = confidence == null ? Confidence.UNKNOWN : confidence;
    }

    /** 目录视图：不带正文。 */
    public EnvBlock summaryOnly() {
        return new EnvBlock(kind, blockId, capturedAt, source, coverage, freshness, confidence,
                summary, null, truncated);
    }

    /** 是否已经展开过（正文非 null）。 */
    public boolean expanded() {
        return body != null;
    }

    /**
     * 一行渲染。
     *
     * <p>关键规矩（158 §4）：<b>UNKNOWN 不得写成 ZERO</b>。
     * 所以覆盖度/新鲜度/可信度未知时，渲染出来的就是 {@code UNKNOWN} 三个字，
     * 而不是 {@code 0}、{@code false} 或一个空格。
     */
    public String renderLine() {
        return "[" + kind.name() + "] " + blockId
                + " | " + (summary.isBlank() ? "(无摘要)" : summary)
                + " | source=" + (source.isBlank() ? "UNKNOWN" : source)
                + " capturedAt=" + (capturedAt > 0 ? String.valueOf(capturedAt) : "UNKNOWN")
                + " coverage=" + coverage.name()
                + " freshness=" + freshness.name()
                + " confidence=" + confidence.name()
                + (truncated ? " [truncated]" : "");
    }
}
