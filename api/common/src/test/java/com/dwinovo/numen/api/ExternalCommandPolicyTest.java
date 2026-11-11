package com.dwinovo.numen.api;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalCommandPolicyTest {
    @Test void identityMustBeKnownOwnedAndMatchActualServer() {
        assertDoesNotThrow(() -> ExternalCommandPolicy.requireBound("w", "s", "w", "s", true));
        assertThrows(IllegalArgumentException.class, () -> ExternalCommandPolicy.requireBound("old", "s", "w", "s", true));
        assertThrows(IllegalArgumentException.class, () -> ExternalCommandPolicy.requireBound("w", "old", "w", "s", true));
        assertThrows(IllegalArgumentException.class, () -> ExternalCommandPolicy.requireBound("w", "s", "w", "s", false));
        assertThrows(IllegalArgumentException.class, () -> ExternalCommandPolicy.requireBound("w", "s", null, null, true));
    }
    @Test void oneCommandOnlyAndVanillaSlashIsNormalized() {
        assertEquals("time set day", ExternalCommandPolicy.command("/time set day"));
        assertEquals("spawnpoint @s 1 70 2", ExternalCommandPolicy.command("spawnpoint @s 1 70 2"));
        assertThrows(IllegalArgumentException.class, () -> ExternalCommandPolicy.command(""));
        assertThrows(IllegalArgumentException.class, () -> ExternalCommandPolicy.command("time set day\nclear @a"));
    }
}
