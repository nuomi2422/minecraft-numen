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

    public SideTaskType typeOf(String typeId) {
        return types.get(typeId);
    }

    public Optional<SideTask> current(UUID companionId) {
        return runtime.current(companionId);
    }

    public boolean isActive(UUID companionId) {
        return runtime.isActive(companionId);
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
            SideTaskTrigger source = null;
            for (SideTaskTrigger t : triggers) {
                if (t.typeId().equals(running.typeId())) {
                    source = t;
                    break;
                }
            }
            // 只针对**世界能力变了**这一种（换到没有昼夜循环的维度：下界/末地）。
            // 注意不能因为"时间走出窗口"就取消 —— 那是 deadline 的职责（要 SKIP 而不是 CANCELLED），
            // 我第一版写宽了，直接把 deadlineSkipsInsteadOfFailing 这条测试打红。
            if (!world.hasDayNightCycle()) {
                cancel(companionId, "world has no day-night cycle (" + world.worldId() + "); side task can never finish");
                return;
            }
            advance(active.get(), world);
            return;
        }
        if (!world.hasDayNightCycle() || deathSideTaskOccupying) {
            return;
        }
        long now = world.gameTime();
        long day = world.gameDay();
        for (SideTaskTrigger trigger : triggers) {
            String key = handledKey(companionId, world.worldId(), trigger.typeId());
            Long handled = handledGameDay.get(key);
            if (handled != null && handled == day) {
                continue;
            }
            // 推迟冷却：未到点不重新决策（避免每拍重复决策）。
            Long until = deferredUntil.get(key);
            if (until != null && now < until) {
                continue;
            }
            if (!trigger.fires(new SideTaskTrigger.Context(
                    companionId, world.worldId(), day, world.dayTime(), world.hasDayNightCycle()))) {
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
                deferredUntil.put(key, now + Math.max(decision.deferTicks(), MIN_DEFER_TICKS));
                publishOccurrence(companionId, "side_task_deferred", trigger.typeId(), day,
                        decision.reason(), Math.max(decision.deferTicks(), MIN_DEFER_TICKS));
                continue;
            }
            if (decision.kind() == SideTaskDecision.Kind.SKIP) {
                deferredUntil.remove(key);
                handledGameDay.put(key, day);
                publishOccurrence(companionId, "side_task_skipped_occurrence", trigger.typeId(), day,
                        decision.reason(), 0L);
                continue;
            }
            // EXECUTE：清掉可能残留的推迟，照旧创建。
            deferredUntil.remove(key);
            MainlineResumeToken token = mainlineValidator == null ? null : mainlineValidator.validate(mainlineToken);
            long generation = runtime.nextGeneration();
            String instanceId = trigger.typeId() + "-" + generation + "-" + shortId(companionId);
            SideTask task = type.create(new SideTaskType.CreationContext(
                    companionId, instanceId, generation, day, now,
                    now + type.deadlineTicks(), token, Map.of()));
            if (runtime.activate(task)) {
                handledGameDay.put(key, day);
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
            case COMPLETE -> finish(task, SideTaskState.COMPLETED, verdict.evidence());
            case SKIP -> finish(task, SideTaskState.SKIPPED_TIMEOUT, verdict.evidence());
            case CANCEL -> finish(task, SideTaskState.CANCELLED, verdict.evidence());
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
        if (worldId != null && gameDay >= 0) {
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
        root.addProperty("schema", 2);
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
        return GSON.toJson(root);
    }

    public void restore(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("runtime")) {
            runtime.restore(root.getAsJsonObject("runtime"));
        }
        handledGameDay.clear();
        if (root.has("handledGameDay")) {
            for (var entry : root.getAsJsonObject("handledGameDay").entrySet()) {
                handledGameDay.put(entry.getKey(), entry.getValue().getAsLong());
            }
        }
        deferredUntil.clear();
        if (root.has("deferredUntil")) {
            for (var entry : root.getAsJsonObject("deferredUntil").entrySet()) {
                deferredUntil.put(entry.getKey(), entry.getValue().getAsLong());
            }
        }
    }
}
