package com.dwinovo.numen.core.task.move;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 169 实机：goto 只给 y 时不能被 0,0 占位坐标带偏。 */
class MoveToTaskRecordTest {

    @Test
    void yOnlyIsElevationMove() {
        MoveToTaskRecord r = new MoveToTaskRecord("t1", 0L, null, 65d, null, null, false);
        assertEquals(MoveToTaskRecord.Kind.YLEVEL, r.kind);
    }

    @Test
    void xPlusZIsColumn() {
        MoveToTaskRecord r = new MoveToTaskRecord("t2", 0L, 371d, null, 715d, null, false);
        assertEquals(MoveToTaskRecord.Kind.COLUMN, r.kind);
    }

    @Test
    void zeroZeroWithYIsRejectedAsPlaceholder() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new MoveToTaskRecord("t3", 0L, 0d, 65d, 0d, null, false));
        assertTrue(ex.getMessage().contains("placeholder"), ex.getMessage());
    }
}
