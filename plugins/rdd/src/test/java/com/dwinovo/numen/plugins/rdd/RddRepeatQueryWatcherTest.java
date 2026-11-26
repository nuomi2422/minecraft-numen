package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic repeat evidence, bounded state and one successful system reminder. */
class RddRepeatQueryWatcherTest {
    private static final UUID ID = UUID.fromString("e502d17b-de15-4e5e-9864-9e97b0702546");
    private static final RddRepeatQueryWatcher.Context RUNNING =
            new RddRepeatQueryWatcher.Context("chain|primary|subtask|1|0", true);
    private final List<RddRepeatQueryWatcher.Repeat> sent = new ArrayList<>();
    private boolean accept = true;

    @BeforeEach void reset() {
        RddRepeatQueryWatcher.install(null);
        RddRepeatQueryWatcher.installNotifier((id, repeat) -> {
            if (accept) sent.add(repeat);
            return accept;
        });
    }

    @Test void mcpMonitorCallsAreNotCompanionRepeats() {
        assertTrue(RddRepeatQueryWatcher.isExternalCall("mcp-122"));
        assertFalse(RddRepeatQueryWatcher.isExternalCall("call_00_abc"));
        assertFalse(RddRepeatQueryWatcher.isExternalCall(null));
        for (int i = 0; i < 4; i++) {
            assertFalse(observe("mcp-" + i, "rdd_get_inventory", "{}", i * 10));
        }
        assertTrue(sent.isEmpty());
    }

    @Test void lengthOnlyLegacyHookIsSilentRatherThanGuessing() {
        assertFalse(RddRepeatQueryWatcher.observe(ID, "rdd_get_inventory", 2, 1000L));
        assertFalse(RddRepeatQueryWatcher.observe(ID, "rdd_get_inventory", 2, 1010L));
        assertFalse(RddRepeatQueryWatcher.observe(ID, "rdd_get_inventory", 2, 1020L));
        assertTrue(sent.isEmpty());
    }

    @Test void productionInstallsRawArgumentsInsteadOfLengthOnlyObservation() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(
                "plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddPlugin.java"))) {
            root = root.getParent();
        }
        assertNotNull(root, "source root missing");
        String source = Files.readString(root.resolve(
                "plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddPlugin.java"));
        assertTrue(source.contains("ToolCallObserver.installRawArguments("));
        assertTrue(source.contains("RddRepeatQueryWatcher.observe(companionId, toolCallId, toolName, argsJson, tick)"));
        assertFalse(source.contains("RddRepeatQueryWatcher.observe(companionId, toolName, argsChars, tick)"));
    }

    @Test void stableNestedKeyOrderNotifiesOnlyOnThirdEqualAction() {
        String a = "{\"target\":{\"x\":12,\"y\":64,\"z\":-7},\"count\":3,\"item\":\"minecraft:stone\"}";
        String b = "{ \"item\":\"minecraft:stone\", \"count\":3, \"target\":{\"z\":-7,\"y\":64,\"x\":12} }";
        assertFalse(observe("a", "scan_blocks", a, 1000));
        assertFalse(observe("b", "scan_blocks", b, 1010));
        assertTrue(observe("c", "scan_blocks", a, 1020));
        assertEquals(1, sent.size());
        assertEquals("same_action", sent.get(0).kind());
        assertEquals(3, sent.get(0).count());
        assertEquals(64, sent.get(0).actionHash().length(), "only a digest is retained");
    }

    @Test void coordinatesTargetsQuantitiesAndArrayOrderRemainSemantic() {
        String base = "{\"target\":{\"x\":1,\"y\":2,\"z\":3},\"count\":4,\"items\":[\"a\",\"b\"]}";
        for (String args : List.of(
                "{\"target\":{\"x\":2,\"y\":2,\"z\":3},\"count\":4,\"items\":[\"a\",\"b\"]}",
                "{\"target\":{\"x\":1,\"y\":2,\"z\":3},\"count\":5,\"items\":[\"a\",\"b\"]}",
                "{\"target\":{\"x\":1,\"y\":2,\"z\":3},\"count\":4,\"items\":[\"b\",\"a\"]}",
                "{\"target\":{\"x\":1,\"y\":2,\"z\":3},\"count\":4,\"items\":[\"a\",\"c\"]}")) {
            RddRepeatQueryWatcher.clear(ID);
            assertFalse(observe("base", "collect_items", base, 0));
            assertFalse(observe("changed", "collect_items", args, 10));
            assertFalse(observe("base-again", "collect_items", base, 20));
        }
        assertTrue(sent.isEmpty());
    }

    @Test void twoRoundShortSequencesAreMergedButMereSetsAreNot() {
        assertFalse(observe("1", "scan_blocks", "{\"x\":1}", 1000));
        assertFalse(observe("2", "rdd_get_inventory", "{}", 1010));
        assertFalse(observe("3", "scan_blocks", "{\"x\":1}", 1020));
        assertTrue(observe("4", "rdd_get_inventory", "{}", 1030));
        assertEquals("tool_sequence", sent.get(0).kind());
        assertEquals(2, sent.get(0).sequenceLength());
        assertEquals(2, sent.get(0).count());

        RddRepeatQueryWatcher.clear(ID); sent.clear();
        for (int i = 0; i < 6; i++) {
            boolean result = observe("s" + i, "tool_" + (i % 3), "{\"n\":" + (i % 3) + "}", 2000 + i);
            assertEquals(i == 5, result, "ABCABC is a three-action sequence");
        }
        assertEquals(3, sent.get(0).sequenceLength());
    }

    @Test void interleavedOrReorderedToolsDoNotCreateASequence() {
        int[] order = {0, 1, 1, 0};
        for (int i = 0; i < order.length; i++) {
            assertFalse(observe("i" + i, "tool_" + order[i], "{}", i * 10));
        }
        assertTrue(sent.isEmpty());
    }

    @Test void sameIdentityGetsOneSuccessfulReminderAndNormalContinuationChance() {
        for (int i = 0; i < 3; i++) assertTrue(observe("a" + i, "rdd_status", "{}", i) == (i == 2));
        for (int i = 3; i < 20; i++) assertFalse(observe("a" + i, "rdd_status", "{}", i));
        assertEquals(1, sent.size());
        RddRepeatQueryWatcher.clear(ID);
        assertFalse(observe("b0", "rdd_status", "{}", 30));
        assertFalse(observe("b1", "rdd_status", "{}", 31));
        assertTrue(observe("b2", "rdd_status", "{}", 32), "real progress starts a new episode");
        assertEquals(2, sent.size());
    }

    @Test void rejectedOrThrottledDeliveryIsNotRecordedAsSuccess() {
        accept = false;
        for (int i = 0; i < 4; i++) assertFalse(observe("a" + i, "rdd_status", "{}", i));
        accept = true;
        assertTrue(observe("a4", "rdd_status", "{}", 4));
        assertFalse(observe("a5", "rdd_status", "{}", 5));
        assertEquals(1, sent.size());
    }

    @Test void duplicateCallIdFromMultipleLayersCountsOnce() {
        assertFalse(observe("same", "rdd_status", "{}", 1));
        assertFalse(observe("same", "rdd_status", "{}", 2));
        assertFalse(observe("other", "rdd_status", "{}", 3));
        assertTrue(observe("third", "rdd_status", "{}", 4));
        assertEquals(1, sent.size());
    }

    @Test void expirationAndClockRollbackStartFresh() {
        assertFalse(observe("a", "rdd_status", "{}", 1000));
        assertFalse(observe("b", "rdd_status", "{}", 1010));
        assertFalse(observe("c", "rdd_status", "{}", 1000 + RddRepeatQueryWatcher.WINDOW_TICKS + 1));
        assertFalse(observe("d", "rdd_status", "{}", 2202));
        assertFalse(observe("e", "rdd_status", "{}", 2210));
        assertTrue(observe("f", "rdd_status", "{}", 2211));

        RddRepeatQueryWatcher.clear(ID); sent.clear();
        assertFalse(observe("g", "rdd_status", "{}", 3000));
        assertFalse(observe("h", "rdd_status", "{}", 3010));
        assertFalse(observe("i", "rdd_status", "{}", 1), "rollback cannot join an old window");
        assertFalse(observe("j", "rdd_status", "{}", 2));
        assertTrue(observe("k", "rdd_status", "{}", 3));
    }

    @Test void pauseOrIneligibleSampleDiscardsOldEpisode() {
        assertFalse(observe("a", "rdd_status", "{}", 1));
        assertFalse(observe("b", "rdd_status", "{}", 2));
        assertFalse(observe("paused", "rdd_status", "{}", 3, RddRepeatQueryWatcher.Context.INELIGIBLE));
        assertFalse(observe("c", "rdd_status", "{}", 4));
        assertFalse(observe("d", "rdd_status", "{}", 5));
        assertTrue(sent.isEmpty());
    }

    @Test void newTaskIdentityDoesNotInheritOldCountsOrReminder() {
        assertFalse(observe("a", "rdd_status", "{}", 1));
        assertFalse(observe("b", "rdd_status", "{}", 2));
        var next = new RddRepeatQueryWatcher.Context("chain|primary|new-subtask|1|0", true);
        assertFalse(observe("c", "rdd_status", "{}", 3, next));
        assertFalse(observe("d", "rdd_status", "{}", 4, next));
        assertTrue(observe("e", "rdd_status", "{}", 5, next));
        assertEquals(1, sent.size());
    }

    @Test void malformedArgumentsAreUnknownAndNeverRepeatEvidence() {
        for (int i = 0; i < 5; i++) assertFalse(observe("bad" + i, "rdd_status", "{\"x\":", i));
        assertTrue(sent.isEmpty());
    }

    @Test void identicalVisibleZeroToolResponsesNotifyOnlyOnThirdSettledToken() {
        assertFalse(visible(1, "execution", "stop", 0, "same answer", 1000));
        assertFalse(visible(2, "execution", "stop", 0, "same answer", 1010));
        assertTrue(visible(3, "execution", "stop", 0, "same answer", 1020));
        assertEquals("same_visible_text", sent.get(0).kind());
        assertEquals(3, sent.get(0).count());
        assertEquals(64, sent.get(0).actionHash().length());
        assertFalse(visible(4, "execution", "stop", 0, "same answer", 1030));
        assertEquals(1, sent.size());
    }

    @Test void duplicateTokenChunkLikeReportsAndHiddenOnlyTextAreNotEvidence() {
        assertFalse(visible(7, "execution", "stop", 0, "same answer", 1));
        assertFalse(visible(7, "execution", "stop", 0, "same answer", 2));
        assertFalse(visible(8, "execution", "stop", 0, "same answer", 3));
        assertTrue(sent.isEmpty(), "the same settled response cannot be counted as chunks");
        RddRepeatQueryWatcher.clear(ID);
        for (int i = 0; i < 5; i++) {
            assertFalse(visible(20 + i, "execution", "stop", 0, " \n", 10 + i));
        }
        assertTrue(sent.isEmpty(), "blank visible content is not a repeated answer");
    }

    @Test void blankVisibleResponseBreaksConsecutiveTextEvidence() {
        assertFalse(visible(1, "execution", "stop", 0, "same answer", 1));
        assertFalse(visible(2, "execution", "stop", 0, " \n", 2));
        assertFalse(visible(3, "execution", "stop", 0, "same answer", 3));
        assertFalse(visible(4, "execution", "stop", 0, "same answer", 4));
        assertTrue(sent.isEmpty(), "blank content is a new response, not a link in the same-text streak");
        assertTrue(visible(5, "execution", "stop", 0, "same answer", 5));
        assertEquals(3, sent.get(0).count());
    }

    @Test void visibleRepeatRequiresExecutorStopAndZeroTools() {
        assertFalse(visible(1, "supervisor", "stop", 0, "same", 1));
        assertFalse(visible(2, "execution", "length", 0, "same", 2));
        assertFalse(visible(3, "execution", "stop", 1, "same", 3));
        assertFalse(visible(4, "execution", "stop", -1, "same", 4));
        assertTrue(sent.isEmpty());
    }

    @Test void differentVisibleTextOrRealToolCallStartsANewEpisode() {
        assertFalse(visible(1, "execution", "stop", 0, "answer A", 1));
        assertFalse(visible(2, "execution", "stop", 0, "answer B", 2));
        assertFalse(visible(3, "execution", "stop", 0, "answer A", 3));
        assertTrue(sent.isEmpty(), "non-consecutive identical text is not this signal");
        assertFalse(observe("tool-call", "rdd_status", "{}", 4));
        assertFalse(visible(4, "execution", "stop", 0, "answer A", 5));
        assertFalse(visible(5, "execution", "stop", 0, "answer A", 6));
        assertTrue(visible(6, "execution", "stop", 0, "answer A", 7));
    }

    @Test void visibleRepeatExpiresAndTaskIdentityDoesNotLeak() {
        assertFalse(visible(1, "execution", "stop", 0, "same", 1000));
        assertFalse(visible(2, "execution", "stop", 0, "same", 1010));
        assertFalse(visible(3, "execution", "stop", 0, "same", 1000 + RddRepeatQueryWatcher.WINDOW_TICKS + 1));
        assertFalse(visible(4, "execution", "stop", 0, "same", 1800));
        assertTrue(visible(5, "execution", "stop", 0, "same", 2201));

        RddRepeatQueryWatcher.clear(ID); sent.clear();
        var next = new RddRepeatQueryWatcher.Context("chain|primary|new|1|0", true);
        assertFalse(visible(6, "execution", "stop", 0, "same", 3000, next));
        assertFalse(visible(7, "execution", "stop", 0, "same", 3010, next));
        assertTrue(visible(8, "execution", "stop", 0, "same", 3020, next));
    }

    @Test void productionSettlePassesOnlyAssistantVisibleContent() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("ai/src/main/java"))) root = root.getParent();
        assertNotNull(root);
        String client = Files.readString(root.resolve("ai/src/main/java/com/dwinovo/numen/agent/llm/NumenLlmClient.java"));
        assertTrue(client.contains("turn.toolCalls().size(), turn.content()"),
                "the settled signal must use finalized visible assistant content");
        assertFalse(client.contains("turn.toolCalls().size(), turn.reasoning()"),
                "hidden reasoning must never enter repeat supervision");
    }

    private boolean visible(long token, String phase, String finish, int tools, String text, long tick) {
        return visible(token, phase, finish, tools, text, tick, RUNNING);
    }
    private boolean visible(long token, String phase, String finish, int tools, String text, long tick,
                            RddRepeatQueryWatcher.Context context) {
        return RddRepeatQueryWatcher.observeVisible(ID, token, phase, finish, tools, text, tick, context);
    }

    private boolean observe(String callId, String tool, String args, long tick) {
        return RddRepeatQueryWatcher.observe(ID, callId, tool, args, tick, RUNNING);
    }
    private boolean observe(String callId, String tool, String args, long tick, RddRepeatQueryWatcher.Context context) {
        return RddRepeatQueryWatcher.observe(ID, callId, tool, args, tick, context);
    }
}
