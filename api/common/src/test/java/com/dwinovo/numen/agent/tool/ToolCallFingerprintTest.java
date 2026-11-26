package com.dwinovo.numen.agent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallFingerprintTest {
    @Test void recursivelySortsObjectKeysButPreservesArrayOrder() {
        String a = "{\"z\":[{\"b\":2,\"a\":1},3],\"a\":{\"y\":2,\"x\":1}}";
        String b = "{ \"a\":{\"x\":1,\"y\":2}, \"z\":[{\"a\":1,\"b\":2},3] }";
        assertEquals("{\"a\":{\"x\":1,\"y\":2},\"z\":[{\"a\":1,\"b\":2},3]}", ToolCallFingerprint.canonicalArguments(a));
        assertEquals(ToolCallFingerprint.hashArguments(a), ToolCallFingerprint.hashArguments(b));
        assertNotEquals(ToolCallFingerprint.hashArguments(a),
                ToolCallFingerprint.hashArguments("{\"a\":{\"x\":1,\"y\":2},\"z\":[3,{\"a\":1,\"b\":2}]}"));
    }

    @Test void coordinatesTargetsAndQuantitiesAreNotNormalizedAway() {
        String base = "{\"x\":10,\"y\":64,\"z\":-2,\"target\":\"stone\",\"count\":1}";
        for (String changed : new String[]{
                "{\"x\":11,\"y\":64,\"z\":-2,\"target\":\"stone\",\"count\":1}",
                "{\"x\":10,\"y\":64,\"z\":-2,\"target\":\"dirt\",\"count\":1}",
                "{\"x\":10,\"y\":64,\"z\":-2,\"target\":\"stone\",\"count\":2}"}) {
            assertNotEquals(ToolCallFingerprint.hashArguments(base), ToolCallFingerprint.hashArguments(changed));
        }
    }

    @Test void invalidNullOrNonObjectArgumentsAreUnknown() {
        for (String value : new String[]{null, "", " ", "{", "[]", "null", "42"}) {
            assertNull(ToolCallFingerprint.canonicalArguments(value));
            assertNull(ToolCallFingerprint.hashArguments(value));
            assertNull(ToolCallFingerprint.action("tool", value));
        }
        assertNull(ToolCallFingerprint.action(" ", "{}"));
    }

    @Test void visibleTextDigestIsExactBoundedAndNeverStoresRawText() {
        assertEquals(ToolCallFingerprint.hashText("same visible answer"),
                ToolCallFingerprint.hashText("same visible answer"));
        assertNotEquals(ToolCallFingerprint.hashText("same visible answer"),
                ToolCallFingerprint.hashText("same visible answer "));
        assertNull(ToolCallFingerprint.hashText(null));
        assertNull(ToolCallFingerprint.hashText(" \n"));
        assertEquals(64, ToolCallFingerprint.hashText("private visible text").length());
    }

    @Test void digestDoesNotContainRawArgumentsAndToolNameIsPartOfIdentity() {
        String secret = "{\"selection_id\":\"secret-selection-42\",\"block\":\"stone\"}";
        String hash = ToolCallFingerprint.hashArguments(secret);
        assertEquals(64, hash.length());
        assertFalse(hash.contains("secret"));
        assertNotEquals(ToolCallFingerprint.action("a", secret), ToolCallFingerprint.action("b", secret));
    }
}
