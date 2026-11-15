package com.dwinovo.numen.core.tools.agent;

import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.DeepSeekProvider;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.agent.tool.ToolAnchor;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.api.LearnedSkillBodyResolver;
import com.dwinovo.numen.core.tools.AgentOps;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real ToolCall -> AgentOps -> supplied reader; production defaults to the existing capability lookup. */
class LoadSkillToolReferenceTest {
    private static final String REF = "skill:sk-food@v7";
    private static final UUID COMPANION = UUID.fromString("00000000-0000-0000-0000-000000000017");
    @TempDir Path dir;
    private final AtomicReference<LearnedSkillBodyResolver> reader = new AtomicReference<>();
    private final LoadSkillTool tool = new LoadSkillTool(new AgentOps(reader::get));
    private boolean scannedDirectory;

    @AfterEach
    void restoreDirectoryRoot() {
        if (scannedDirectory) SkillRegistry.instance().scan(null);
    }

    private String invoke(String args, ToolAnchor anchor) {
        var reply = new AtomicReference<String>();
        tool.invoke(new ToolCall("explicit-food-read", tool.name(), args, anchor, reply::set));
        assertNotNull(reply.get(), "the actual tool must settle the call");
        return reply.get();
    }

    private void reader(LearnedSkillBodyResolver resolver) {
        reader.set(resolver);
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaAllowsRefOnlyAndDoesNotExposeCallerIdentityOrView() {
        var schema = tool.parameterSchema();
        var props = (Map<String, Object>) schema.get("properties");
        assertEquals(java.util.Set.of("name", "file", "ref"), props.keySet());
        assertEquals(List.of(), schema.get("required"));
        assertEquals(false, schema.get("additionalProperties"));
    }

    @Test
    void realRefOnlyInvocationUsesAnchorAndFixedExecutorView() {
        var calls = new AtomicInteger();
        String original = "Observe edible stock.\nOnly use current ingredients; quote: \"bread\".";
        reader((companion, ref, view) -> {
            calls.incrementAndGet();
            assertEquals(COMPANION, companion);
            assertEquals(REF, ref);
            assertEquals(LearnedSkillBodyResolver.View.EXECUTOR, view);
            return new LearnedSkillBodyResolver.Resolution(ref, original, "");
        });
        var response = JsonParser.parseString(invoke("{\"ref\":\"" + REF + "\"}", () -> COMPANION)).getAsJsonObject();
        assertTrue(response.get("success").getAsBoolean());
        assertEquals(REF, response.get("ref").getAsString());
        assertEquals(original, response.get("body").getAsString());
        assertEquals(1, calls.get());
    }

    @Test
    void missingCapabilityIsAnExplicitGapRatherThanDirectoryFallback() {
        String reply = invoke("{\"ref\":\"" + REF + "\"}", () -> COMPANION);
        assertTrue(reply.contains("learned-skill-resolver-unavailable"), reply);
        assertFalse(reply.contains("unknown skill:"), reply);
        var defaultReply = new AtomicReference<String>();
        new LoadSkillTool().invoke(new ToolCall("default-lookup", "load_skill",
                "{\"ref\":\"" + REF + "\"}", () -> COMPANION, defaultReply::set));
        assertNotNull(defaultReply.get());
        assertTrue(defaultReply.get().contains("learned-skill-resolver-unavailable"), defaultReply.get());
    }

    @Test
    void missingAnchorOrUuidCannotReadOrFallBack() {
        var calls = new AtomicInteger();
        reader((companion, ref, view) -> {
            calls.incrementAndGet();
            return new LearnedSkillBodyResolver.Resolution(ref, "must not be read", "");
        });
        String args = "{\"ref\":\"" + REF + "\"}";
        assertTrue(invoke(args, null).contains("context missing"));
        assertTrue(invoke(args, () -> null).contains("context missing"));
        assertEquals(0, calls.get());
    }

    @Test
    void invalidReferencesAndMixedModesNeverReachReader() {
        var calls = new AtomicInteger();
        reader((companion, ref, view) -> {
            calls.incrementAndGet();
            return new LearnedSkillBodyResolver.Resolution(ref, "must not be read", "");
        });
        for (String args : List.of(
                "{}", "{\"file\":\"references/food.md\"}",
                "{\"name\":\"old-pack\",\"ref\":null}", "{\"ref\":null}", "{\"ref\":\"\"}",
                "{\"ref\":\"skill:sk-food\"}", "{\"ref\":\"skill:sk-food@v0\"}",
                "{\"ref\":\"skill:sk-food@v2147483648\"}",
                "{\"ref\":\"" + REF + "\",\"name\":\"old-pack\"}",
                "{\"ref\":\"" + REF + "\",\"file\":\"references/food.md\"}")) {
            String reply = invoke(args, () -> COMPANION);
            assertFalse(JsonParser.parseString(reply).getAsJsonObject().get("success").getAsBoolean(), reply);
            assertFalse(reply.contains("must not be read"), reply);
            assertFalse(reply.contains("unknown skill:"), reply);
        }
        assertEquals(0, calls.get());
    }

    @Test
    void refusalAndWrongIdentityCannotReturnBodyAsSuccess() {
        reader((companion, ref, view) -> LearnedSkillBodyResolver.Resolution.refused(ref, "not-retrievable"));
        assertTrue(invoke("{\"ref\":\"" + REF + "\"}", () -> COMPANION).contains("not-retrievable"));
        reader((companion, ref, view) -> new LearnedSkillBodyResolver.Resolution("skill:sk-other@v7", "foreign body", ""));
        String wrong = invoke("{\"ref\":\"" + REF + "\"}", () -> COMPANION);
        assertTrue(wrong.contains("identity mismatch"), wrong);
        assertFalse(wrong.contains("foreign body"), wrong);
    }

    @Test
    void directoryAndSupportingFileRemainOnTheOriginalTwoArgumentPath() throws Exception {
        var calls = new AtomicInteger();
        reader((companion, ref, view) -> {
            calls.incrementAndGet();
            return new LearnedSkillBodyResolver.Resolution(ref, "expanded food body", "");
        });
        Path skills = dir.resolve("skills");
        Path pack = skills.resolve("offline-supply-pack");
        Files.createDirectories(pack.resolve("references"));
        Files.writeString(pack.resolve("SKILL.md"), "---\nname: offline-supply-pack\ndescription: Offline supply fixture\n---\nRead " + REF + " only when needed.\n");
        Files.writeString(pack.resolve("references/food.md"), "Optional component: " + REF);
        scannedDirectory = true;
        SkillRegistry.instance().scan(skills);
        var legacy = new AgentOps();
        String body = invoke("{\"name\":\"offline-supply-pack\"}", null);
        assertEquals(legacy.loadSkill("offline-supply-pack", null), body);
        assertTrue(body.contains(REF));
        assertFalse(body.contains("expanded food body"), "opening the pack must not recursively load a component");
        assertEquals(legacy.loadSkill("offline-supply-pack", "references/food.md"),
                invoke("{\"name\":\"offline-supply-pack\",\"file\":\"references/food.md\"}", null));
        assertEquals(0, calls.get());
        String component = invoke("{\"ref\":\"" + REF + "\"}", () -> COMPANION);
        assertEquals("expanded food body", JsonParser.parseString(component).getAsJsonObject().get("body").getAsString());
        assertEquals(1, calls.get());
    }

    @Test
    void successfulReadSurvivesConversationLogAndNextDeepSeekMessages() {
        String original = "  Observe edible stock.\r\n只用现有材料; quote: \"bread\"; path: C:\\food\\stock\t\n\n";
        reader((companion, ref, view) -> new LearnedSkillBodyResolver.Resolution(ref, original, ""));

        for (var message : invokeToNextToolMessages()) {
            var response = JsonParser.parseString(message.get("content").getAsString()).getAsJsonObject();
            assertTrue(response.get("success").getAsBoolean());
            assertEquals(REF, response.get("ref").getAsString());
            assertEquals(original, response.get("body").getAsString());
        }
    }

    @Test
    void refusedReadSurvivesConversationLogAndNextDeepSeekMessagesWithoutBody() {
        reader((companion, ref, view) -> LearnedSkillBodyResolver.Resolution.refused(ref, "not-retrievable"));

        for (var message : invokeToNextToolMessages()) {
            String content = message.get("content").getAsString();
            var response = JsonParser.parseString(content).getAsJsonObject();
            assertFalse(response.get("success").getAsBoolean());
            assertTrue(content.contains("not-retrievable"), content);
            assertFalse(response.has("body"), content);
        }
    }

    /** Offline pure seams only: actual tool completion, original log restore, then provider serialization. */
    private List<JsonObject> invokeToNextToolMessages() {
        String callId = "explicit-food-read";
        String args = "{\"ref\":\"" + REF + "\"}";
        var log = ConvoLog.atFile(dir.resolve("conversation.jsonl"));
        var convo = new ConvoState(log::append);
        convo.addUser("Read the exact learned component.");
        convo.addAssistant(new AssistantTurn("", List.of(new LlmToolCall(callId, tool.name(), args)), null));
        var reply = new AtomicReference<String>();
        tool.invoke(new ToolCall(callId, tool.name(), args, () -> COMPANION, result -> {
            reply.set(result);
            convo.addToolResult(callId, result);
        }));
        assertNotNull(reply.get(), "the actual tool must settle into the conversation");

        var restored = new ConvoState();
        restored.preload(log.loadForRestore(ConvoLog.DEFAULT_LOAD_LIMIT).history());
        assertEquals(convo.snapshot(), restored.snapshot(), "original log restore must preserve the conversation");

        var provider = new DeepSeekProvider();
        var toolMessages = new ArrayList<JsonObject>();
        for (var snapshot : List.of(convo.snapshot(), restored.snapshot())) {
            assertEquals(3, snapshot.size(), "one user, one assistant call, one actual tool result");
            var user = assertInstanceOf(ConvoState.Msg.User.class, snapshot.get(0));
            var assistant = assertInstanceOf(ConvoState.Msg.Assistant.class, snapshot.get(1));
            var result = assertInstanceOf(ConvoState.Msg.Tool.class, snapshot.get(2));
            assertEquals(callId, result.toolCallId());
            assertEquals(reply.get(), result.content(), "completion JSON must remain verbatim");
            var request = provider.buildRequestBody("offline-model", null, List.of(
                    provider.buildUserMessage(user.content()),
                    provider.assistantToRequestMessage(assistant.turn()),
                    provider.buildToolResultMessage(result.toolCallId(), result.content())), new JsonArray());
            var messages = JsonParser.parseString(request.toString()).getAsJsonObject().getAsJsonArray("messages");
            assertEquals(3, messages.size());
            assertEquals("user", messages.get(0).getAsJsonObject().get("role").getAsString());
            var assistantMessage = messages.get(1).getAsJsonObject();
            assertEquals("assistant", assistantMessage.get("role").getAsString());
            var call = assistantMessage.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
            assertEquals(callId, call.get("id").getAsString());
            assertEquals(tool.name(), call.getAsJsonObject("function").get("name").getAsString());
            assertEquals(args, call.getAsJsonObject("function").get("arguments").getAsString());
            var message = messages.get(2).getAsJsonObject();
            assertEquals("tool", message.get("role").getAsString());
            assertEquals(callId, message.get("tool_call_id").getAsString());
            assertEquals(reply.get(), message.get("content").getAsString());
            toolMessages.add(message);
        }
        return toolMessages;
    }
}
