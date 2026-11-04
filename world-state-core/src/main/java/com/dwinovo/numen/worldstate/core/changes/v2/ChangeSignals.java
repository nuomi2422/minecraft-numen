package com.dwinovo.numen.worldstate.core.changes.v2;

/**
 * 消费决策的纯函数（042 R3 / D-020 的离线替身）。
 *
 * <p>为什么抽出来：消费者里"要不要因为变化而提前重扫"是一个三行判断，但写在插件类里就没法离线测。
 * 抽成纯函数后可以用假页把三种情形都断言掉：有变化 → 重扫；断档 → 重扫；没变化 → 不重扫。
 */
public final class ChangeSignals {

    private ChangeSignals() {}

    /**
     * 这一页结果是否要求调用方**立刻重扫**（而不是等下一个轮询周期）。
     *
     * <p>断档也必须重扫：历史丢了，手里那份数据不可信。
     */
    public static boolean shouldRescan(ChangesPage page) {
        if (page == null) return false;
        if (page.resetRequired()) return true;
        return !page.events().isEmpty();
    }

    /** 这一页结果是否说明"增量链路不可用"（调用方应退回有界轮询，而不是当没变化）。 */
    public static boolean isUnusable(ChangesPage page) {
        return page == null;
    }
}
