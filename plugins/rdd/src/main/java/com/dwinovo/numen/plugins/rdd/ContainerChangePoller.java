package com.dwinovo.numen.plugins.rdd;

/**
 * 容器增量的轮询器（041 §22 / D-020 的可测替身）。
 *
 * <p>把"这次轮询要不要立刻重扫"从 {@code RddContainerMemory} 里剥出来：
 * 那边只能读真实世界、只能在游戏里跑；这里收成纯逻辑，用假数据源就能断言三种情形。
 *
 * <p>口径（与 041 §22.3 的 {@code ChangeSignals.shouldRescan} 一致）：
 * <ul>
 *   <li>有变化 → 重扫；</li>
 *   <li>断档（gap） → 重扫（手里的数据不可信）；</li>
 *   <li>没变化 → 不重扫，保持原轮询节奏；</li>
 *   <li>增量不可用（null） → 不重扫，但**不能**当成"没变化"的证据（调用方退回节奏扫描）。</li>
 * </ul>
 *
 * <p>线程：每只同伴一个实例，只在服务端 tick 用。
 */
final class ContainerChangePoller {

    /** 一页增量（与宿主 API 解耦，便于测试）。 */
    record Page(boolean resetRequired, long nextCursor, int eventCount) {}

    /** 数据源：返回 null 表示"拿不到增量"。 */
    interface ChangesSource {
        Page page(long cursor);
    }

    private long cursor = 0L;

    long cursor() {
        return cursor;
    }

    /**
     * 轮询一次。
     *
     * @return true=需要立刻重扫
     */
    boolean poll(ChangesSource source) {
        if (source == null) return false;
        Page page = source.page(cursor);
        if (page == null) return false;          // 拿不到增量：退回节奏扫描，不当"没变化"
        cursor = page.nextCursor();
        return page.resetRequired() || page.eventCount() > 0;
    }
}
