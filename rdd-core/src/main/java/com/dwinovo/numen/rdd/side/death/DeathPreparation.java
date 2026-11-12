package com.dwinovo.numen.rdd.side.death;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.core.WorldFactConditions;
import com.dwinovo.numen.rdd.policy.AssetSemantics;
import com.dwinovo.numen.rdd.side.*;
import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * 2026-11-12: death-local preparation obligations and planner callback application.
 * WHY: a later trading manifest cannot represent earlier still-needed worn equipment.
 * Reuses the active SideTask data/lifecycle and the existing decomposer; never mutates TaskChain.
 * No text-to-armor guesses or global loadout: only explicit same-chain conditions/declarations.
 */
public final class DeathPreparation {
    private static final Gson JSON = new Gson();
    public static final String TARGETS = "prepare_targets";
    public static final String PLAN = "prepare_plan";
    public static final int MAX_TARGETS = 16, MAX_STEPS = 8, MAX_JSON = 16000;
    private DeathPreparation() {}

    @FunctionalInterface public interface Planner {
        void plan(String objective, String hint, Consumer<List<SubtaskSpec>> done);
    }

    private record Selection(String scope, List<Map<String, Object>> targets, String reason) {}
    private static Selection unknownSelection(String reason) { return new Selection("UNKNOWN", List.of(), reason); }

    public static List<Map<String, Object>> targets(TaskChain chain, Map<String, AssetSemantics> semantics,
                                                    Map<String, String> wearSlots) {
        return select(chain, semantics, wearSlots).targets();
    }

    /** Stop at the real current subtask, not the end of its primary's future plan. */
    private static Selection select(TaskChain chain, Map<String, AssetSemantics> semantics,
                                    Map<String, String> wearSlots) {
        if (chain == null || chain.goal() == null || chain.currentPrimary() == null)
            return unknownSelection("recovery_chain_unavailable");
        Map<String, Map<String, Object>> selected = new LinkedHashMap<>();
        Set<String> reached = new LinkedHashSet<>(), later = new LinkedHashSet<>();
        boolean boundary = false;
        for (PrimaryGoal primary : chain.goal().primaryGoals()) {
            boolean current = primary.id().equals(chain.currentPrimary().id());
            for (Subtask sub : primary.subtasks()) {
                if (boundary) { declarationKey(sub.condition()).ifPresent(later::add); continue; }
                Map<String, Object> c = sub.condition();
                if (chain.subtaskStatuses().get(sub.id()) != SubtaskStatus.SKIPPED
                        && !Boolean.TRUE.equals(c.get("optional"))) {
                    declarationKey(c).ifPresent(reached::add);
                    if ("equipment".equals(c.get("type"))) {
                        if (!WorldFactConditions.valid(c)) return unknownSelection("invalid_equipment_declaration");
                        selected.put(wearKey(c), c);
                    } else if (c.get("asset_key") instanceof String key && wearSlots != null && wearSlots.containsKey(key)) {
                        Object raw = c.getOrDefault("minimum", 1);
                        if (!(raw instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() <= 0)
                            return unknownSelection("invalid_equipment_quantity");
                        addDeclared(selected, key, n.intValue(), wearSlots);
                    }
                }
                if (current && chain.currentSubtask() != null && sub.id().equals(chain.currentSubtask().id())) boundary = true;
            }
            if (current) boundary = true;
        }
        for (PrimaryGoal primary : chain.goal().primaryGoals()) {
            boolean current = primary.id().equals(chain.currentPrimary().id());
            for (AssetRequirement requirement : primary.waitFor()) {
                // Entry declarations cannot smuggle in the current primary's later equipment output.
                if (!current || reached.contains(requirement.assetKey()) || !later.contains(requirement.assetKey())) {
                    addDeclared(selected, requirement.assetKey(), requirement.minimum(), wearSlots);
                    reached.add(requirement.assetKey());
                }
            }
            if (current) break;
        }
        if (semantics != null) for (AssetSemantics s : semantics.values()) {
            if (s == null || !reached.contains(s.assetKey()) || s.nature() != AssetSemantics.Nature.EQUIPMENT
                    || !s.important() || !chain.goal().id().equals(s.servesGoal())) continue;
            if (!s.requiredCapability().matches("[1-9][0-9]{0,3}")) return unknownSelection("equipment_quantity_not_declared");
            int minimum = Integer.parseInt(s.requiredCapability());
            String slot = wearSlots == null ? null : wearSlots.get(s.assetKey());
            if (slot == null) {
                selected.put("stock:" + s.assetKey(), Map.of("asset_key", s.assetKey(), "minimum", minimum, "mode", "hold"));
                continue;
            }
            var wear = Map.<String, Object>of("type", "equipment", "item", s.assetKey(), "slot", slot);
            selected.putIfAbsent(wearKey(wear), wear);
            if (minimum > 1) selected.put("stock:" + s.assetKey(), Map.of("asset_key", s.assetKey(), "minimum", minimum, "mode", "hold"));
        }
        if (selected.size() > MAX_TARGETS || JSON.toJson(selected.values()).length() > MAX_JSON)
            return unknownSelection("equipment_declarations_over_budget");
        return new Selection(selected.isEmpty() ? "NONE" : "DECLARED", List.copyOf(selected.values()),
                selected.isEmpty() ? "no_applicable_equipment_obligation" : "equipment_recheck_required");
    }

    private static Optional<String> declarationKey(Map<String, Object> c) {
        Object raw = "equipment".equals(c.get("type")) ? c.get("item") : c.get("asset_key");
        return raw instanceof String s ? Optional.of(s) : Optional.empty();
    }

    private static String wearKey(Map<String, Object> c) {
        String slot = String.valueOf(c.getOrDefault("slot", "any"));
        slot = switch (slot) { case "boots" -> "feet"; case "leggings" -> "legs";
            case "chestplate" -> "chest"; case "helmet" -> "head"; default -> slot; };
        return "wear:" + slot + ("any".equals(slot) ? ":" + c.get("item") : "") + ":" + c.getOrDefault("dimension", "");
    }
    private static void addDeclared(Map<String, Map<String, Object>> selected, String key, int minimum,
                                    Map<String, String> slots) {
        String slot = slots == null ? null : slots.get(key);
        if (slot == null) return;
        var wear = Map.<String, Object>of("type", "equipment", "item", key, "slot", slot);
        selected.put(wearKey(wear), wear);
        if (minimum > 1) selected.put("stock:" + key,
                Map.of("asset_key", key, "minimum", minimum, "mode", "hold"));
    }

    /** Serialize obligations into this recovery instance, not a global capability table. */
    public static boolean attach(SideTaskEngine engine, UUID id, TaskChain chain,
                                 Map<String, AssetSemantics> semantics, Map<String, String> slots) {
        SideTask task = engine.current(id).orElse(null);
        if (task == null || !DeathRecoverySideTaskType.TYPE_ID.equals(task.typeId()) || !sameChain(task, chain)) return false;
        if (task.data().containsKey(TARGETS)) return true;
        var data = new LinkedHashMap<>(task.data());
        Selection selection = select(chain, semantics, slots);
        data.put(TARGETS, JSON.toJson(selection.targets()));
        data.put("prepare_scope", selection.scope());
        data.put("prepare_reason", selection.reason());
        engine.runtime().replace(task.withData(data));
        return true;
    }

    /** NONE is a successful selector result, never a parse-error fallback. */
    public static boolean notApplicable(SideTask task) {
        if (task == null || !"NONE".equals(task.data().get("prepare_scope"))) return false;
        try {
            return JsonParser.parseString(task.data().getOrDefault(TARGETS, "null")).getAsJsonArray().isEmpty();
        } catch (RuntimeException ex) { return false; }
    }

    public static boolean choose(SideTaskEngine engine, UUID id, DeathRecoveryDecision decision) {
        SideTask task = engine.current(id).orElse(null);
        if (task == null || !DeathRecoverySideTaskType.TYPE_ID.equals(task.typeId()) || decision == null) return false;
        if (decision.name().equals(task.data().get("decision"))) return true;
        var data = new LinkedHashMap<>(task.data());
        data.put("decision", decision.name());
        data.remove("prepare_request"); // invalidates any in-flight callback for a different choice
        data.remove(PLAN);
        data.remove("prepare_descriptions");
        data.remove("prepare_index");
        data.remove("prepare_plan_state");
        if (prepares(decision) && !notApplicable(task)) data.put("prepare_required", "true");
        else data.remove("prepare_required");
        engine.runtime().replace(task.withData(data));
        return true;
    }

    public static boolean prepares(DeathRecoveryDecision decision) {
        return decision == DeathRecoveryDecision.PREPARE_AND_REPLAN
                || decision == DeathRecoveryDecision.FETCH_BACKUP_THEN_CONTINUE;
    }

    /** Tick consumes current evidence and requests one bounded plan, without moving the mainline cursor. */
    public static void tick(SideTaskEngine engine, UUID id, TaskChain chain, SideTaskWorldPort world, Planner planner) {
        SideTask task = engine.current(id).orElse(null);
        if (task == null || !DeathRecoverySideTaskType.TYPE_ID.equals(task.typeId()) || !sameChain(task, chain) || notApplicable(task)) return;
        DeathRecoveryDecision decision = DeathRecoveryDecision.parse(task.data().get("decision"));
        if (decision == DeathRecoveryDecision.RESET_AND_RESTART) return;
        var facts = world.deathPreparationFacts(id, task);
        var drops = world.deathRecoveryFacts(id, task).dropState();
        boolean terminalDrops = drops == SideTaskWorldPort.DeathDropState.DROPS_ABSENT
                || drops == SideTaskWorldPort.DeathDropState.RECOVERED;
        boolean automatic = terminalDrops && task.data().containsKey(TARGETS)
                && facts.state() != SideTaskWorldPort.PreparationState.READY;
        if (!prepares(decision) && !"true".equals(task.data().get("prepare_required")) && !automatic) return;
        var data = new LinkedHashMap<>(task.data());
        data.put("prepare_required", "true");
        data.put("prepare_reason", "REJECTED".equals(data.get("prepare_plan_state"))
                ? "preparation_plan_rejected_or_unavailable; " + facts.reason() : facts.reason());
        if (drops == SideTaskWorldPort.DeathDropState.DROPS_ABSENT) data.put("drop_terminal", RecoveryTerminal.CONFIRMED_ABSENT);
        if (drops == SideTaskWorldPort.DeathDropState.RECOVERED) data.put("drop_terminal", RecoveryTerminal.RECOVERED);
        List<Map<String, Object>> plan = conditions(task, PLAN);
        int index = integer(data.get("prepare_index"));
        while (index < plan.size() && Boolean.TRUE.equals(world.deathPreparationCondition(id, plan.get(index)))) index++;
        data.put("prepare_index", String.valueOf(index));
        engine.runtime().replace(task.withData(data));
        if (facts.state() != SideTaskWorldPort.PreparationState.MISSING || task.pastDeadline(world.gameTime())
                || data.containsKey("prepare_request") || data.containsKey(PLAN) || planner == null) return;
        String request = UUID.randomUUID().toString();
        data.put("prepare_request", request);
        data.put("prepare_plan_state", "PENDING");
        SideTask requested = task.withData(data);
        engine.runtime().replace(requested);
        String objective = "恢复当前装备能力（死亡批次 " + data.getOrDefault("death_seq", "?")
                + "）：按明确条件取得/穿戴，完成后继续原任务，不重做历史阶段";
        String hint = "当前恢复条件=" + data.getOrDefault(TARGETS, "[]")
                + "\n当前随身=" + facts.held() + "；已观测备用=" + facts.backup()
                + "\n策略=" + data.getOrDefault("decision", "PREPARE_AND_REPLAN")
                + "；掉落=" + drops + "。DROPS_ABSENT 时不得再安排回死亡点拾取。"
                + "有当前物品/可用备用优先取用穿戴，不重复制造。背包有甲不等于已穿。"
                + "只生成当前能力补齐步骤，condition仅用hold库存或equipment当前槽位；不改变原主线。";
        try {
            planner.plan(objective, hint, specs -> install(engine, id, chain, requested, request, specs));
        } catch (RuntimeException ex) {
            install(engine, id, chain, requested, request, List.of());
        }
    }

    /** Called inside the host's existing callback guard; checks persisted instance/chain/death identity too. */
    public static boolean install(SideTaskEngine engine, UUID id, TaskChain liveChain, SideTask expected,
                                  String request, List<SubtaskSpec> specs) {
        SideTask current = engine.current(id).orElse(null);
        if (current == null || !sameChain(current, liveChain)
                || !current.instanceId().equals(expected.instanceId()) || current.generation() != expected.generation()
                || !Objects.equals(current.data().get("death_seq"), expected.data().get("death_seq"))
                || !Objects.equals(current.data().get("decision"), expected.data().get("decision"))
                || !Objects.equals(current.data().get("prepare_request"), request)
                || !"PENDING".equals(current.data().get("prepare_plan_state"))) return false;
        var data = new LinkedHashMap<>(current.data());
        List<Map<String, Object>> steps = new ArrayList<>();
        List<String> descriptions = new ArrayList<>();
        boolean legal = specs != null && !specs.isEmpty() && specs.size() <= MAX_STEPS;
        if (legal) for (SubtaskSpec spec : specs) {
            if (spec == null || spec.nodeType() != NodeType.MAIN || !planCondition(spec.condition())) { legal = false; break; }
            steps.add(spec.condition());
            descriptions.add(spec.description().substring(0, Math.min(400, spec.description().length())));
        }
        String serialized = JSON.toJson(steps);
        if (!legal || serialized.length() > MAX_JSON) {
            data.put("prepare_plan_state", "REJECTED");
            data.put("prepare_reason", "preparation_plan_rejected_or_unavailable");
        } else {
            data.put(PLAN, serialized);
            data.put("prepare_descriptions", JSON.toJson(descriptions));
            data.put("prepare_plan_state", "READY");
            data.put("prepare_index", "0");
        }
        engine.runtime().replace(current.withData(data));
        return true;
    }

    private static boolean planCondition(Map<String, Object> c) {
        if ("equipment".equals(c.get("type"))) return WorldFactConditions.valid(c);
        Object type = c.get("type");
        Object key = c.containsKey("asset_key") ? c.get("asset_key") : c.get("item");
        Object mode = c.get("mode");
        if (type != null && !"inventory".equals(type) && !"item".equals(type)) return false;
        Object min = c.getOrDefault("minimum", 1);
        return key instanceof String s && s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                && (mode == null || "hold".equals(mode)) && min instanceof Number n
                && n.doubleValue() == n.intValue() && n.intValue() > 0;
    }

    public static List<Map<String, Object>> conditions(SideTask task, String field) {
        try {
            String raw = task.data().getOrDefault(field, "[]");
            if (raw.length() > MAX_JSON) return List.of();
            JsonArray array = JsonParser.parseString(raw).getAsJsonArray();
            if (array.size() > MAX_TARGETS) return List.of();
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonElement e : array) {
                @SuppressWarnings("unchecked") Map<String, Object> c = JSON.fromJson(e, Map.class);
                if (c == null || !planCondition(c)) return List.of();
                out.add(Map.copyOf(c));
            }
            return List.copyOf(out);
        } catch (RuntimeException ex) { return List.of(); }
    }

    public static String context(SideTask task) {
        String raw = task.data().getOrDefault("prepare_descriptions", "[]");
        try {
            JsonArray descriptions = JsonParser.parseString(raw).getAsJsonArray();
            int index = integer(task.data().get("prepare_index"));
            if (index < descriptions.size() && index < MAX_STEPS) return descriptions.get(index).getAsString();
        } catch (RuntimeException ignored) { }
        return "核实当前装备与备用；未知不当作没有，不能把历史完成当仍持有";
    }

    /** Bounded resume payload lives in the existing pause reason, not another recovery registry. */
    public static String resumePayload(SideTask task) {
        var data = new LinkedHashMap<String, String>();
        for (String key : List.of(TARGETS, PLAN, "prepare_scope", "prepare_descriptions", "prepare_index",
                "prepare_reason", "prepare_plan_state", "prepare_retries", "prepare_retry_at", "death_seq",
                "decision", "drop_terminal", "dimension", "death_at", "prepare_death_seq")) {
            String value = task.data().get(key);
            if (value != null && value.length() <= MAX_JSON) data.put(key, value);
        }
        String encoded = JSON.toJson(task.withData(data));
        if (encoded.length() > 2 * MAX_JSON + 8000) {
            data.remove(PLAN); data.remove("prepare_descriptions");
            data.put("prepare_plan_state", "REJECTED");
            data.put("prepare_reason", "preparation_resume_payload_over_budget");
            encoded = JSON.toJson(task.withData(data));
        }
        return encoded;
    }

    public static String pauseReason(String payload, String reason) {
        if (payload == null || payload.length() > 2 * MAX_JSON + 8000) payload = "";
        return "recovery_not_verified:" + JSON.toJson(Map.of("resume", payload,
                "reason", reason == null ? "unknown" : reason.substring(0, Math.min(400, reason.length()))));
    }

    public static SideTask resumeTask(String payload) {
        try {
            if (payload == null || payload.length() > 2 * MAX_JSON + 8000) return null;
            return JSON.fromJson(payload, SideTask.class);
        } catch (RuntimeException ex) { return null; }
    }
    /** Preserve the current plan/cursor and all historical facts. Duplicate settlement is harmless. */
    public static boolean settle(TaskChain chain, String payload, boolean verified, String reason) {
        SideTask task = resumeTask(payload);
        if (task == null || !sameChain(task, chain)) return false;
        if (verified) {
            if (chain.currentSubtask() != null && chain.isPaused(chain.currentSubtask().id())
                    && chain.pauseReason(chain.currentSubtask().id()) != null
                    && chain.pauseReason(chain.currentSubtask().id()).startsWith("recovery_not_verified:"))
                chain.resumeFromPaused(chain.currentSubtask().id());
            return true;
        }
        if (chain.currentSubtask() == null) return true;
        if (chain.isPaused(chain.currentSubtask().id()) && chain.pauseReason(chain.currentSubtask().id()) != null
                && chain.pauseReason(chain.currentSubtask().id()).startsWith("recovery_not_verified:")) {
            SideTask existing = pausedTask(chain);
            if (existing != null && integer(existing.data().get("prepare_retries")) == integer(task.data().get("prepare_retries"))
                    && List.of(PLAN, "prepare_plan_state", "prepare_index", "prepare_death_seq")
                    .stream().allMatch(k -> Objects.equals(existing.data().get(k), task.data().get(k)))) return true;
            chain.resumeFromPaused(chain.currentSubtask().id());
        }
        if (!chain.isPaused(chain.currentSubtask().id())) {
            var data = new LinkedHashMap<>(task.data());
            data.putIfAbsent("prepare_retry_at", String.valueOf(task.deadlineGameTime() + 1200L));
            data.putIfAbsent("prepare_retries", "0");
            chain.pauseSubtask(chain.currentSubtask().id(), pauseReason(resumePayload(task.withData(data)), reason));
        }
        return true;
    }

    public static SideTask pausedTask(TaskChain chain) {
        if (chain == null || chain.currentSubtask() == null || !chain.isPaused(chain.currentSubtask().id())) return null;
        String reason = chain.pauseReason(chain.currentSubtask().id());
        if (reason == null || !reason.startsWith("recovery_not_verified:")) return null;
        try {
            var envelope = JsonParser.parseString(reason.substring("recovery_not_verified:".length())).getAsJsonObject();
            return resumeTask(envelope.get("resume").getAsString());
        } catch (RuntimeException ex) { return null; }
    }

    /** Cache-only final execution projection: original trade remains preserved, not executable while paused. */
    public static String pausedContext(TaskChain chain) {
        SideTask task = pausedTask(chain);
        if (task == null || !sameChain(task, chain)) return "";
        return "<rdd><enabled>true</enabled><active>true</active><primary_status>PAUSED</primary_status>"
                + "<state>recovery_not_verified</state><scope>SIDE</scope><current_task>"
                + xml(context(task)) + "</current_task><done_when>" + xml(task.data().getOrDefault(TARGETS, "[]"))
                + "</done_when><note>原主步骤保留但尚不准入；先核实/补齐装备，背包有甲未穿不等于完成。"
                + xml(task.data().getOrDefault("prepare_reason", "current equipment unverified"))
                + "</note></rdd>";
    }

    /** 2026-11-13: share the existing read-only retry gate before a host commits a fresh paused body. */
    public static boolean retryDue(SideTask task, SideTaskWorldPort world) {
        int retries = integer(task.data().get("prepare_retries"));
        long at;
        try { at = Long.parseLong(task.data().getOrDefault("prepare_retry_at", String.valueOf(task.deadlineGameTime() + 1200L))); }
        catch (RuntimeException invalid) { return false; }
        return retries < 2 && world.gameTime() >= at;
    }

    /** Existing paused recheck may retry twice, >=60s apart; no separate scheduler or unbounded loop. */
    public static boolean reopenPaused(SideTaskEngine engine, UUID id, TaskChain chain, SideTaskWorldPort world) {
        SideTask old = pausedTask(chain);
        if (old == null || !sameChain(old, chain) || notApplicable(old) || engine.isActive(id)) return false;
        if (world.deathPreparationFacts(id, old).state() != SideTaskWorldPort.PreparationState.MISSING) return false;
        if (!retryDue(old, world)) return false;
        int retries = integer(old.data().get("prepare_retries"));
        var data = new LinkedHashMap<>(old.data());
        data.put("prepare_retries", String.valueOf(retries + 1));
        data.put("prepare_retry_at", String.valueOf(world.gameTime() + 1200L));
        data.put("prepare_required", "true");
        if ("REJECTED".equals(data.get("prepare_plan_state")) || "PENDING".equals(data.get("prepare_plan_state"))) {
            data.remove(PLAN); data.remove("prepare_descriptions"); data.remove("prepare_index");
            data.remove("prepare_plan_state");
        }
        data.remove("prepare_request");
        SideTask retry = new SideTask(id, old.instanceId(), old.typeId(), old.generation(), old.scheduledGameDay(),
                world.gameTime(), world.gameTime() + DeathRecoverySideTaskType.DEADLINE_TICKS,
                SideTaskState.ACTIVE, DeathRecoverySideTaskType.PHASE_REEQUIP, data, old.mainlineToken());
        if (!engine.runtime().activate(retry)) return false;
        // Keep retry budget in the existing pause payload too, surviving another terminal/restore.
        chain.resumeFromPaused(chain.currentSubtask().id());
        chain.pauseSubtask(chain.currentSubtask().id(), pauseReason(resumePayload(retry), factsReason(retry)));
        return true;
    }

    private static String factsReason(SideTask task) { return task.data().getOrDefault("prepare_reason", "recovery_not_verified"); }
    private static String xml(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    /** This specific pause has no generic timeout bypass; new trusted evidence can release it. */
    public static boolean holdPaused(TaskChain chain, UUID id, SideTaskWorldPort world) {
        if (chain == null || chain.currentSubtask() == null) return false;
        String reason = chain.pauseReason(chain.currentSubtask().id());
        if (reason == null || !reason.startsWith("recovery_not_verified:")) return false;
        try {
            var envelope = JsonParser.parseString(reason.substring("recovery_not_verified:".length())).getAsJsonObject();
            SideTask task = JSON.fromJson(envelope.get("resume").getAsString(), SideTask.class);
            if (task != null && sameChain(task, chain)
                    && world.deathPreparationFacts(id, task).state() == SideTaskWorldPort.PreparationState.READY) {
                chain.resumeFromPaused(chain.currentSubtask().id());
                return false;
            }
        } catch (RuntimeException ignored) { /* absent evidence remains paused, never owner-stop */ }
        return true;
    }

    private static int integer(String s) {
        try { return Math.max(0, Integer.parseInt(s)); } catch (RuntimeException ex) { return 0; }
    }
    public static boolean sameChain(SideTask task, TaskChain chain) {
        if (task == null || chain == null || task.mainlineToken() == null) return false;
        return task.mainlineToken().matches(chain.chainInstanceId(), chain.goal().id(), chain.currentPrimary().id(),
                chain.currentSubtask() == null ? null : chain.currentSubtask().id(), chain.planRevision());
    }
}
