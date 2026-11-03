package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02：expected_type 大小写不敏感（RECOVERY vs recovery）。 */
class RddDeclineSideTypeTest {

    @Test
    void caseInsensitiveMatch() {
        assertTrue(RddSideType.matches("RECOVERY", "recovery"));
        assertTrue(RddSideType.matches("recovery", "recovery"));
        assertTrue(RddSideType.matches(" Recovery ", "recovery"));
    }

    @Test
    void differentTypeStillMismatch() {
        assertFalse(RddSideType.matches("sleep", "recovery"));
        assertFalse(RddSideType.matches(null, "recovery"));
    }
}