package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RddRationaleFreshTest {
    @Test void onlyExactChainStateIsFresh() {
        assertTrue(RddRationale.fresh(37, 37));
        assertFalse(RddRationale.fresh(36, 37), "older rationale must not be carried");
        assertFalse(RddRationale.fresh(38, 37), "future/racy rationale is not trusted");
        assertFalse(RddRationale.fresh(0, 37));
    }
}
