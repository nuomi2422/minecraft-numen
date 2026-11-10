package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** P1-C only: finish one stage, then publish. Pending is recovery intent, never a query fact store. */
final class StageCompletionCommit {
    enum WritePoint { INTENT_WRITTEN, TASK_WRITTEN, FACTS_WRITTEN }
    @FunctionalInterface interface WriteHook { void after(WritePoint point) throws IOException; }
    record Result(boolean committed, boolean recordedAsCompletion, String description, String error) {
        static Result failed(String message) { return new Result(false, false, "", message); }
    }
    private record Prepared(TaskChain chain, CompletedFactStore facts, long baseVersion) {}
    private final Path tasksDir, factsDir;
    private final WriteHook hook;
    private final Map<UUID, Prepared> prepared = new HashMap<>();

    StageCompletionCommit(Path tasksDir, Path factsDir) { this(tasksDir, factsDir, point -> {}); }
    StageCompletionCommit(Path tasksDir, Path factsDir, WriteHook hook) {
        this.tasksDir = tasksDir; this.factsDir = factsDir; this.hook = Objects.requireNonNull(hook);
    }
    private Path pending(UUID id) { return tasksDir.resolve(id + ".completion-pending"); }
    boolean hasPending(UUID id) { return tasksDir != null && Files.exists(pending(id)); }
    boolean canSave(UUID id) { return !hasPending(id); }
    synchronized boolean saveRuntime(UUID id, TaskChain chain) throws IOException {
        if (!canSave(id)) return false;
        synchronized (chain) { write(tasksDir.resolve(id + ".json"), chain.toJson()); }
        return true;
    }

    Result settle(UUID id, TaskChain live, CompletedFactStore facts, String primary,
                  String reason, boolean verified, long at) {
        return submit(id, live, facts, primary, reason, verified, at, false);
    }
    Result confirm(UUID id, TaskChain live, CompletedFactStore facts, String reason, long at) {
        return submit(id, live, facts, live.currentPrimary().id(), reason, true, at, true);
    }
    private synchronized Result submit(UUID id, TaskChain live, CompletedFactStore facts, String primary,
                                       String reason, boolean verified, long at, boolean confirm) {
        if (id == null || live == null || facts == null || tasksDir == null || factsDir == null) {
            return Result.failed("completion storage unavailable");
        }
        synchronized (live) {
            synchronized (facts) {
                try {
                    JsonObject intent;
                    Prepared candidate;
                    if (hasPending(id)) {
                        intent = readIntent(id);
                        candidate = prepared.get(id);
                        if (!intent.get("chainInstanceId").getAsString().equals(live.chainInstanceId())
                                || !intent.get("primaryId").getAsString().equals(primary)
                                || intent.get("baseVersion").getAsLong() != live.stateVersion()
                                || intent.get("verified").getAsBoolean() != verified || candidate == null) {
                            return Result.failed("pending completion must recover before another stage can commit");
                        }
                    } else {
                        long base = live.stateVersion();
                        TaskChain next = live.copyForCompletionCommit();
                        CompletedFactStore nextFacts = facts.copyForCompletionCommit();
                        String description;
                        if (confirm) {
                            if (next.primaryStatus() != PrimaryGoalStatus.AWAITING_SUPERVISOR) {
                                return Result.failed("stage is not awaiting confirmation");
                            }
                            description = next.currentPrimary().description();
                            next.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, primary, reason));
                        } else {
                            description = next.settlePrimaryByFact(primary, reason, verified);
                        }
                        if (verified) nextFacts.recordStage(live.goal(), description, at, "primary confirmed");
                        intent = new JsonObject();
                        intent.addProperty("version", 1);
                        intent.addProperty("companionId", id.toString());
                        intent.addProperty("chainInstanceId", live.chainInstanceId());
                        intent.addProperty("primaryId", primary);
                        intent.addProperty("baseVersion", base);
                        intent.addProperty("verified", verified);
                        intent.addProperty("description", description);
                        intent.addProperty("chainJson", next.toJson());
                        intent.addProperty("factsJson", nextFacts.toJson());
                        candidate = new Prepared(next, nextFacts, base);
                        // Publish intent before either final projection. Any IO failure leaves live objects untouched.
                        write(pending(id), intent.toString());
                        prepared.put(id, candidate);
                        hook.after(WritePoint.INTENT_WRITTEN);
                    }
                    project(id, intent);
                    Files.delete(pending(id));
                    live.publishCompletionCommit(candidate.chain(), candidate.baseVersion());
                    facts.publishCompletionCommit(candidate.facts());
                    prepared.remove(id);
                    return new Result(true, intent.get("verified").getAsBoolean(), intent.get("description").getAsString(), "");
                } catch (IOException | RuntimeException ex) {
                    return Result.failed("completion pending/failed: " + ex.getMessage());
                }
            }
        }
    }
    synchronized Result retryPending(UUID id, TaskChain live, CompletedFactStore facts) {
        if (!hasPending(id)) return new Result(true, false, "", "");
        try {
            JsonObject intent = readIntent(id);
            return submit(id, live, facts, intent.get("primaryId").getAsString(), "resume pending completion",
                    intent.get("verified").getAsBoolean(), 0L, false);
        } catch (IOException | RuntimeException ex) { return Result.failed("completion retry pending: " + ex.getMessage()); }
    }

    /** Startup must call this before exposing either runtime or facts. No new completion event is fabricated. */
    synchronized Result recover(UUID id) {
        if (!hasPending(id)) return new Result(true, false, "", "");
        try {
            JsonObject intent = readIntent(id);
            project(id, intent);
            Files.delete(pending(id));
            prepared.remove(id);
            return new Result(true, intent.get("verified").getAsBoolean(), intent.get("description").getAsString(), "");
        } catch (IOException | RuntimeException ex) { return Result.failed("completion recovery pending: " + ex.getMessage()); }
    }
    private JsonObject readIntent(UUID id) throws IOException {
        JsonObject intent = JsonParser.parseString(Files.readString(pending(id), StandardCharsets.UTF_8)).getAsJsonObject();
        if (intent.get("version").getAsInt() != 1 || !id.toString().equals(intent.get("companionId").getAsString())) {
            throw new IOException("completion intent identity mismatch");
        }
        JsonObject chain = JsonParser.parseString(intent.get("chainJson").getAsString()).getAsJsonObject();
        if (!intent.get("chainInstanceId").getAsString().equals(chain.get("chainInstanceId").getAsString())) {
            throw new IOException("completion chain identity mismatch");
        }
        return intent;
    }
    private void project(UUID id, JsonObject intent) throws IOException {
        write(tasksDir.resolve(id + ".json"), intent.get("chainJson").getAsString());
        hook.after(WritePoint.TASK_WRITTEN);
        RddFactStore.saveJson(factsDir, id, intent.get("factsJson").getAsString());
        hook.after(WritePoint.FACTS_WRITTEN);
    }
    private static void write(Path target, String json) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
