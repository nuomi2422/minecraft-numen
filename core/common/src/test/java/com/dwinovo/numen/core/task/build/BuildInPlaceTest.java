package com.dwinovo.numen.core.task.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code in_place} 闸门判据（游戏外可测：只依赖四个整数）。
 *
 * <p>它守的是「人必须先在自己挖的坑里，才允许就地封顶」这条不变量里最容易被写错的一半：
 * 只看 Y 变小是不够的——身体被挤到隔壁一列、或者还悬在半空，Y 都可能合格。
 * 所以这里把判据钉死成三件事：同列、脚的高度、目标格不压身体。</p>
 */
class BuildInPlaceTest {

    /** 1x1 竖井：脚在 y=61，头在 y=62，列 (8,8)。 */
    private static final BuildInPlace PIT = new BuildInPlace(8, 8, 61, 62);

    @Test
    void bodyAtTheAnchorIsAccepted() {
        assertTrue(PIT.anchorsBodyAt(8.5, 61.0, 8.5));
        assertTrue(PIT.anchorsBodyAt(8.0, 61.2, 8.99), "±0.2 容差内仍算在坑底");
    }

    @Test
    void bodyInAnotherColumnIsRejected() {
        assertFalse(PIT.anchorsBodyAt(9.5, 61.0, 8.5), "深度对但列不对 —— 旧写法只看 Y 会放过");
        assertFalse(PIT.anchorsBodyAt(8.5, 61.0, 7.5));
    }

    @Test
    void bodyNotAtThePitFloorIsRejected() {
        assertFalse(PIT.anchorsBodyAt(8.5, 62.0, 8.5), "还没落到底");
        assertFalse(PIT.anchorsBodyAt(8.5, 64.0, 8.5), "根本没下坑");
        assertFalse(PIT.anchorsBodyAt(8.5, 60.5, 8.5), "掉过头了");
    }

    @Test
    void targetInsideTheBodyCellsIsRejected() {
        assertTrue(PIT.clipsBodyCell(8, 61, 8), "脚格不能放");
        assertTrue(PIT.clipsBodyCell(8, 62, 8), "头格不能放（会把人封在里面）");
        assertFalse(PIT.clipsBodyCell(8, 63, 8), "头顶上一层就是封盖那一格，合法");
        assertFalse(PIT.clipsBodyCell(7, 61, 8), "隔壁格不压身体");
        assertFalse(PIT.clipsBodyCell(8, 61, 9));
    }

    @Test
    void bodyCellsAreExactlyTwo() {
        assertTrue(PIT.isBodyCell(61));
        assertTrue(PIT.isBodyCell(62));
        assertFalse(PIT.isBodyCell(63));
        assertFalse(PIT.isBodyCell(60));
    }
}
