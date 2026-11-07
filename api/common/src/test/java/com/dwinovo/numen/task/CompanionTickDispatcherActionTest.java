package com.dwinovo.numen.task;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-08: waiting tools are idle, action tools occupy the body. */
class CompanionTickDispatcherActionTest {
    @Test void waitingToolsAreIdle() {
        assertFalse(CompanionTickDispatcher.isActionTool("wait_timer"));
        assertFalse(CompanionTickDispatcher.isActionTool("timer_wait"));
        assertFalse(CompanionTickDispatcher.isActionTool("sleep"));
    }

    @Test void actionToolsOccupyBody() {
        assertTrue(CompanionTickDispatcher.isActionTool("goto"));
        assertTrue(CompanionTickDispatcher.isActionTool("farm_nearby"));
        assertTrue(CompanionTickDispatcher.isActionTool("mine"));
        assertTrue(CompanionTickDispatcher.isActionTool("attack"));
    }

    @Test void unknownToolIsActionByDefault() {
        assertTrue(CompanionTickDispatcher.isActionTool("collect_items"));
        assertFalse(CompanionTickDispatcher.isActionTool(""));
        assertFalse(CompanionTickDispatcher.isActionTool(null));
    }
}
