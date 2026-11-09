package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SideContextDecoratorTest {
    private static final String BLOCK = "<rdd><enabled>true</enabled><active>true</active><side_task>old</side_task></rdd>";

    @Test void sameIdentityKeepsBlockAndMarksCurrent() {
        String out = SideContextDecorator.decorate(BLOCK, "w", "s", "minecraft:overworld", 42, "w", "s");
        assertTrue(out.contains("<side_task>old</side_task>"));
        assertTrue(out.contains("<side_context_status>CURRENT</side_context_status>"));
    }

    @Test void worldOrSessionMismatchOrMissingLeaksNothing() {
        for (String out : new String[]{
                SideContextDecorator.decorate(BLOCK, "w1", "s1", "d", 42, "w2", "s1"),
                SideContextDecorator.decorate(BLOCK, "w1", "s1", "d", 42, "w1", "s2"),
                SideContextDecorator.decorate(BLOCK, "w1", "s1", "d", 42, null, null),
                SideContextDecorator.decorate(BLOCK, null, null, "d", 42, "w1", "s1"),
                SideContextDecorator.decorate(null, "w1", "s1", "d", 42, "w1", "s1")}) {
            assertFalse(out.contains("<side_task>old</side_task>"));
            assertTrue(out.contains("<side_context_status>UNKNOWN</side_context_status>"));
            assertTrue(out.contains("<active>false</active>"));
        }
    }
}
