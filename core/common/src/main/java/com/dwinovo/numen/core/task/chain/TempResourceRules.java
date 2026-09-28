package com.dwinovo.numen.core.task.chain;

/**
 * 临时资源收尾的纯判据——不碰 Minecraft。
 *
 * <h2>为什么单独拎出来</h2>
 * 自救链放下的东西(水桶倒的那摊水、垫在落点的软方块)是<b>借来的</b>:水桶是消耗品,
 * 不收回去就只能救一次;垫下去的方块会留在世界里。但这条链压过一切,窗口卡死在
 * {@code MLGChain.RECLAIM_TICKS}——它必须在动作做完之后<b>立刻放手</b>,不能为了收尾
 * 一直霸着身体。
 *
 * <p>于是就有了那个真正的缺口:窗口一到就 {@code placed = null},那摊水既没被收回,
 * 也没留下任何记录——世界少了一格水,没有任何人知道。更糟的是 {@code stop()} 也这么
 * 干:被更高优先级的反射抢占时,还没收的水被<b>直接丢掉</b>。这正是"落地水不回收"。
 *
 * <p>所以收尾得有两个窗口:抢占式的快窗口(让身体),和一个被抢占后仍然存在的、
 * 低优先级的清理窗口(把账销掉)。判据只回答"接下来怎么办",动作仍在链里。
 */
public final class TempResourceRules {

    private TempResourceRules() {}

    /** 快窗口到期后的处置。 */
    public enum AfterFastWindow {
        /** 水还在、桶也在 → 转入清理窗口,不立刻放弃(它多半是被抢占打断的)。 */
        DEFER_TO_CLEANUP,
        /** 水没了 / 桶没了 → 账已经自然结清,直接收工,别再占身体。 */
        SETTLED,
        /** 水还在但桶没了(桶被别的东西用了) → 收不回来了,记一笔,放手。 */
        ABANDON_NO_BUCKET
    }

    /** 清理窗口到期后的处置。 */
    public enum AfterCleanupWindow {
        /** 还在 → 记一笔"收不回来了",放手,别无限期占着身体。 */
        ABANDON,
        /** 已经不在了 → 干净收工。 */
        SETTLED
    }

    /**
     * 抢占式快窗口到期时怎么办。
     *
     * <p>关键在于<b>不能因为窗口到点就把账销掉</b>:水还在世界里,收了才是对的,
     * 而收水只需要几刻,不急在这一刻把身体还回去。
     *
     * @param waterStillThere 记着的那格是否还是源水
     * @param hasEmptyBucket 手上是否还有空桶可装
     */
    public static AfterFastWindow afterFastWindow(boolean waterStillThere, boolean hasEmptyBucket) {
        if (!waterStillThere) {
            return AfterFastWindow.SETTLED;
        }
        return hasEmptyBucket ? AfterFastWindow.DEFER_TO_CLEANUP : AfterFastWindow.ABANDON_NO_BUCKET;
    }

    /**
     * 清理窗口也到期了:这时无论什么原因都<b>必须</b>放手,否则一条反射能永久占住身体。
     * 收不回来的记一笔,让世界上的变化有据可查。
     */
    public static AfterCleanupWindow afterCleanupWindow(boolean waterStillThere) {
        return waterStillThere ? AfterCleanupWindow.ABANDON : AfterCleanupWindow.SETTLED;
    }

    /**
     * 被抢占停止时,已放下的东西该忘掉还是留账。
     *
     * <p>只在水真的还在时才留——水已经流走了还记着,就是给后续 tick 白干活。
     */
    public static boolean keepLedgerOnStop(boolean waterStillThere) {
        return waterStillThere;
    }
}
