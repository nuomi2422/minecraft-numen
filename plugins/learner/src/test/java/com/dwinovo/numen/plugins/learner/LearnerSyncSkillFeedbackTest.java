package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import com.dwinovo.numen.plugins.learner.core.UsageLedger;
import com.dwinovo.numen.plugins.learner.skills.SkillBodyStore;
import com.dwinovo.numen.plugins.learner.skills.SkillRegistrar;
import com.dwinovo.numen.plugins.learner.skills.SkillRegistry;
import com.dwinovo.numen.task.TaskRecord;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Initial positive contract only: choosing a skill for synchronous craft with no
 * RUNNING body task returns the full body and a server-issued selection correlation
 * ID, not authorization to act. Wrong tool, epoch and receipt correlation are not
 * tested here. No target-tool invocation or result is fabricated.
 */
class LearnerSyncSkillFeedbackTest {
    @TempDir Path dir;
    private static final UUID COMPANION = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // Same host-boundary fixture as LearnerSkillTaskFeedbackTest; no body task exists.
    private static final class Tasks implements LearnerUsageTool.TaskAccess {
        public UUID companionId() { return COMPANION; }
        public WorldContextStamp.Token identity() {
            return new WorldContextStamp.Token(Map.of("world_id", "world", "server_session", "session"), 7);
        }
        public TaskRecord current() { return null; }
        public TaskRecord finished(String id) { return null; }
    }

    @AfterEach void reset() { SkillRegistrar.resetForTest(); }

    @Test void emptyBodySelectsCompleteSkillForSynchronousCraft() {
        String body = "exact original steps for synchronous craft";
        String bodyRef = SkillBodyStore.write(dir, "sk-1", 1, Integer.toHexString(body.hashCode()), body);
        assertNotNull(bodyRef);
        assertEquals(body, SkillBodyStore.read(dir, bodyRef));
        SkillRegistry registry = SkillRegistrar.registryFor(dir);
        ArtifactDraft artifact = new ArtifactDraft(ArtifactKind.EXPERIENCE, "craft steps", "craft safely", body,
                List.of("craft"), List.of(), List.of(), "LOW", List.of(), List.of("memo-1"), "EXECUTOR");
        assertEquals(SkillRegistry.Outcome.REGISTERED, registry.register(artifact, "sk-1", bodyRef, true).outcome());
        UsageLedger ledger = new UsageLedger(dir);
        Tasks tasks = new Tasks();
        assertNull(tasks.current());
        LearnerUsageTool tool = new LearnerUsageTool(dir, ledger, tasks);

        JsonObject args = new JsonObject();
        args.addProperty("action", "adopt_sync_skill");
        args.addProperty("skill_ref", "skill:sk-1");
        args.addProperty("version", "v1");
        args.addProperty("target_tool", "craft");
        AtomicReference<String> reply = new AtomicReference<>();
        tool.onServerCall("selection-call", args, null, reply::set);
        assertNotNull(reply.get(), "production branch must reply");
        JsonObject response = JsonParser.parseString(reply.get()).getAsJsonObject();
        assertTrue(response.get("success").getAsBoolean(), response.toString());
        JsonObject data = response.getAsJsonObject("data");
        assertNotNull(data);
        assertTrue(data.has("selection_id"), "server must return a selection correlation ID");
        assertFalse(data.get("selection_id").getAsString().isBlank());
        assertEquals(body, data.get("body").getAsString());
        assertNull(tasks.current());
        assertTrue(ledger.all().isEmpty(), "selection alone is not a result or effectiveness fact");
    }
}
