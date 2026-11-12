package com.dwinovo.numen.rdd.side;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 支线引擎：驱动触发器 + 推进活跃支线 + 发事件 + 恢复主线。纯 JVM，可无游戏单测。
 *
 * <p>每拍（宿主约每 20 tick 调一次）：
 * <ol>
 *   <li>有活跃支线 → 让它的 {@link SideTaskType#evaluate} 给结论并应用；</li>
 *   <li>没有 → 按触发器判断是否开一条新的（同一游戏日 + 同一维度只开一次；世界无昼夜循环
 *       或死亡支线占用时不抢）。</li>
 * </ol>
 */
public final class SideTaskEngine {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final SideTaskRuntime runtime = new SideTaskRuntime();
    private final Map<String, SideTaskType> types = new LinkedHashMap<>();
    private final List<SideTaskTrigger> triggers = new ArrayList<>();
    /** 去重键(companion|world|typeId) → 已处理过的游戏日。 */
    private final Map<String, Long> handledGameDay = new HashMap<>();
    /** 去重键 → 推迟到该 gameTime 之前不再重新决策（"推迟到安全停顿点"）。用单调 gameTime，不受 /time set 影响。 */
    private final Map<String, Long> deferredUntil = new HashMap<>();
    /** 2026-11-08: recurring specs by specId + their task type. */
    private final Map<String, RecurringSideTaskSpec> recurringSpecs = new LinkedHashMap<>();
    private final Map<String, String> recurringTypes = new HashMap<>();
    /** companion|world|spec|gameDay — recurring one-shot per day. */
    private final Map<String, Long> recurringHandled = new HashMap<>();
    /** 宿主约每 20 tick 调一次 tick；推迟冷却不得小于一拍，否则每拍都会重新决策。 */
    private static final int MIN_DEFER_TICKS = 20;
    private final SideTaskEvents events;
    private final MainlineValidator mainlineValidator;

    public SideTaskEngine(SideTaskEvents events, MainlineValidator mainlineValidator) {
        this.events = events == null ? SideTaskEvents.NOOP : events;
        this.mainlineValidator = mainlineValidator;
    }

    public SideTaskEvents events() {
        return events;
    }

    public SideTaskRuntime runtime() {
        return runtime;
    }

    public void registerType(SideTaskType type) {
        types.put(type.typeId(), type);
    }

    public void registerTrigger(SideTaskTrigger trigger) {
        triggers.add(trigger);
    }

    /** 2026-11-08: register a recurring/daily spec; it never wakes the body while busy. */
    /** Read-only handled probe: null = UNKNOWN (no key), true/false = recorded day equals today. */
    public Boolean handledToday(UUID companionId, String worldId, String specId, long gameDay) {
        String key = companionId + "|" + worldId + "|" + specId + "|" + gameDay;
        Long handled = recurringHandled.get(key);
        return handled == null ? null : handled == gameDay;
    }

    /** Replace only this goal's recurring specs; other goals keep theirs. */
    public void clearRecurringForGoal(String goalId) {
        if (goalId == null || goalId.isBlank()) return;
        String prefix = "daily:" + goalId + ":";
        recurringSpecs.keySet().removeIf(id -> id.startsWith(prefix));
        recurringTypes.keySet().removeIf(id -> id.startsWith(prefix));
    }

    /** 2026-11-08: SURVIVAL report forces a task even when thresholds are not met; idempotent. */
    public boolean startNow(SideTaskType type, UUID companionId, SideTaskWorldPort world,
                            MainlineResumeToken token, Map<String, String> data) {
        if (type == null || runtime.current(companionId).isPresent()) return false;
        long now = world.gameTime();
        long day = world.gameDay();
        long generation = runtime.nextGeneration();
        SideTask task = type.create(new SideTaskType.CreationContext(
                companionId, type.typeId() + "-" + generation + "-" + shortId(companionId), generation,
                day, now, now + type.deadlineTicks(), token, data));
        if (!runtime.activate(task)) return false;
        events.publish("side_task_created", base(task));
        events.publish("side_task_activated", base(task));
        return true;
    }

    public void clearRecurring() {
        recurringSpecs.clear();
        recurringTypes.clear();
    }

    public void registerRecurring(RecurringSideTaskSpec spec, String typeId) {
        if (spec == null || typeId == null || typeId.isBlank()) return;
        recurringSpecs.put(spec.scheduleId(), spec);
        recurringTypes.put(spec.scheduleId(), typeId);
    }

    /** 2026-11-12: immutable schedule probe; UNKNOWN is null, never a reconstructed schedule. */
    public RecurringSideTaskSpec recurringSpec(String scheduleId) {
        return recurringSpecs.get(scheduleId);
    }

    public SideTaskType typeOf(String typeId) {
        return types.get(typeId);
    }

    /** 找到某类支线的触发器（没有 → null）。 */
    private SideTaskTrigger triggerOf(String typeId) {
        for (SideTaskTrigger t : triggers) {
            if (t.typeId().equals(typeId)) {
                return t;
            }
        }
        return null;
    }

    public Optional<SideTask> current(UUID companionId) {
        return runtime.current(companionId);
    }

    public boolean isActive(UUID companionId) {
        return runtime.isActive(companionId);
    }

    /** Set a validated strategy field on an active task; unknown fields are rejected by the caller. */
    public boolean setData(UUID companionId, String key, String value) {
        Optional<SideTask> active = runtime.current(companionId);
        if (active.isEmpty() || key == null || key.isBlank()) return false;
        Map<String, String> data = new LinkedHashMap<>(active.get().data());
        data.put(key, value == null ? "" : value);
        runtime.replace(active.get().withData(data));
        return true;
    }

    public void tick(UUID companionId, SideTaskWorldPort world,
                     MainlineResumeToken mainlineToken, boolean deathSideTaskOccupying) {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(world, "world");

        Optional<SideTask> active = runtime.current(companionId);
        if (active.isPresent()) {
            // 2026-10-28 实机死锁（本会话抓到的）：
            // 她在主世界天黑时激活了「睡觉支线」，随后自己走进**下界**；
            // 下界没有昼夜循环、也睡不成，而这里原来**只看 active 就 advance、从不重判触发条件**
            // → 支线永远 active → 主线被冻结 → 判据明明成立（condition_matches=true）也永远判不过
            // （实测卡了 14 分钟，一步没走）。
            // 所以：active 期间也要复查「当初让它来的条件还在不在」，不在了就取消，把主线放出来。
            SideTask running = active.get();
            SideTaskTrigger source = triggerOf(running.typeId());
            // 167 §B4-6（2026-11-01）：**死亡恢复点名事件不许被常驻支线吞掉**。
            // 原来 active 存在时这里直接 advance 并 return，于是"睡觉支线正在跑"期间发生死亡，
            // death_recovery 根本不会在本轮被创建 —— 要等睡觉结束。而掉落物 5 分钟就消失。
            // 现在：死亡占用时，任何不允许在死亡期运行的支线先取消，再让本轮去创建死亡恢复。
            if (deathSideTaskOccupying && (source == null || !source.allowedWhileDeathOccupying())) {
                cancel(companionId, "death recovery takes precedence over " + running.typeId());
                // **本拍到此为止**：不能接着跑触发循环，否则同一条常驻支线会被立刻重新创建
                // （实测：取消后 sleep 触发器仍然 fires，于是 cancel→create 循环）。
                // 下一拍 active 为空，deathSideTaskOccupying 会把非死亡触发器全部跳过，
                // 死亡恢复点名就能在下一拍被创建——掉落窗口是 5 分钟，等一拍没有代价。
                return;
            }
            {
            // 只有依赖昼夜能力的周期支线才在固定时间维度取消。
            // 死亡恢复必须能在下界/末地运行；它的时钟是掉落/业务事实，不是昼夜时钟。
            if (!world.hasDayNightCycle() && (source == null || !source.allowedWhileDeathOccupying())) {
                cancel(companionId, "world has no day-night cycle (" + world.worldId() + "); side task can never finish");
                return;
            }
            if (world.bodyBusy(companionId) && "daily_maintenance".equals(running.typeId())) {
                finish(running, SideTaskState.CANCELLED, "mainline needs body; daily yields");
                return;
            }
            advance(active.get(), world);
            return;
            }
        }
        if (!world.hasDayNightCycle() && !deathSideTaskOccupying) {
            return;
        }
        long now = world.gameTime();
        long day = world.gameDay();
        if (!deathSideTaskOccupying && !world.bodyBusy(companionId)) {
            if (tickRecurring(companionId, world, mainlineToken, day, now)) {
                return;
            }
        }

        for (SideTaskTrigger trigger : triggers) {
            if (deathSideTaskOccupying && !trigger.allowedWhileDeathOccupying()) {
                continue;
            }
            String key = handledKey(companionId, world.worldId(), trigger.typeId());
            // 按日去重只适用于周期支线。死亡恢复按 deathSeq 去重（触发器 take() 消费一批），
            // 否则同一天的第二、三次死亡会被"今天已经问过"静默吞掉（V-20261030-13）。
            boolean oncePerDay = trigger.oncePerGameDay();
            if (oncePerDay) {
                Long handled = handledGameDay.get(key);
                if (handled != null && handled == day) {
                    continue;
                }
                // 推迟冷却：未到点不重新决策（避免每拍重复决策）。
                Long until = deferredUntil.get(key);
                if (until != null && now < until) {
                    continue;
                }
            }
            if (!trigger.fires(new SideTaskTrigger.Context(
                    companionId, world.worldId(), day, world.dayTime(), world.hasDayNightCycle(),
                    world.hunger(companionId), world.health(companionId), world.edibleCount(companionId)))) {
                continue;
            }
            SideTaskType type = types.get(trigger.typeId());
            if (type == null) {
                continue;
            }
            SideTaskDecision decision = type.decide(companionId, world);
            if (decision == null) {
                decision = SideTaskDecision.EXECUTE;
            }
            if (decision.kind() == SideTaskDecision.Kind.DEFER) {
                if (oncePerDay) {
                    deferredUntil.put(key, now + Math.max(decision.deferTicks(), MIN_DEFER_TICKS));
                }
                publishOccurrence(companionId, "side_task_deferred", trigger.typeId(), day,
                        decision.reason(), Math.max(decision.deferTicks(), MIN_DEFER_TICKS));
                continue;
            }
            if (decision.kind() == SideTaskDecision.Kind.SKIP) {
                if (oncePerDay) {
                    deferredUntil.remove(key);
                    handledGameDay.put(key, day);
                }
                publishOccurrence(companionId, "side_task_skipped_occurrence", trigger.typeId(), day,
                        decision.reason(), 0L);
                continue;
            }
            // EXECUTE：清掉可能残留的推迟，照旧创建。
            if (oncePerDay) {
                deferredUntil.remove(key);
            }
            MainlineResumeToken token = mainlineValidator == null ? null : mainlineValidator.validate(mainlineToken);
            long generation = runtime.nextGeneration();
            String instanceId = trigger.typeId() + "-" + generation + "-" + shortId(companionId);
            SideTask task = type.create(new SideTaskType.CreationContext(
                    companionId, instanceId, generation, day, now,
                    now + type.deadlineTicks(), token, Map.of()));
            if (runtime.activate(task)) {
                if (oncePerDay) {
                    handledGameDay.put(key, day);
                }
                events.publish("side_task_created", base(task));
                events.publish("side_task_activated", base(task));
            }
            return;
        }
    }

    /** 发布"推迟/跳过本次"这类开之前的决策事件（不含实例，因为还没创建实例）。 */
    private void publishOccurrence(UUID companionId, String event, String typeId, long day,
                                   String reason, long retryTicks) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companionId", companionId.toString());
        data.put("scope", TaskScope.SIDE.name());
        data.put("typeId", typeId);
        data.put("gameDay", day);
        data.put("reason", reason == null ? "" : reason);
        if (retryTicks > 0) {
            data.put("retryTicks", retryTicks);
        }
        events.publish(event, data);
    }

    private void advance(SideTask task, SideTaskWorldPort world) {
        SideTaskType type = types.get(task.typeId());
        if (type == null) {
            finish(task, SideTaskState.CANCELLED, "unknown side task type");
            return;
        }
        SideTaskVerdict verdict = type.evaluate(task, world);
        switch (verdict.kind()) {
            case CONTINUE -> {
                SideTask updated = task.withPhase(verdict.nextPhase()).withData(verdict.data());
                runtime.replace(updated);
                if (!Objects.equals(task.phase(), updated.phase())) {
                    Map<String, Object> data = base(updated);
                    data.put("phase", updated.phase());
                    events.publish("side_task_progress", data);
                }
            }
            case COMPLETE -> finish(task.withData(verdict.data()), SideTaskState.COMPLETED, verdict.evidence());
            case SKIP -> finish(task.withData(verdict.data()), SideTaskState.SKIPPED_TIMEOUT, verdict.evidence());
            case CANCEL -> finish(task.withData(verdict.data()), SideTaskState.CANCELLED, verdict.evidence());
        }
    }

    private void finish(SideTask task, SideTaskState state, String reason) {
        runtime.remove(task.companionId());
        String event = switch (state) {
            case COMPLETED -> "side_task_completed";
            case SKIPPED_TIMEOUT -> "side_task_skipped";
            default -> "side_task_cancelled";
        };
        Map<String, Object> data = base(task);
        data.put("state", state.name());
        data.put("reason", reason == null ? "" : reason);
        data.put("reason", reason == null ? "" : reason);
        events.publish(event, data);
        resumeMainline(task);
    }

    private void resumeMainline(SideTask task) {
        MainlineResumeToken token = task.mainlineToken();
        boolean valid = token != null && (mainlineValidator == null || mainlineValidator.validate(token) != null);
        Map<String, Object> data = base(task);
        data.put("mainlineResumed", valid);
        events.publish("side_task_resumed_mainline", data);
    }

    /**
     * 执行 AI 自己放弃当前支线（2026-10-28，主人要的「支线可跳过」）。
     *
     * <p><b>为什么必须有这个出口</b>：支线文案一直是「你现在最高优先的目标」，
     * 执行侧没有任何拒绝的通道 —— 于是「那里太危险 / 我连工具都没有 / 我过去了也捡不回来」
     * 这类只有到现场才知道的判断，只能被无视。主人原话：
     * 「这个支线任务是发给你了，但是你要不要去捡是你决定的……万一那里很危险，我回去了也没有用」。
     *
     * <p><b>为什么带 worldId / gameDay</b>：拒绝之后<b>本游戏日不再重开同型支线</b> ——
     * 否则引擎下一拍照旧触发，「拒绝」等于没拒绝。去重键与 {@link #handledGameDay} 完全一致。
     * 传 {@code worldId=null} / {@code gameDay<0}（拿不到世界信息）时只拒绝这一次、不锁当天，
     * 这是刻意的降级：宁可它下一拍再来问，也不要凭一个猜的日子把整晚的支线全锁死。
     *
     * @return 是否真的拒绝了一条活跃支线（没有活跃支线 → false，调用方据此回执，不静默成功）
     */
    public boolean decline(UUID companionId, String reason, String worldId, long gameDay) {
        Objects.requireNonNull(companionId, "companionId");
        Optional<SideTask> active = runtime.current(companionId);
        if (active.isEmpty()) {
            return false;
        }
        SideTask task = active.get();
        runtime.remove(companionId);
        // 只有按日去重的支线才把"拒绝"记成当天不再问；死亡恢复按 deathSeq 走，不锁当天。
        SideTaskTrigger trigger = triggerOf(task.typeId());
        if (worldId != null && gameDay >= 0 && (trigger == null || trigger.oncePerGameDay())) {
            handledGameDay.put(handledKey(companionId, worldId, task.typeId()), gameDay);
        }
        Map<String, Object> data = base(task);
        data.put("state", SideTaskState.DECLINED.name());
        data.put("reason", reason == null ? "" : reason);
        data.put("decidedBy", "executor");
        events.publish("side_task_declined", data);
        resumeMainline(task);
        return true;
    }

    /** 外部取消（主人暂停/换目标/清目标/世界切换）。 */
    public void cancel(UUID companionId, String reason) {
        runtime.current(companionId).ifPresent(task -> {
            runtime.remove(companionId);
            Map<String, Object> data = base(task);
            data.put("reason", reason == null ? "" : reason);
            events.publish("side_task_cancelled", data);
        });
    }

    public void onServerStopped() {
        runtime.clearAll();
        handledGameDay.clear();
        deferredUntil.clear();
    }

    private boolean tickRecurring(UUID companionId, SideTaskWorldPort world,
                                  MainlineResumeToken mainlineToken, long day, long now) {
        for (var e : recurringSpecs.entrySet()) {
            RecurringSideTaskSpec spec = e.getValue();
            String typeId = recurringTypes.get(e.getKey());
            if (!spec.dueOn(day)) continue;
            MainlineResumeToken token = null;
            boolean plannerDaily = spec.scheduleId().startsWith("daily:");
            if (plannerDaily) {
                // 2026-11-12: Host shares this engine across goals. Qualify before prompt/due/handled too.
                // Use the live validator, not a companion guessed from the goal id or a second owner table.
                if (mainlineToken == null || !companionId.equals(mainlineToken.companionId())
                        || mainlineValidator == null) continue;
                token = mainlineValidator.validate(mainlineToken);
                if (token == null || !companionId.equals(token.companionId())
                        || token.goalId() == null || token.goalId().isBlank()) continue;
                String goalPrefix = "daily:" + token.goalId() + ":";
                // Exact last boundary keeps goal-b4 separate from goal-b49 and goal-b4:child.
                if (!spec.scheduleId().startsWith(goalPrefix)
                        || spec.scheduleId().lastIndexOf(':') != goalPrefix.length() - 1
                        || spec.scheduleId().length() == goalPrefix.length()) continue;
            }
            String key = companionId + "|" + world.worldId() + "|" + spec.scheduleId() + "|" + day;
            Long handled = recurringHandled.get(key);
            if (handled != null && handled == day) {
                System.out.println("[rdd] daily handled spec=" + spec.scheduleId() + " day=" + day
                        + " interval=" + spec.intervalGameDays() + " anchor=" + spec.anchorGameDay());
                continue;
            }
            if (spec.promptOnly()) {
                recurringHandled.put(key, day);
                Map<String, Object> due = new LinkedHashMap<>();
                due.put("companionId", companionId.toString());
                due.put("specId", spec.scheduleId());
                due.put("gameDay", day);
                due.put("promptOnly", true);
                System.out.println("[rdd] daily due spec=" + spec.scheduleId() + " day=" + day
                        + " interval=" + spec.intervalGameDays() + " anchor=" + spec.anchorGameDay());
                events.publish("side_task_due", due);
                continue;
            }
            SideTaskType type = typeId == null ? null : types.get(typeId);
            if (type == null) continue;
            System.out.println("[rdd] recurring create spec=" + spec.scheduleId() + " dataKeys=" + spec.data().keySet());
            if (!plannerDaily) token = mainlineValidator == null ? null : mainlineValidator.validate(mainlineToken);
            long generation = runtime.nextGeneration();
            SideTask task = type.create(new SideTaskType.CreationContext(
                    companionId, typeId + "-" + generation + "-" + shortId(companionId), generation,
                    day, now, now + type.deadlineTicks(), token, spec.data()));
            if (runtime.activate(task)) {
                recurringHandled.put(key, day);
                Map<String, Object> due = base(task);
                due.put("specId", spec.scheduleId());
                due.put("gameDay", day);
                events.publish("side_task_due", due);
                events.publish("side_task_created", base(task));
                events.publish("side_task_activated", base(task));
                return true;
            }
        }
        return false;
    }

    private static String handledKey(UUID companionId, String worldId, String typeId) {
        return companionId + "|" + worldId + "|" + typeId;
    }

    private static String shortId(UUID companionId) {
        return companionId.toString().substring(0, 8);
    }

    private Map<String, Object> base(SideTask task) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companionId", task.companionId().toString());
        data.put("scope", TaskScope.SIDE.name());
        data.put("typeId", task.typeId());
        data.put("instanceId", task.instanceId());
        data.put("generation", task.generation());
        data.put("gameDay", task.scheduledGameDay());
        data.put("state", task.state().name());
        data.put("phase", task.phase());
        if (task.data().containsKey("death_seq")) {
            data.put("deathSeq", task.data().get("death_seq"));
            data.put("deathRecoveryTerminalReason", task.data().getOrDefault("terminal_reason", ""));
        }
        if (task.mainlineToken() != null) {
            data.put("mainlineGoalId", task.mainlineToken().goalId());
            data.put("mainlinePrimaryId", task.mainlineToken().primaryId());
            data.put("mainlineSubtaskId", task.mainlineToken().subtaskId());
        }
        return data;
    }

    // ---- 持久化（整个引擎共享一份；宿主自行决定文件位置） ----

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 3);
        root.add("runtime", runtime.toJsonObject());
        JsonObject handled = new JsonObject();
        for (Map.Entry<String, Long> e : handledGameDay.entrySet()) {
            handled.addProperty(e.getKey(), e.getValue());
        }
        root.add("handledGameDay", handled);
        JsonObject deferred = new JsonObject();
        for (Map.Entry<String, Long> e : deferredUntil.entrySet()) {
            deferred.addProperty(e.getKey(), e.getValue());
        }
        root.add("deferredUntil", deferred);
        JsonObject recurring = new JsonObject();
        for (Map.Entry<String, Long> e : recurringHandled.entrySet()) {
            recurring.addProperty(e.getKey(), e.getValue());
        }
        root.add("recurringHandled", recurring);
        root.add("recurringSpecs", GSON.toJsonTree(recurringSpecs));
        root.add("recurringTypes", GSON.toJsonTree(recurringTypes));
        return GSON.toJson(root);
    }

    public void restore(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        // Validate the added snapshot before mutating existing runtime/handled state.
        // Schema 2 had no templates: absence is UNKNOWN, not evidence of a restored schedule.
        boolean hasSpecs = root.has("recurringSpecs");
        boolean hasTypes = root.has("recurringTypes");
        Map<String, RecurringSideTaskSpec> savedSpecs = new LinkedHashMap<>();
        Map<String, String> savedTypes = new LinkedHashMap<>();
        if (hasSpecs != hasTypes || (root.has("schema") && root.get("schema").getAsInt() >= 3 && !hasSpecs)) {
            throw new IllegalArgumentException("incomplete recurring schedule snapshot");
        }
        if (hasSpecs) {
            if (!root.get("recurringSpecs").isJsonObject() || !root.get("recurringTypes").isJsonObject()) {
                throw new IllegalArgumentException("recurring schedule snapshot must contain two objects");
            }
            for (var entry : root.getAsJsonObject("recurringSpecs").entrySet()) {
                if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("invalid recurring spec: " + entry.getKey());
                JsonObject record = entry.getValue().getAsJsonObject();
                for (String field : List.of("scheduleId", "templateId", "intervalGameDays", "anchorGameDay",
                        "missedPolicy", "enabled", "promptOnly", "data")) {
                    if (!record.has(field) || record.get(field).isJsonNull()) {
                        throw new IllegalArgumentException("recurring spec missing " + field + ": " + entry.getKey());
                    }
                }
                if (!record.get("data").isJsonObject()
                        || !record.get("scheduleId").isJsonPrimitive() || !record.getAsJsonPrimitive("scheduleId").isString()
                        || !record.get("templateId").isJsonPrimitive() || !record.getAsJsonPrimitive("templateId").isString()
                        || !record.get("intervalGameDays").isJsonPrimitive() || !record.getAsJsonPrimitive("intervalGameDays").isNumber()
                        || !record.get("anchorGameDay").isJsonPrimitive() || !record.getAsJsonPrimitive("anchorGameDay").isNumber()
                        || !record.get("missedPolicy").isJsonPrimitive() || !record.getAsJsonPrimitive("missedPolicy").isString()
                        || !record.get("enabled").isJsonPrimitive() || !record.getAsJsonPrimitive("enabled").isBoolean()
                        || !record.get("promptOnly").isJsonPrimitive() || !record.getAsJsonPrimitive("promptOnly").isBoolean()) {
                    throw new IllegalArgumentException("invalid recurring spec fields: " + entry.getKey());
                }
                for (var datum : record.getAsJsonObject("data").entrySet()) {
                    if (!datum.getValue().isJsonPrimitive() || !datum.getValue().getAsJsonPrimitive().isString()) {
                        throw new IllegalArgumentException("invalid recurring payload: " + entry.getKey());
                    }
                }
                try {
                    record.getAsJsonPrimitive("intervalGameDays").getAsBigDecimal().intValueExact();
                    record.getAsJsonPrimitive("anchorGameDay").getAsBigDecimal().longValueExact();
                    RecurringSideTaskSpec spec = GSON.fromJson(record, RecurringSideTaskSpec.class);
                    if (!entry.getKey().equals(spec.scheduleId()) || spec.scheduleId().isBlank()
                            || spec.templateId().isBlank() || spec.intervalGameDays() <= 0 || spec.missedPolicy() == null) {
                        throw new IllegalArgumentException("invalid recurring spec identity or schedule: " + entry.getKey());
                    }
                    savedSpecs.put(entry.getKey(), spec);
                } catch (RuntimeException ex) {
                    throw new IllegalArgumentException("invalid recurring spec: " + entry.getKey(), ex);
                }
            }
            for (var entry : root.getAsJsonObject("recurringTypes").entrySet()) {
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()
                        || entry.getValue().getAsString().isBlank()) {
                    throw new IllegalArgumentException("invalid recurring type: " + entry.getKey());
                }
                savedTypes.put(entry.getKey(), entry.getValue().getAsString());
            }
            if (!savedSpecs.keySet().equals(savedTypes.keySet())) {
                throw new IllegalArgumentException("recurring specs and types must have matching schedule ids");
            }
        }
        if (root.has("runtime")) {
            runtime.restore(root.getAsJsonObject("runtime"));
        }
        handledGameDay.clear();
        if (root.has("handledGameDay")) {
            for (var entry : root.getAsJsonObject("handledGameDay").entrySet()) {
                handledGameDay.put(entry.getKey(), entry.getValue().getAsLong());
            }
        }
        recurringHandled.clear();
        if (root.has("recurringHandled")) {
            for (var entry : root.getAsJsonObject("recurringHandled").entrySet()) {
                recurringHandled.put(entry.getKey(), entry.getValue().getAsLong());
            }
        }
        if (hasSpecs) {
            recurringSpecs.clear();
            recurringSpecs.putAll(savedSpecs);
            recurringTypes.clear();
            recurringTypes.putAll(savedTypes);
        }
        deferredUntil.clear();
        if (root.has("deferredUntil")) {
            for (var entry : root.getAsJsonObject("deferredUntil").entrySet()) {
                deferredUntil.put(entry.getKey(), entry.getValue().getAsLong());
            }
        }
    }
}
