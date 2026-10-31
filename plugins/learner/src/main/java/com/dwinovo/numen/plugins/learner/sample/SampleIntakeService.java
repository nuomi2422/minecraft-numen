package com.dwinovo.numen.plugins.learner.sample;

import com.dwinovo.numen.plugins.learner.core.FeedbackEvent;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 样本入库的<strong>唯一入口</strong>（159 C / 160 §6）。
 *
 * <h2>它解决的是什么问题</h2>
 * 三条 intake 口（`learner_intake` / `LearnerAutoIntake` / `ToolFailureIntake`）各自持有
 * 不同形状的事实，但「这条事实是不是一条新样本」必须只有一个答案。
 * 本类把「适配 → 判定 → 落盘 → 发事件」串成一次调用，
 * **入口只有一个，判定只有一份**。
 *
 * <h2>为什么不把异常抛给调用方</h2>
 * 学习是<b>旁路</b>：样本写不出来，任务链照样要跑。所以 IO 失败一律降级成
 * {@link Result#error()}（带原文），由调用方决定是记日志还是报警 ——
 * 绝不因为学习层写盘失败而中断一次正在执行的任务。
 * 但降级<b>必须可见</b>：{@link #failed()} 计数与 {@code error} 字符串都会如实报，
 * 不允许"默默没学到"。
 *
 * <h2>它不做什么</h2>
 * 不判断质量、不发放行、不决定是否复盘、不改 memo 队列 ——
 * 那些分别是质量门、调度和 {@code MemoQueue} 的事（160 §7：适配器只做映射）。
 *
 * <p>线程安全：委托给 {@link SampleStore} 的同步方法；本类自身只增计数，用 {@code synchronized} 保护。
 */
public final class SampleIntakeService {

    private final SampleStore store;

    private int admitted;
    private int merged;
    private int skipped;
    private int failed;

    private SampleIntakeService(SampleStore store) {
        this.store = store;
    }

    public static SampleIntakeService open(Path file) throws IOException {
        return new SampleIntakeService(SampleStore.open(file));
    }

    public static SampleIntakeService open(Path file, long bucketMillis) throws IOException {
        return new SampleIntakeService(SampleStore.open(file, bucketMillis));
    }

    /**
     * 一次入库结果。三个状态互斥：
     * <ul>
     *   <li>{@code decision != null && error.isEmpty()} —— 成功（新建或并入）；</li>
     *   <li>{@code decision == null && error.isEmpty()} —— <b>按规则跳过</b>（如缺同伴），不算故障；</li>
     *   <li>{@code !error.isEmpty()} —— <b>故障</b>，学习层降级，调用方必须把它记下来。</li>
     * </ul>
     */
    public record Result(SampleAdmission.Decision decision, String error) {
        public Result {
            error = error == null ? "" : error;
        }

        public boolean ok() {
            return decision != null && error.isEmpty();
        }

        public boolean skipped() {
            return decision == null && error.isEmpty();
        }

        public boolean failed() {
            return !error.isEmpty();
        }

        public static Result skip() {
            return new Result(null, "");
        }

        public static Result fail(String error) {
            return new Result(null, error);
        }
    }

    /** 事件型入口（手动口 / 自动口）。 */
    public Result admitEvent(FeedbackEvent ev, SampleIntakeAdapter.Envelope env) {
        SampleIntakeAdapter.Mapped mapped = SampleIntakeAdapter.fromEvent(ev, env);
        return admit(mapped, env == null ? 0L : env.nowMillis());
    }

    /** 工具失败入口。 */
    public Result admitToolFailure(String companionId, String tool, String errorClass, int count,
                                   long lastAtMillis, String firstEventId,
                                   SampleIntakeAdapter.Envelope env) {
        SampleIntakeAdapter.Mapped mapped = SampleIntakeAdapter.fromToolFailure(
                companionId, tool, errorClass, count, lastAtMillis, firstEventId, env);
        return admit(mapped, env == null ? 0L : env.nowMillis());
    }

    /** 通用入口：已映射好的候选。 */
    public synchronized Result admit(SampleIntakeAdapter.Mapped mapped, long nowMillis) {
        if (mapped == null || !mapped.present()) {
            skipped++;
            return Result.skip();
        }
        try {
            SampleAdmission.Decision d = store.admit(mapped.candidate(), nowMillis);
            if (d.outcome() == SampleAdmission.Outcome.MERGED) {
                merged++;
            } else {
                admitted++;
            }
            return new Result(d, "");
        } catch (IOException | RuntimeException e) {
            // 写盘失败/坏 JSON 一律降级，不打断调用方；但计数与原因必须留痕。
            failed++;
            return Result.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public SampleStore store() {
        return store;
    }

    public synchronized int admitted() {
        return admitted;
    }

    public synchronized int merged() {
        return merged;
    }

    public synchronized int skipped() {
        return skipped;
    }

    public synchronized int failed() {
        return failed;
    }

    /** 面板/日志读数（可查性：入库四次计数一眼看清）。 */
    public synchronized java.util.Map<String, Object> stats() {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("admitted", admitted);
        m.put("merged", merged);
        m.put("skipped", skipped);
        m.put("failed", failed);
        m.put("samples", store.sampleCount());
        m.put("occurrences", store.occurrenceCount());
        return m;
    }
}
