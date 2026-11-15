package com.dwinovo.numen.core.tools.agent;

import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.agent.tool.ToolAnchor;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.api.LearnedSkillBodyResolver;
import com.dwinovo.numen.core.tools.AgentOps;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
}
