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
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    // Same host-boundary fixture as LearnerSkillTaskFeedbackTest; no body task exists.
    private static final class Tasks implements LearnerUsageTool.TaskAccess {
        UUID owner = OWNER;
        WorldContextStamp.Token token = new WorldContextStamp.Token(Map.of("world_id", "world", "server_session", "session"), 7);
        public UUID ownerId() { return owner; }
        public UUID companionId() { return COMPANION; }
        public WorldContextStamp.Token identity() {
            return token;
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
        String id = data.get("selection_id").getAsString();
        var svc = SyncSkillFeedback.INSTANCE;
        var token = tasks.identity();
        assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.NONE, svc.bind(null, OWNER, COMPANION, token, "wire", "craft"));
        assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.REJECTED, svc.bind(id, OWNER, COMPANION, token, "wire", "other"));
        assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.REJECTED, svc.bind(id, UUID.randomUUID(), COMPANION, token, "wire", "craft"));
        assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.REJECTED, svc.bind(id, OWNER, COMPANION, new WorldContextStamp.Token(token.identity(), 8), "wire", "craft"));
        assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.BOUND, svc.bind(id, OWNER, COMPANION, token, "wire", "craft"));
        assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.DUPLICATE, svc.bind(id, OWNER, COMPANION, token, "wire", "craft"));
        assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.REJECTED, svc.bind(id, OWNER, COMPANION, token, "other-wire", "craft"));
        svc.receipt(id, OWNER, COMPANION, token, "wire", "craft", com.dwinovo.numen.api.SyncSkillCorrelation.Receipt.UNKNOWN);
        svc.receipt(id, OWNER, COMPANION, token, "wire", "craft", com.dwinovo.numen.api.SyncSkillCorrelation.Receipt.SUCCESS);
        var rows = new UsageLedger(dir).all();
        assertEquals(1, rows.size());
        assertEquals(UsageLedger.Outcome.UNKNOWN, rows.get(0).outcome());
        assertEquals(UsageLedger.EvidenceLevel.NONE, rows.get(0).evidence());
        assertTrue(new UsageLedger(dir).associationCandidates().isEmpty());
        AtomicReference<String> secondReply = new AtomicReference<>();
        tool.onServerCall("selection-2", args, null, responseText -> {
            secondReply.set(responseText);
            String reserved = JsonParser.parseString(responseText).getAsJsonObject().getAsJsonObject("data").get("selection_id").getAsString();
            assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.REJECTED,
                    svc.bind(reserved, OWNER, COMPANION, token, "premature", "craft"));
        });
        String secondId = JsonParser.parseString(secondReply.get()).getAsJsonObject().getAsJsonObject("data").get("selection_id").getAsString();
        assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.BOUND, svc.bind(secondId, OWNER, COMPANION, token, "wire-success", "craft"));
        svc.receipt(secondId, OWNER, COMPANION, token, "wrong-wire", "craft", com.dwinovo.numen.api.SyncSkillCorrelation.Receipt.SUCCESS);
        assertEquals(1, new UsageLedger(dir).all().size());
        svc.receipt(secondId, OWNER, COMPANION, token, "wire-success", "craft", com.dwinovo.numen.api.SyncSkillCorrelation.Receipt.SUCCESS);
        assertEquals(1, new UsageLedger(dir).associationCandidates().size());
        assertEquals(UsageLedger.EvidenceLevel.NONE, new UsageLedger(dir).associationCandidates().get(0).evidence());
        for (var row : new UsageLedger(dir).all()) {
            assertFalse(row.artifactId().contains(id));
            assertFalse(row.detail().contains(id));
            assertFalse(row.source().contains(id));
            assertFalse(row.artifactId().contains(secondId));
            assertFalse(row.detail().contains(secondId));
            assertFalse(row.source().contains(secondId));
        }
        AtomicReference<String> failedId = new AtomicReference<>();
        tool.onServerCall("throwing-reply", args, null, responseText -> {
            failedId.set(JsonParser.parseString(responseText).getAsJsonObject().getAsJsonObject("data").get("selection_id").getAsString());
            throw new IllegalStateException("transport failed");
        });
        assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.REJECTED,
                svc.bind(failedId.get(), OWNER, COMPANION, token, "wire2", "craft"));
        AtomicReference<String> staleId = new AtomicReference<>();
        tool.onServerCall("stale-owner", args, null, responseText -> {
            staleId.set(JsonParser.parseString(responseText).getAsJsonObject().getAsJsonObject("data").get("selection_id").getAsString());
            tasks.owner = UUID.randomUUID();
        });
        assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.REJECTED,
                svc.bind(staleId.get(), OWNER, COMPANION, token, "wire3", "craft"));
    }
}
