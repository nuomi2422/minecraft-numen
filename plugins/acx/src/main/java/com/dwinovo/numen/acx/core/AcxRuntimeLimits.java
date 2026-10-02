package com.dwinovo.numen.acx.core;

/**
 * 运行期熔断阈值。默认值见 {@link AcxLimits}，由宿主按需覆盖。
 */
public final class AcxRuntimeLimits {

    private final int maxSteps;
    private final long maxTimeoutMs;
    private final int maxDepth;

    public AcxRuntimeLimits(int maxSteps, long maxTimeoutMs, int maxDepth) {
        this.maxSteps = maxSteps <= 0 ? AcxLimits.DEFAULT_MAX_STEPS : maxSteps;
        this.maxTimeoutMs = maxTimeoutMs <= 0 ? AcxLimits.DEFAULT_MAX_TIMEOUT_MS : maxTimeoutMs;
        this.maxDepth = maxDepth <= 0 ? 8 : maxDepth;
    }

    public static AcxRuntimeLimits defaults() {
        return new AcxRuntimeLimits(
                AcxLimits.DEFAULT_MAX_STEPS,
                AcxLimits.DEFAULT_MAX_TIMEOUT_MS,
                8);
    }

    public int maxSteps() {
        return maxSteps;
    }

    public long maxTimeoutMs() {
        return maxTimeoutMs;
    }

    /** 子 AC 递归层数上限，防 A 调 B 调 A 的间接无限递归。 */
    public int maxDepth() {
        return maxDepth;
    }
}
