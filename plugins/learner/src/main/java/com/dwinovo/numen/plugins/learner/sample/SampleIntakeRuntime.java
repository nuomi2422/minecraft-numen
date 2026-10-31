package com.dwinovo.numen.plugins.learner.sample;

import com.dwinovo.numen.plugins.learner.core.FeedbackEvent;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 样本层的<strong>运行时门面</strong>：给三个 intake 口一个「调用一行、永不抛」的接入口。
 *
 * <h2>为什么要有这一层，而不是让工具直接 new Service</h2>
 * 调用点是三个已经稳定运行的工具。它们不该因为「学习旁路」多出三件事：
 * 管开关、管单例、管异常。这些都收在这里，工具那边只剩一行
 * `SampleIntakeRuntime.shared(dir).recordEvent(ev, SRC_MANUAL, now)`。
 *
 * <h2>三条纪律</h2>
 * <ol>
 *   <li><b>永不抛</b>：学习是旁路。写不进去、配置坏了、服务没开 —— 一律吞掉并把事实记进
 *       {@link #errors()} 与返回值，绝不让一次任务因为学习层失败而中断。</li>
 *   <li><b>关着就是关着</b>：开关为关时<b>不创建目录、不打开存储、不写文件</b>。
 *       只判一次开关就返回，代价是一次文件 stat。</li>
 *   <li><b>失败可见</b>：吞异常不等于假装没发生 —— `errors` 计数与 `lastError` 都要能被读出来。</li>
 * </ol>
 *
 * <h2>为什么按 configDir 分实例</h2>
 * 同一进程里可能有多个存档/实例（live 与 test 曾并行）。按目录分实例，
 * 样本才不会从 A 存档漏到 B 存档 —— 而"跨世界串味"是最难复现的一类错。
 *
 * <p>纯 JVM、可离线单测（传入临时目录即可）。
 */
public final class SampleIntakeRuntime {

    private static final Map<Path, SampleIntakeRuntime> BY_DIR = new ConcurrentHashMap<>();

    /** 开关复查间隔：与 {@code LearnerAutoIntake} 的 10s 一致，改配置不用重启。 */
    private static final long GATE_TTL_MILLIS = 10_000L;

    private final Path configDir;
    private final long bucketMillis;

    private SampleIntakeService service;
    private SampleIntakeGate.State gate = SampleIntakeGate.State.off("未检查");
    private long gateCheckedAt;
    private int errors;
    private String lastError = "";

    private SampleIntakeRuntime(Path configDir, long bucketMillis) {
        this.configDir = configDir;
        this.bucketMillis = bucketMillis;
    }

    /** 取（或建）某个 configDir 的门面。 */
    public static SampleIntakeRuntime shared(Path configDir) {
        return shared(configDir, SampleFingerprint.DEFAULT_BUCKET_MILLIS);
    }

    public static SampleIntakeRuntime shared(Path configDir, long bucketMillis) {
        if (configDir == null) {
            throw new IllegalArgumentException("configDir 不能为空");
        }
        return BY_DIR.computeIfAbsent(configDir, d -> new SampleIntakeRuntime(d, bucketMillis));
    }

    /** 测试隔离：清掉所有已缓存实例（它们持有文件句柄级别的状态）。 */
    public static void resetForTest() {
        BY_DIR.clear();
    }

    /** 当前开关（带 10s 缓存）。 */
    public synchronized boolean enabled() {
        long now = System.currentTimeMillis();
        if (gateCheckedAt > 0 && now - gateCheckedAt < GATE_TTL_MILLIS) {
            return gate.enabled();
        }
        gateCheckedAt = now;
        gate = SampleIntakeGate.read(configDir);
        return gate.enabled();
    }

    public synchronized SampleIntakeGate.State gate() {
        enabled();
        return gate;
    }

    /** 手动口 / 自动口一行接入。关着时返回 {@code Optional.empty()}（没发生任何事）。 */
    public Optional<SampleIntakeService.Result> recordEvent(FeedbackEvent ev,
                                                            String source,
                                                            long nowMillis) {
        if (!enabled()) {
            return Optional.empty();
        }
        try {
            SampleIntakeAdapter.Envelope env = new SampleIntakeAdapter.Envelope(
                    "", "", "", "", "", source, nowMillis);
            return Optional.of(service().admitEvent(ev, env));
        } catch (RuntimeException | java.io.IOException e) {
            noteError(e);
            return Optional.empty();
        }
    }

    /** 工具失败口一行接入。 */
    public Optional<SampleIntakeService.Result> recordToolFailure(String companionId,
                                                                  String tool,
                                                                  String errorClass,
                                                                  int count,
                                                                  long lastAtMillis,
                                                                  String firstEventId,
                                                                  long nowMillis) {
        if (!enabled()) {
            return Optional.empty();
        }
        try {
            SampleIntakeAdapter.Envelope env = new SampleIntakeAdapter.Envelope(
                    "", "", "", "", "", SampleIntakeAdapter.SRC_TOOL_FAILURE, nowMillis);
            return Optional.of(service().admitToolFailure(
                    companionId, tool, errorClass, count, lastAtMillis, firstEventId, env));
        } catch (RuntimeException | java.io.IOException e) {
            noteError(e);
            return Optional.empty();
        }
    }

    private synchronized SampleIntakeService service() throws java.io.IOException {
        if (service == null) {
            service = SampleIntakeService.open(configDir.resolve("samples.jsonl"), bucketMillis);
        }
        return service;
    }

    private synchronized void noteError(Exception e) {
        errors++;
        lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    public synchronized int errors() {
        return errors;
    }

    public synchronized String lastError() {
        return lastError;
    }

    /** 面板读数：开关/错误/入库计数一次看全。 */
    public synchronized Map<String, Object> stats() {
        // ⚠ 必须先触发一次开关复查：直接读 gate 字段会拿到「未检查」，
        //   面板就会显示一个从没被读过的状态（这条是单测逼出来的，别改回去）。
        SampleIntakeGate.State s = gate();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.enabled());
        m.put("gateReason", s.reason());
        m.put("errors", errors);
        if (!lastError.isBlank()) {
            m.put("lastError", lastError);
        }
        if (service != null) {
            m.putAll(service.stats());
        }
        return m;
    }

    public Path configDir() {
        return configDir;
    }

    /**
     * TTL 归档扫描（158 §8：只归档不删证据）。
     *
     * <p>只在<b>启用</b>且主实例已就绪时执行 —— 否则会新建第二个 `SampleStore` 去改文件，
     * 而主实例内存里还是旧样子，下一次写入就会把归档结果覆盖掉（双实例互相踩）。
     *
     * @return 归档条数；未启用或失败时为 0（失败计数进 {@link #errors()}）
     */
    public synchronized int sweepExpired(long nowMillis, long ttlMillis) {
        if (!enabled() || service == null) {
            return 0;
        }
        try {
            return service.store().archiveExpired(nowMillis, ttlMillis);
        } catch (RuntimeException | java.io.IOException e) {
            noteError(e);
            return 0;
        }
    }

    /**
     * 只读取样本存储（给 `learner_blocks` 这类只读工具用）。
     *
     * <p>它<b>不</b>受开关影响：开关关的是「要不要往样本层写」，不是「能不能读已有的样本」。
     * 关着还能读，才能在不开写入的情况下排查历史样本。
     *
     * <p>代价（必须知道）：这里可能创建<b>第二个</b> `SampleStore` 实例指向同一文件。
     * 它只做 get/list、不做 admit，所以不会覆盖；但若同一进程里两处同时 admit，
     * 后写的那份会覆盖前一份 —— 单进程写者约定见 {@code SampleStore} 类注释。
     */
    public synchronized SampleStore storeReadOnly() throws java.io.IOException {
        if (service != null) {
            return service.store();
        }
        return SampleStore.open(configDir.resolve("samples.jsonl"), bucketMillis);
    }
}
