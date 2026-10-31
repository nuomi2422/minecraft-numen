package com.dwinovo.numen.plugins.learner.sample;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/**
 * 学习样本的稳定指纹（158 §6）。
 *
 * <h2>它由哪几段组成，为什么是这几段</h2>
 * 158 §6 原文：指纹由「companion、trigger kind、goal/plan identity、归一化 cause signature、
 * 时间桶和相关世界身份」组成。逐段的作用：
 * <ul>
 *   <li>{@code companion} —— 换同伴就是换主体，A 的死亡不能替 B 去重（78 §2.2 同款教训）；</li>
 *   <li>{@code triggerKind} —— 死亡和资源浪费不是同一类样本；</li>
 *   <li>{@code goalId + planVersion} —— 158 §6「目标版本……实质变化时生成新样本」；</li>
 *   <li>{@code cause} —— 「同一对象」的核心：同类事件、不同死因不是同一样本；</li>
 *   <li>{@code worldId + dimension} —— 换存档/换维度就是换世界，不能误合并；</li>
 *   <li>{@code bucket} —— 时间桶：同一次事故被上报三次要合并，隔一天的同名事故不是同一次。</li>
 * </ul>
 *
 * <h2>为什么用可读的规范串，而不是只存一个哈希</h2>
 * 排障时要能一眼看出「这两条为什么被合并」，哈希做不到。
 * 所以主键就是这条规范串（可 grep、可对比），哈希只作为日志短码 {@link #shortHash(String)}。
 *
 * <h2>为什么弱指纹单独存在</h2>
 * 158 §6 要求「判断不了是否相同则保留独立样本并标记 {@code possibleDuplicate}」。
 * 判断不了 = 强指纹不同（cause 不同）但其余全同。这时要有东西能发现「它们像同一件事」，
 * 那就是去掉 cause 的弱指纹。弱指纹只用于<b>标记</b>，永远不用于合并。
 *
 * <p>纯 JVM，无时钟依赖（时间由调用方传入），可离线单测。
 */
public final class SampleFingerprint {

    /** 指纹格式版本。改组成方式必须升版本，否则新旧样本会被判成同一件事。 */
    public static final int VERSION = 1;

    /**
     * 默认时间桶：5 分钟。
     *
     * <p>⚠️ <b>未经实测校准</b>（与 {@code StormGuard.COOLDOWN_MILLIS} 同款说明）：
     * 取「足够容纳一次事故的多条上报，又短于一次任务窗口」的中间值。
     * 真实分布应该看「同指纹 occurrence 的时间跨度」直方图再回来定，
     * 那要先进游戏跑出数据；现阶段允许用构造器参数覆盖。
     */
    public static final long DEFAULT_BUCKET_MILLIS = 5 * 60 * 1000L;

    private SampleFingerprint() {
    }

    /**
     * 归一化：NFKC（全角→半角、兼容字符折叠）→ 去首尾空白 → 连续空白压成一个空格 → 转小写。
     *
     * <p>为什么不直接 {@code trim().toLowerCase()}：中文输入法会产出全角数字/空格，
     * 大模型生成的 cause 文本还会带换行和多余空格。不归一化就会「同一死因出现两条指纹」，
     * 而这类重复恰恰是最难人工发现的（看起来一模一样）。
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        s = s.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").replaceAll("\\s+", " ").trim();
        return s.toLowerCase(Locale.ROOT);
    }

    /** 时间桶序号。{@code bucketMillis <= 0} 时抛错——静默退化成 0 会让所有样本落进同一个桶。 */
    public static long bucketOf(long epochMillis, long bucketMillis) {
        if (bucketMillis <= 0) {
            throw new IllegalArgumentException("bucketMillis 必须为正数，收到 " + bucketMillis);
        }
        return Math.floorDiv(epochMillis, bucketMillis);
    }

    /**
     * 强指纹：全部字段参与。
     *
     * @return 形如 {@code v1|companion=a|kind=death|goal=g1|plan=2|cause=generic_kill|world=w|dim=overworld|bucket=123}
     */
    public static String of(String companionId,
                            String triggerKind,
                            String goalId,
                            String planVersion,
                            String causeSignature,
                            String worldId,
                            String dimension,
                            long bucketIndex) {
        return "v" + VERSION
                + "|companion=" + normalize(companionId)
                + "|kind=" + normalize(triggerKind)
                + "|goal=" + normalize(goalId)
                + "|plan=" + normalize(planVersion)
                + "|cause=" + normalize(causeSignature)
                + "|world=" + normalize(worldId)
                + "|dim=" + normalize(dimension)
                + "|bucket=" + bucketIndex;
    }

    /**
     * 弱指纹：去掉 cause 与 bucket 之外的一切照旧？<b>不是</b>——去掉 cause，<b>保留</b> bucket。
     *
     * <p>理由：弱指纹要在「同一个时间桶内、其余条件全同、只有归因不确定」时触发
     * {@code possibleDuplicate}。如果把 bucket 也去掉，一周前的同类事件也会被标成疑似重复，
     * 标记会失去意义（全都像重复）。
     */
    public static String weakOf(String companionId,
                                String triggerKind,
                                String goalId,
                                String planVersion,
                                String worldId,
                                String dimension,
                                long bucketIndex) {
        return "v" + VERSION + "w"
                + "|companion=" + normalize(companionId)
                + "|kind=" + normalize(triggerKind)
                + "|goal=" + normalize(goalId)
                + "|plan=" + normalize(planVersion)
                + "|world=" + normalize(worldId)
                + "|dim=" + normalize(dimension)
                + "|bucket=" + bucketIndex;
    }

    /** 日志短码：规范串的 SHA-256 前 12 位。仅用于打日志，不作主键（碰撞后不可读）。 */
    public static String shortHash(String canonical) {
        Objects.requireNonNull(canonical, "canonical");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法；真没有说明运行环境坏了，不许静默降级成 hashCode。
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}
