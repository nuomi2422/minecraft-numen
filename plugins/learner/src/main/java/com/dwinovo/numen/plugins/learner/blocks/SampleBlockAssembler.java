package com.dwinovo.numen.plugins.learner.blocks;

import com.dwinovo.numen.plugins.learner.sample.LearningSample;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一条学习样本和 `context.jsonl` 接起来：样本 → 它那段时间的块（159 D3）。
 *
 * <h2>取哪一段：事故前 60s 到事故后 30s</h2>
 * 这个窗口不是拍脑袋：{@code TrajectorySlices} 用的就是 -60s/+30s（161 §2 实测）。
 * 复用同一组常数有两个好处：两个视图看到的是同一段时间；将来要调也只调一处口径。
 *
 * <h2>为什么样本摘要块必须排第一</h2>
 * 目录要有稳定的打开顺序。学习者先看到"这是个什么样本"，再决定展开哪些块；
 * 如果摘要排在中间，目录的阅读顺序就依赖数据插入顺序，翻页也失去意义。
 *
 * <h2>摘要块的 freshness 故意用样本时间算</h2>
 * 它不是观测，是我们自己的登记记录；它的"新鲜"指的是<b>这条样本多久之前发生的</b>，
 * 不是"这份摘要多久之前生成的"。混用两种含义会让读者误判该不该拿它下结论。
 *
 * <p>纯 JVM、无 IO、时钟由调用方传入。
 */
public final class SampleBlockAssembler {

    /** 事故之前看多久。 */
    public static final long BEFORE_MILLIS = 60_000L;

    /** 事故之后看多久。 */
    public static final long AFTER_MILLIS = 30_000L;

    private SampleBlockAssembler() {
    }

    /**
     * 组装结果。
     *
     * @param windowNote 这次的取数窗口（给回执用；空样本时说明为什么只有一块）
     */
    public record Assembled(List<EnvBlock> blocks, int skipped, List<String> problems, String windowNote) {
        public Assembled {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            problems = problems == null ? List.of() : List.copyOf(problems);
            windowNote = windowNote == null ? "" : windowNote;
        }

        public int blockCount() {
            return blocks.size();
        }
    }

    public static Assembled assemble(LearningSample sample, List<String> lines, long nowMillis) {
        if (sample == null) {
            throw new IllegalArgumentException("样本不能为空");
        }
        List<EnvBlock> blocks = new ArrayList<>();
        blocks.add(summaryOf(sample, nowMillis));

        long from = sample.earliestEvidenceAt() - BEFORE_MILLIS;
        long to = sample.latestEvidenceAt() + AFTER_MILLIS;
        ContextBlockSource.Result ctx = ContextBlockSource.extract(
                lines == null ? List.of() : lines,
                new ContextBlockSource.Selection(sample.companionId(), from, to),
                nowMillis,
                DEFAULT_STALE);
        blocks.addAll(ctx.blocks());

        String note = "window=" + from + ".." + to
                + " companion=" + (sample.companionId().isBlank() ? "UNKNOWN" : sample.companionId())
                + " context_blocks=" + ctx.blocks().size();
        return new Assembled(blocks, ctx.skipped(), ctx.problems(), note);
    }

    /**
     * 组装并打开一个读取会话：样本 → 块 → {@link BlockReader}。
     *
     * <p>为什么不把组装写在调用方：`open` 必须和 `assemble` 用同一个 `nowMillis`，
     * 否则"新鲜度"和"账本时间"会差出一次 IO 的时长，复盘时对不上。
     */
    public static BlockReader openSession(LearningSample sample, List<String> lines,
                                          ReadLedger ledger, long nowMillis) {
        Assembled a = assemble(sample, lines, nowMillis);
        return BlockReader.open(sample.sampleId(), a.blocks(), ledger, nowMillis);
    }

    /** 引擎新鲜度阈值（与 {@link ContextBlockSource#DEFAULT_STALE_AFTER_MILLIS} 保持一致）。 */
    static final long DEFAULT_STALE = ContextBlockSource.DEFAULT_STALE_AFTER_MILLIS;

    /**
     * 样本摘要块（161 里唯一真正缺的一类，现在已经能产）。
     *
     * <p>正文用 {@link LearningSample#summary()} 的键值展开，<b>不</b>加任何推断。
     */
    static EnvBlock summaryOf(LearningSample sample, long nowMillis) {
        StringBuilder body = new StringBuilder();
        for (var e : sample.summary().entrySet()) {
            body.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        long at = sample.latestEvidenceAt();
        EnvBlock.Freshness f;
        if (at <= 0) {
            f = EnvBlock.Freshness.UNKNOWN;
        } else {
            f = (nowMillis - at) <= DEFAULT_STALE ? EnvBlock.Freshness.CURRENT : EnvBlock.Freshness.STALE;
        }
        return new EnvBlock(
                BlockKind.SAMPLE_SUMMARY,
                "summary-" + sample.sampleId(),
                at,
                "samples.jsonl",
                EnvBlock.Coverage.COMPLETE,
                f,
                // 我们自己登记的事实，不需要猜。
                EnvBlock.Confidence.HIGH,
                sample.triggerKind() + " @ " + (at > 0 ? String.valueOf(at) : "UNKNOWN")
                        + " occurrences=" + sample.occurrenceCount(),
                body.toString(),
                false);
    }
}
