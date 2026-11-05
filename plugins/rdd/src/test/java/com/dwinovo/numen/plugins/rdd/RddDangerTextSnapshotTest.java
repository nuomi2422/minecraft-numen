package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 042 R2 条件 3：快照新鲜度与"未知"标记的纯逻辑（无可测部分之外的东西）。
 */
class RddDangerTextSnapshotTest {

    @Test
    void freshWithinTtl() {
        assertTrue(RddDangerText.isFresh(100, 100, 20));
        assertTrue(RddDangerText.isFresh(120, 100, 20), "正好 TTL 边界仍算新鲜");
    }

    @Test
    void staleBeyondTtl() {
        assertFalse(RddDangerText.isFresh(121, 100, 20));
    }

    @Test
    void missingSnapshotIsNotFresh() {
        assertFalse(RddDangerText.isFresh(100, -1, 20), "没有快照（-1）永远不算新鲜");
    }

    @Test
    void clockGoingBackwardsIsNotFresh() {
        assertFalse(RddDangerText.isFresh(90, 100, 20), "时间倒退不能当成新鲜（防重启/换维度）");
    }

    @Test
    void unknownMarkerIsExplicitAndNonEmpty() {
        String m = RddDangerText.unknownMarker();
        assertFalse(m.isEmpty(), "未知必须有内容——空串会被读成'安全'");
        assertTrue(m.contains("未知"), m);
        assertTrue(m.contains("不等于安全"), m);
    }

    @Test
    void noDangerStillReturnsEmptyBlock() {
        // 与"未知"区分：真的没敌怪时仍是空串（不占 token），这是既有契约
        assertTrue(RddDangerText.block(0, 0, 100, "") .isEmpty());
    }
}
