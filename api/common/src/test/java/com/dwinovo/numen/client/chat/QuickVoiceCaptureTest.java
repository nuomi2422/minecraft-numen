package com.dwinovo.numen.client.chat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the callback ownership consumer used by partial/final/status, not a copy of the rule. */
class QuickVoiceCaptureTest {
    @Test void callbacksFromOldCaptureCannotMutateNewCapture() {
        var old = QuickVoice.beginCapture(null, null);
        var fresh = QuickVoice.beginCapture(null, null);
        int[] mutations = {0};
        assertFalse(QuickVoice.updateCapture(old, () -> mutations[0]++), "late partial");
        assertFalse(QuickVoice.updateCapture(old, () -> mutations[0]++), "late final");
        assertFalse(QuickVoice.updateCapture(old, () -> mutations[0]++), "late status");
        assertEquals(0, mutations[0]);
        assertTrue(QuickVoice.updateCapture(fresh, () -> mutations[0]++));
        assertEquals(1, mutations[0]);
        QuickVoice.clear();
        assertFalse(QuickVoice.updateCapture(fresh, () -> mutations[0]++));
    }
}
