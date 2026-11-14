package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.provider.LlmToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-15: one restore pass must produce the same two views the loop used to read separately.
 * Assertions state the expected shape explicitly (the new method is never compared with itself).
 */
class ConvoLogRestoreTest {

    private static ConvoLog write(Path dir, String... lines) throws IOException {
        Path file = dir.resolve("chat.jsonl");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return ConvoLog.atFile(file);
    }

    private static final String U1 = "{\"role\":\"user\",\"content\":\"u1\"}";
    private static final String A1 = "{\"role\":\"assistant\",\"content\":\"a1\"}";
    private static final String T1 = "{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"r1\"}";
    private static final String U2 = "{\"role\":\"user\",\"content\":\"u2\"}";
    private static final String A2 = "{\"role\":\"assistant\",\"content\":\"a2\"}";
    private static final String U3 = "{\"role\":\"user\",\"content\":\"u3\"}";

    @Test
    void historyWalksBackToUserAndMayExceedLimit(@TempDir Path dir) throws IOException {
        ConvoLog.Restore restored = write(dir, U1, A1, T1, U2, A2, U3).loadForRestore(2);

        // limit=2 starts at index 4 (assistant), so the model tail grows back to the nearest user.
        assertEquals(3, restored.history().size(), "user boundary may exceed the limit");
        assertEquals("u2", ((ConvoState.Msg.User) restored.history().get(0)).content());
        assertInstanceOf(ConvoState.Msg.Assistant.class, restored.history().get(1));
        assertEquals("u3", ((ConvoState.Msg.User) restored.history().get(2)).content());
    }

    @Test
    void displayIsPhysicalTailWithoutUserWalk(@TempDir Path dir) throws IOException {
        ConvoLog.Restore restored = write(dir, U1, A1, T1, U2, A2, U3).loadForRestore(2);

        assertEquals(2, restored.display().size());
        assertInstanceOf(ConvoState.Msg.Assistant.class, restored.display().get(0));
        assertEquals("u3", ((ConvoState.Msg.User) restored.display().get(1)).content());
    }

    @Test
    void compactRestartsHistoryAndDrawsDividerInDisplay(@TempDir Path dir) throws IOException {
        String compact = "{\"type\":\"compact\",\"content\":\"SUMMARY\","
                + "\"preserved\":[{\"role\":\"user\",\"content\":\"kept\"}]}";

        ConvoLog.Restore restored = write(dir, U1, compact, U2).loadForRestore(100);

        assertEquals(3, restored.history().size());
        assertEquals("SUMMARY", ((ConvoState.Msg.User) restored.history().get(0)).content());
        assertEquals("kept", ((ConvoState.Msg.User) restored.history().get(1)).content());
        assertEquals("u2", ((ConvoState.Msg.User) restored.history().get(2)).content());
        assertEquals(3, restored.display().size(), "display keeps the physical order");
        assertEquals(ConvoLog.COMPACT_DIVIDER,
                ((ConvoState.Msg.User) restored.display().get(1)).content());
    }

    @Test
    void clearWipesHistoryButKeepsPhysicalTranscript(@TempDir Path dir) throws IOException {
        String clear = "{\"type\":\"clear\",\"ts\":1}";

        ConvoLog.Restore restored = write(dir, U1, clear, U2).loadForRestore(100);

        assertEquals(1, restored.history().size(), "clear is a blank slate for the model");
        assertEquals("u2", ((ConvoState.Msg.User) restored.history().get(0)).content());
        assertEquals(3, restored.display().size(), "the GUI transcript loses nothing");
        assertEquals(ConvoLog.CLEAR_DIVIDER,
                ((ConvoState.Msg.User) restored.display().get(1)).content());
    }

    @Test
    void personaChangeIsDisplayOnly(@TempDir Path dir) throws IOException {
        String persona = "{\"type\":\"persona-change\",\"id\":\"p2\"}";

        ConvoLog.Restore restored = write(dir, U1, persona, U2).loadForRestore(100);

        assertEquals(2, restored.history().size(), "persona change is not a conversation message");
        assertEquals(3, restored.display().size());
        assertEquals(ConvoLog.PERSONA_DIVIDER,
                ((ConvoState.Msg.User) restored.display().get(1)).content());
    }

    @Test
    void malformedAndUnknownRecordsAreSkippedInBothViews(@TempDir Path dir) throws IOException {
        ConvoLog.Restore restored = write(dir,
                U1, "not-json", "{\"role\":\"robot\",\"content\":\"x\"}", A1).loadForRestore(100);

        assertEquals(2, restored.history().size());
        assertEquals(2, restored.display().size());
        assertInstanceOf(ConvoState.Msg.User.class, restored.history().get(0));
        assertInstanceOf(ConvoState.Msg.Assistant.class, restored.history().get(1));
    }

    @Test
    void poisonToolArgumentsAreNormalisedOnLoad(@TempDir Path dir) throws IOException {
        // 与 ConvoLogPoisonTest 同一契约：坏参数归一成 NO_ARGS，id / 正文 / extras 原样保留。
        String poison = "{\"role\":\"assistant\",\"content\":\"我这就去\",\"extras\":{\"k\":\"v\"},"
                + "\"tool_calls\":[{\"id\":\"c1\",\"name\":\"goto\",\"arguments\":\"{\\\"x\\\": \"}]}";

        ConvoLog.Restore restored = write(dir, poison).loadForRestore(100);

        var assistant = assertInstanceOf(ConvoState.Msg.Assistant.class, restored.history().get(0));
        LlmToolCall call = assistant.turn().toolCalls().get(0);
        assertEquals(LlmToolCall.NO_ARGS, call.arguments(), "坏参数按既有契约归一，不逐字保留");
        assertEquals("c1", call.id(), "id 必须原样留着配对 tool_call_id");
        assertEquals("我这就去", assistant.turn().content());
        assertEquals("v", assistant.turn().extras().get("k").getAsString());
        assertEquals(1, restored.display().size());
    }

    @Test
    void missingFileYieldsEmptyViews(@TempDir Path dir) {
        ConvoLog.Restore restored = ConvoLog.atFile(dir.resolve("absent.jsonl")).loadForRestore(200);

        assertTrue(restored.history().isEmpty());
        assertTrue(restored.display().isEmpty());
    }

    @Test
    void displayNeverDecodesPreservedWhileHistoryFailsClosed(@TempDir Path dir) throws IOException {
        // 旧 loadDisplay 只把 compact 画成分隔线，从不 decode preserved：历史脏数据不能连带弄爆 GUI。
        String compact = "{\"type\":\"compact\",\"content\":\"S\",\"preserved\":[42]}";
        ConvoLog log = write(dir, U1, compact, U2);

        List<ConvoState.Msg> shown = log.loadDisplay(100);
        assertEquals(3, shown.size());
        assertEquals(ConvoLog.COMPACT_DIVIDER, ((ConvoState.Msg.User) shown.get(1)).content());

        // load 仍按旧口径在坏 preserved 上抛同样异常：不吞、不洗数据。
        assertThrows(IllegalStateException.class, () -> log.load(100));
        assertThrows(IllegalStateException.class, () -> log.loadForRestore(100));
    }

    @Test
    void unknownEventTypeIsSkippedByBothViews(@TempDir Path dir) throws IOException {
        String future = "{\"type\":\"future-event\",\"x\":1}";

        ConvoLog.Restore restored = write(dir, U1, future, A1).loadForRestore(100);

        assertEquals(2, restored.history().size());
        assertEquals(2, restored.display().size());
    }

    @Test
    void legacyRoleCompactIsReadAsACompactEvent(@TempDir Path dir) throws IOException {
        String legacy = "{\"role\":\"compact\",\"content\":\"OLD-SUMMARY\"}";

        ConvoLog.Restore restored = write(dir, U1, legacy, U2).loadForRestore(100);

        assertEquals(2, restored.history().size());
        assertEquals("OLD-SUMMARY", ((ConvoState.Msg.User) restored.history().get(0)).content());
        assertEquals(3, restored.display().size());
        assertEquals(ConvoLog.COMPACT_DIVIDER,
                ((ConvoState.Msg.User) restored.display().get(1)).content());
    }

    @Test
    void nonRegularPathYieldsEmptyViews(@TempDir Path dir) {
        ConvoLog.Restore restored = ConvoLog.atFile(dir).loadForRestore(100);

        assertTrue(restored.history().isEmpty());
        assertTrue(restored.display().isEmpty());
    }

    /**
     * Performance counter-example over a ~70MB synthetic transcript (the live companion file was
     * 71.8MB). Elapsed times are informational only; no wall-clock assertion.
     */
    @Test
    void onePassRestoresBothViewsFromLargeTranscript(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("big.jsonl");
        String payload = "x".repeat(50_000);
        try (var w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (int i = 0; i < 1_400; i++) {
                w.write("{\"role\":\"user\",\"content\":\"" + payload + "\"}\n");
                w.write("{\"role\":\"assistant\",\"content\":\"ok\"}\n");
            }
        }
        ConvoLog log = ConvoLog.atFile(file);

        long t0 = System.nanoTime();
        List<ConvoState.Msg> oldHistory = log.load(200);
        List<ConvoState.Msg> oldDisplay = log.loadDisplay(200);
        long oldMs = (System.nanoTime() - t0) / 1_000_000L;

        long t1 = System.nanoTime();
        ConvoLog.Restore restored = log.loadForRestore(200);
        long newMs = (System.nanoTime() - t1) / 1_000_000L;

        // compat-two-calls = 两次兼容 API 调用（各自只读一个视图），不是旧 readAllLines 双遍实现的基准。
        System.out.println("[numen-convo-perf] bytes=" + Files.size(file)
                + " compat-two-calls=" + oldMs + "ms single-restore=" + newMs + "ms");

        assertEquals(200, oldHistory.size());
        assertEquals(200, oldDisplay.size());
        assertEquals(200, restored.history().size());
        assertEquals(200, restored.display().size());
        assertInstanceOf(ConvoState.Msg.User.class, restored.display().get(0));
    }
}
