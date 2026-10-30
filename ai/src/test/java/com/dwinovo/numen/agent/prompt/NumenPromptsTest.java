package com.dwinovo.numen.agent.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NumenPromptsTest {
    @Test
    void internalRddContextIsPrivateByDefault() {
        String prompt = NumenPrompts.ENTITY_PROMPT;
        assertTrue(prompt.contains("private control context"));
        assertTrue(prompt.contains("Never repeat it in ordinary companion chat"));
    }

    @Test
    void survivalCoreDefinesLifePreservationWithoutTurningIntoRefusal() {
        String prompt = NumenPrompts.ENTITY_PROMPT;
        assertTrue(prompt.contains("<survival_core>"));
        assertTrue(prompt.contains("fear death"));
        assertTrue(prompt.contains("safe, reversible, efficient"));
        assertTrue(prompt.contains("sleep when night makes travel unsafe"));
        assertTrue(prompt.contains("Never treat death as a teleport"));
        assertTrue(prompt.contains("make progress whenever it can be done safely"));
    }

    @Test
    void bodyStatusPromptMatchesLiveInventoryContract() {
        String prompt = NumenPrompts.ENTITY_PROMPT;
        assertTrue(prompt.contains("live backpack is in the request's <inventory> state block"));
        assertTrue(prompt.contains("inspect_gui when exact slots matter"));
        assertTrue(prompt.contains("does NOT list your backpack") == false,
                "the core prompt must not resurrect the removed full-inventory claim");
    }
}
