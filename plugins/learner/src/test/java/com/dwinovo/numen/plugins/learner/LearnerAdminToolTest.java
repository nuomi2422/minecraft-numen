package com.dwinovo.numen.plugins.learner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearnerAdminToolTest {

    @Test void rejectsMissingFields() {
        assertTrue(LearnerAdminTool.validate("retire", "sk-1", "why", true) == null);
        assertEquals("learner_admin: action must be 'retire'", LearnerAdminTool.validate("add", "sk-1", "why", true));
        assertEquals("learner_admin: skillId is required", LearnerAdminTool.validate("retire", " ", "why", true));
        assertEquals("learner_admin: reason is required", LearnerAdminTool.validate("retire", "sk-1", "", true));
        assertEquals("learner_admin: confirm=true is required", LearnerAdminTool.validate("retire", "sk-1", "why", false));
        assertNull(LearnerAdminTool.validate("retire", "sk-1", "why", true));
    }
}
