package com.dwinovo.numen.core.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectStorageTextTest {

    @Test void airMessageInvalidatesMemoryCoordinate() {
        String text = InspectStorageText.airAt("360,66,636");
        assertTrue(text.contains("is air"));
        assertTrue(text.contains("memory coordinate is dead"));
        assertTrue(text.contains("do not retry"));
    }
}
