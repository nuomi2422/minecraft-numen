package com.dwinovo.numen.agent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolAliasesTest {

    @Test
    void mapsObservedShortName() {
        assertEquals("todowrite", ToolAliases.resolve("todo"));
        assertEquals("todowrite", ToolAliases.resolve(" TODO "));
    }

    @Test
    void leavesUnknownAlone() {
        assertEquals("goto", ToolAliases.resolve("goto"));
        assertEquals("mine", ToolAliases.resolve("mine"));
        assertEquals(null, ToolAliases.resolve(null));
    }
}
