package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.side.MainlineResumeToken;
import com.dwinovo.numen.rdd.side.SideTask;
import com.dwinovo.numen.rdd.side.SideTaskEngine;
import com.dwinovo.numen.rdd.side.SideTaskType;
import com.dwinovo.numen.rdd.side.SideTaskWorldPort;
import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskTrigger;
import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskType;
import com.dwinovo.numen.rdd.side.death.DeathRecoverySideTaskTrigger;
import com.dwinovo.numen.rdd.side.death.DeathRecoverySideTaskType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 睡觉支线的宿主接线（2026-10-08，第一版）。
 *
 * <p>通用框架在 rdd-core（{@link SideTaskEngine} + {@link SideTaskWorldPort} + 睡觉类型/触发器）；
 * 这里只做 MC 侧：造世界端口、喂主线恢复令牌、把支线上下文缓存给执行 AI、发监测台事件、落盘/恢复。
 *
 * <p><b>本轮只接睡觉一条支线</b>；框架本身与具体支线无关，加新支线 = 注册新的 type + trigger。
 */
final class SideTaskHost {

    private static final Logger LOG = LoggerFactory.getLogger(SideTaskHost.class);
    private static final SleepSideTaskType SLEEP_TYPE = new SleepSideTaskType();
    private static final DeathRecoverySideTaskType DEATH_RECOVERY_TYPE = new DeathRecoverySideTaskType();
    private static final SideTaskEngine ENGINE =
            new SideTaskEngine(SideTaskHost::publish, SideTaskHost::validateMainline);
    /** 支线上下文缓存：主线程 tick 写入，上下文构建只读（可能在非主线程被调）。 */
    private static final SideContextStore SIDE_CONTEXT = new SideContextStore();
    /** 支线拍醒的重复间隔（tick，约 4 分钟）：支线 active 期间至少这么久再补一次提醒。 */
    private static final long NUDGE_REPEAT_TICKS = 4L * 60L * 20L;
    private static final Map<UUID, Long> LAST_NUDGE_GAME_TIME = new ConcurrentHashMap<>();
    /** 2026-11-08: after a food task settles, do not rebuild for 5 real minutes. */
    private static final Map<UUID, Long> LAST_FOOD_DONE_MS = new ConcurrentHashMap<>();
    private static final long FOOD_COOLDOWN_MS = 5L * 60L * 1000L;
    /** SURVIVAL report forces one food task even if thresholds are not otherwise met. */
    private static final java.util.Set<UUID> FORCED_FOOD = ConcurrentHashMap.newKeySet();

    /** Called by report_task_concern(kind=SURVIVAL): request a food side task now. */
    static void requestFoodSupply(com.dwinovo.numen.entity.NumenPlayer ap, String reason) {
        if (ap == null) return;
        UUID id = ap.getUUID();
        if ("food_supply".equals(currentTypeId(id))) return;
        long nowMs = System.currentTimeMillis();
        HostWorldPort port = new HostWorldPort(ap);
        if (com.dwinovo.numen.rdd.side.FoodSupplyPolicy.cooldownBlocks(
                com.dwinovo.numen.rdd.side.FoodSupplyPolicy.cooldownActive(
                        nowMs, LAST_FOOD_DONE_MS.getOrDefault(id, 0L)),
                port.hunger(id), port.health(id), port.edibleCount(id))) return;
        LAST_FOOD_DONE_MS.put(id, nowMs); // claim the slot before the async report can race
        com.dwinovo.numen.task.CompanionTickDispatcher.stopActive(ap,
                "SURVIVAL report: " + (reason == null ? "" : reason));
        RddMonitor.publish("survival_food_requested", java.util.Map.of(
                "companionId", id.toString(), "reason", reason == null ? "" : reason));
        if (ENGINE.isActive(id)) {
            ENGINE.cancel(id, "SURVIVAL food supply takes precedence");
        }
        boolean started = ENGINE.startNow(new com.dwinovo.numen.rdd.side.FoodSupplySideTaskType(), id, port,
                currentMainlineToken(id), java.util.Map.of("reason", reason == null ? "" : reason));
        if (!started) FORCED_FOOD.add(id);
    }
    private static volatile Path sideDir;

    static {
        ENGINE.registerType(SLEEP_TYPE);
        ENGINE.registerTrigger(new SleepSideTaskTrigger());
        // 2026-10-23：恢复支线（主人在意的"丢了东西就插支线，不重写主线"）。
        ENGINE.registerType(new com.dwinovo.numen.rdd.side.recovery.RecoverySideTaskType());
        ENGINE.registerTrigger(new com.dwinovo.numen.rdd.side.recovery.RecoverySideTaskTrigger());
        ENGINE.registerType(DEATH_RECOVERY_TYPE);
        ENGINE.registerType(new com.dwinovo.numen.rdd.side.DailyMaintenanceSideTaskType());
        ENGINE.registerType(new com.dwinovo.numen.rdd.side.FoodSupplySideTaskType());
        ENGINE.registerTrigger(new com.dwinovo.numen.rdd.side.FoodSupplySideTaskTrigger(ctx ->
                !com.dwinovo.numen.rdd.side.FoodSupplyPolicy.cooldownBlocks(
                        com.dwinovo.numen.rdd.side.FoodSupplyPolicy.cooldownActive(
                                System.currentTimeMillis(), LAST_FOOD_DONE_MS.getOrDefault(ctx.companionId(), 0L)),
                        ctx.hunger(), ctx.health(), ctx.edibleCount())));
        ENGINE.registerTrigger(new DeathRecoverySideTaskTrigger());
        SleepWakeWatcher.install();
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> onServerStopped());
    }

    private SideTaskHost() {
    }

    static void setup(Path configDir) {
        sideDir = configDir == null ? null : configDir.resolve("rdd-side");
        // ★ 可逆开关（2026-10-26）：config/numen/side-tasks.json 的 "sleep": false 可暂停睡觉支线。
        //   缺文件/坏文件 = 保持默认开（不因读不到就悄悄改行为）。
        try {
            if (configDir != null) {
                Path f = configDir.resolve("side-tasks.json");
                if (java.nio.file.Files.isRegularFile(f)) {
                    String raw = java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8);
                    var o = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
                    if (o.has("sleep") && o.get("sleep").isJsonPrimitive()) {
                        boolean on = o.get("sleep").getAsBoolean();
                        SleepSideTaskTrigger.setEnabled(on);
                        LOG.info("[rdd-side] sleep side task enabled={} (from side-tasks.json)", on);
                    }
                }
            }
        } catch (RuntimeException | java.io.IOException ex) {
            LOG.warn("[rdd-side] side-tasks.json 读取失败，保持默认（sleep=on）: {}", ex.toString());
        }
        // ★ 2026-10-27（权利缺口②）：学习者派的支线——扫 artifact-outbox/SIDE_TASK
        //   为每条 PENDING 草稿注册触发器+类型。只支持 is_dark 条件；不支持的字段
        //   被拒收并写进日志（不静默丢弃）。重复调用幂等（registerType 同名覆盖、
        //   registerTrigger 追加——引擎每游戏日只开一次同型支线，天然防重）。
        if (sideDir != null) {
            try {
                LearnerSideTaskLoader.loadAll(sideDir.getParent().resolve("artifact-outbox"), ENGINE);
            } catch (Throwable t) {
                LOG.warn("[rdd-side] learner SIDE_TASK 导入失败（不阻塞支线宿主）: {}", t.toString());
            }
        }
        restore();
    }

    /** 2026-11-08: planner DAILY nodes become real executable recurring side tasks. */
    static void registerDailySpecs(com.dwinovo.numen.rdd.core.TaskChain chain) {
        DailyRegistration.registerChain(chain, ENGINE);
        persist();
    }

    /** Restore registration is separate from explicit new-plan updates. */
    static void restoreDailySpecs(TaskChain chain) {
        DailyRegistration.restoreChain(chain, ENGINE);
        persist();
    }

    /** 2026-11-12: keep legal DAILY candidates even when mainline installation is rejected. */
    static void registerDailyCandidates(com.dwinovo.numen.rdd.core.TaskChain chain,
                                       java.util.List<Subtask> candidates) {
        DailyRegistration.register(chain, candidates, ENGINE);
        persist(); // a rejected main plan still owns a durable legal DAILY registration
    }

    /** Pure registration seam: loading it does not install MC event listeners. */
    static final class DailyRegistration {
        private static final Logger LOG = LoggerFactory.getLogger(DailyRegistration.class);

        static void restoreChain(TaskChain chain, SideTaskEngine engine) {
            registerChain(chain, engine, true);
        }

        static void registerChain(TaskChain chain, SideTaskEngine engine) {
            registerChain(chain, engine, false);
        }

        private static void registerChain(TaskChain chain, SideTaskEngine engine, boolean missingOnly) {
            if (chain == null) return;
            register(chain, chain.goal().primaryGoals().stream()
                    .flatMap(primary -> primary.subtasks().stream()).toList(), engine, missingOnly);
        }

        static void register(com.dwinovo.numen.rdd.core.TaskChain chain,
                             java.util.List<Subtask> candidates, SideTaskEngine engine) {
            register(chain, candidates, engine, false);
        }

        private static void register(TaskChain chain, java.util.List<Subtask> candidates,
                                     SideTaskEngine engine, boolean missingOnly) {
            try {
                if (chain == null || chain.goal() == null) return;
                java.util.List<com.dwinovo.numen.rdd.side.RecurringSideTaskSpec> built = new java.util.ArrayList<>();
                for (var sub : candidates) {
                    if (sub.nodeType() != com.dwinovo.numen.rdd.api.NodeType.DAILY || sub.recurrence() == null) continue;
                    var rec = sub.recurrence();
                    var data = new java.util.LinkedHashMap<String, String>();
                    data.put("description", sub.description());
                    Object key0 = sub.condition().get("asset_key");
                    LOG.info("[rdd] daily candidate {} type={} condition={} asset_key0={}", sub.id(),
                            sub.condition().get("type"), sub.condition(), key0);
                    Object key = key0;
                    if (!(key instanceof String) || ((String) key).isBlank()) key = sub.condition().get("block");
                    if (!(key instanceof String) || ((String) key).isBlank()) key = sub.condition().get("item");
                    if (!(key instanceof String) || ((String) key).isBlank()) {
                        LOG.info("[rdd] daily candidate {} skipped: no countable asset_key/block/item", sub.id());
                        continue; // no countable asset -> not an executable daily
                    }
                    data.put("asset_key", (String) key);
                    Object min = sub.condition().get("minimum");
                    data.put("minimum", String.valueOf(min instanceof Number n ? Math.max(1, n.intValue()) : 1));
                    data.put("requires_farm_built", String.valueOf(rec.requiresFarmBuilt()));
                    LOG.info("[rdd] daily spec {} asset={} min={}", sub.id(), key, data.get("minimum"));
                    // stable across replans: description hash, not sub.id/planRevision
                    String specId = "daily:" + chain.goal().id() + ":" + Integer.toHexString(sub.description().hashCode());
                    LOG.info("[rdd] daily spec register {} interval={} anchor={}", specId, rec.intervalGameDays(), chain.planRevision());
                    built.add(new com.dwinovo.numen.rdd.side.RecurringSideTaskSpec(
                            specId, com.dwinovo.numen.rdd.side.DailyMaintenanceSideTaskType.TYPE_ID,
                            rec.intervalGameDays(), chain.planRevision(),
                            rec.missedPolicy() == com.dwinovo.numen.rdd.api.RecurrenceSpec.MissedPolicy.CATCH_UP
                                    ? com.dwinovo.numen.rdd.side.RecurringSideTaskSpec.MissedPolicy.CATCH_UP
                                    : com.dwinovo.numen.rdd.side.RecurringSideTaskSpec.MissedPolicy.SKIP,
                            true, false, data));
                }
                if (built.isEmpty()) {
                    LOG.info("[rdd] daily specs: chain has no executable DAILY; keep existing specs");
                    return;
                }
                for (var spec : built) {
                    var previous = engine.recurringSpec(spec.scheduleId());
                    // 2026-11-12: a saved chain may predate a newer rejected DAILY candidate snapshot.
                    if (missingOnly && previous != null) continue;
                    // Same template retry keeps its due day; a different template is an explicit replacement.
                    if (previous != null && spec.templateId().equals(previous.templateId())) {
                        spec = new com.dwinovo.numen.rdd.side.RecurringSideTaskSpec(
                                spec.scheduleId(), spec.templateId(), spec.intervalGameDays(), previous.anchorGameDay(),
                                spec.missedPolicy(), spec.enabled(), spec.promptOnly(), spec.data());
                    }
                    engine.registerRecurring(spec, com.dwinovo.numen.rdd.side.DailyMaintenanceSideTaskType.TYPE_ID);
                }
            } catch (RuntimeException ignored) {
                // spec registration must never break chain creation
            }
        }
    }

    static boolean isActive(UUID companionId) {
        return companionId != null && ENGINE.isActive(companionId);
    }

    static boolean isDeathRecoveryActive(UUID companionId) {
        return companionId != null
                && DeathRecoverySideTaskTrigger.TYPE_ID.equals(currentTypeId(companionId));
    }

    /** Point a death ledger batch at the protected internal recovery task. */
    static void expectDeathRecovery(UUID companionId, int deathSeq, String dimension,
                                    String deathAt, Map<String, Integer> lostItems) {
        DeathRecoverySideTaskTrigger.expect(companionId, deathSeq, dimension, deathAt, lostItems);
    }

    /** 执行 AI 上下文：支线 active 时返回完整 {@code <rdd>…</rdd>}，否则 null。只读缓存。 */
    /** Pure seam: stale/missing identity must not leak the old block. */
    static String decorateContext(String block, String cachedWorldId, String cachedSession,
                                  String dimension, long sampledGameTime,
                                  String currentWorldId, String currentSession) {
        return SideContextDecorator.decorate(block, cachedWorldId, cachedSession, dimension, sampledGameTime,
                currentWorldId, currentSession);
    }

    static String contextFor(UUID companionId) {
        // No current request identity at this call site: fail closed, never use the global stamp.
        return contextFor(companionId, null, null);
    }

    static String contextFor(UUID companionId, String currentWorldId, String currentSession) {
        if (companionId == null) return null;
        return SIDE_CONTEXT.render(companionId, currentWorldId, currentSession);
    }

    /** 当前支线类型 id（无支线 → null）：给"拒绝"工具做旧回执校验（防拒错一条）。 */
    static String currentTypeId(UUID companionId) {
        if (companionId == null) {
            return null;
        }
        return ENGINE.current(companionId).map(SideTask::typeId).orElse(null);
    }

    /**
     * 执行 AI 自己放弃当前支线（2026-10-28，主人第三点：「要不要去捡是你决定的」）。
     *
     * <p>宿主这一层只做三件收尾：把世界信息（维度 + 游戏日）递进去（拒绝之后当天别再问同一件事）、
     * 清掉上下文缓存、落盘。判定与事件全在 {@code SideTaskEngine.decline} —— 纯 JVM 那边可单测。
     */
    static boolean chooseDeathRecovery(UUID companionId, String decision, String reason) {
        if (companionId == null || !isDeathRecoveryActive(companionId)) return false;
        com.dwinovo.numen.rdd.side.death.DeathRecoveryDecision parsed =
                com.dwinovo.numen.rdd.side.death.DeathRecoveryDecision.parse(decision);
        if (parsed == null) return false;
        boolean set = com.dwinovo.numen.rdd.side.death.DeathPreparation.choose(ENGINE, companionId, parsed);
        if (set) {
            NumenPlayer body = RddCompanionBody.get(companionId);
            if (body != null) tickDeathPreparation(body, new HostWorldPort(body));
        }
        if (set) {
            RddMonitor.publish("death_recovery_decision", Map.of(
                    "companionId", companionId.toString(),
                    "decision", parsed.name(),
                    "reason", reason == null ? "" : reason));
            persist();
        }
        return set;
    }

    static boolean decline(UUID companionId, String reason) {
        if (companionId == null) {
            return false;
        }
        String worldId = null;
        long gameDay = -1L;
        try {
            NumenPlayer body = RddCompanionBody.get(companionId);
            if (body != null && body.level() != null) {
                worldId = body.level().dimension().location().toString();
                gameDay = Math.floorDiv(body.level().getDayTime(), 24_000L);
            }
        } catch (RuntimeException ignored) {
            // 拿不到世界信息 → 引擎按"只拒这一次"降级（不猜日子、不锁整晚）
        }
        boolean declined = ENGINE.decline(companionId, reason, worldId, gameDay);
        SIDE_CONTEXT.remove(companionId);
        LAST_NUDGE_GAME_TIME.remove(companionId);
        persist();
        return declined;
    }

    /** 宿主每拍（约 20 tick）调用。 */
    static void tick(MinecraftServer server, NumenPlayer ap) {
        UUID companionId = ap.getUUID();
        try {
            HostWorldPort port = new HostWorldPort(ap);
            boolean wasActive = ENGINE.isActive(companionId);
            String wasType = currentTypeId(companionId);
            boolean foodCooldown = com.dwinovo.numen.rdd.side.FoodSupplyPolicy.cooldownBlocks(
                    com.dwinovo.numen.rdd.side.FoodSupplyPolicy.cooldownActive(
                            System.currentTimeMillis(), LAST_FOOD_DONE_MS.getOrDefault(companionId, 0L)),
                    port.hunger(companionId), port.health(companionId), port.edibleCount(companionId));
            boolean foodCritical = !foodCooldown && !ENGINE.isActive(companionId)
                    && (FORCED_FOOD.contains(companionId) || foodCritical(port, companionId));
            if (foodCritical) {
                com.dwinovo.numen.task.CompanionTickDispatcher.stopActive(ap,
                        "survival food supply preempts mainline");
            }
            if (!wasActive) {
                port.pollWake(companionId); // 无支线时排空陈旧醒来边沿，避免泄漏到下一晚
            }
            MainlineResumeToken token = currentMainlineToken(companionId);
            boolean deathOccupying = RddRepairDispatch.lastRepair(companionId) != null;
            // Preserve death preparation before the single arbitration tick; sleep only adds its handoff.
            tickDeathPreparation(ap, port);
            SleepSafety.tick(ENGINE, companionId, port, token, deathOccupying, () -> {
                // Same server thread: stop ONLY the action whose owner was admitted, not a replacement.
                var action = com.dwinovo.numen.task.CompanionTickDispatcher.currentTaskFor(companionId);
                if (action == null || action != port.sleepAction || !SleepSafety.mayInterrupt(action.isExternalCall(), action.getToolName())
                        || ap.isSleeping() || ap.hurtTime > 0) return false;
                var bodyState = RddPlugin.bodyState(companionId);
                // The API sends client turn invalidation BEFORE stopActive can emit a result.
                if (!com.dwinovo.numen.api.NumenPlugins.interruptForSleep(ap, action)) return false;
                var stopped = action;
                // A natural side interrupt is not a failed mainline attempt. Leave chain/token intact.
                if (token != null && validateMainline(token) != null && bodyState != null
                        && bodyState == RddPlugin.bodyState(companionId)
                        && java.util.Objects.equals(bodyState.subtaskId(), token.subtaskId())) {
                    RddPlugin.clearBody(companionId);
                }
                RddMonitor.publish("sleep_body_interrupted", Map.of("companionId", companionId.toString(),
                        "taskId", stopped.publicId(), "tool", stopped.getToolName(), "reason", "current nearby hostiles"));
                return true;
            });
            boolean nowActive = ENGINE.isActive(companionId);
            if (FORCED_FOOD.contains(companionId) && "food_supply".equals(currentTypeId(companionId))) {
                FORCED_FOOD.remove(companionId);
            }
            if (wasActive && "food_supply".equals(wasType) && !nowActive) {
                LAST_FOOD_DONE_MS.put(companionId, System.currentTimeMillis());
            }
            refreshContext(companionId, port);
            nudgeIfNeeded(companionId, port, wasActive, nowActive);
            persist();
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 支线 tick 异常 {}: {}", companionId, ex.toString());
        }
    }

    /**
     * 支线监督的"拍醒"：支线刚激活时、以及仍 active 时每约 4 分钟补一次提醒。
     *
     * <p>没有这一步，同伴空闲时不会主动取上下文 → 支线等于没人执行
     * （2026-10-08 用户实测：夜前/夜里都触发了，但同伴毫无反应）。这正是"监督要跟着支线走"。
     * {@link RddPlugin#nudge} 自身还有 3 分钟全局节流兜底，这里再加一层 4 分钟间隔防刷屏。
     */
    private static void nudgeIfNeeded(UUID companionId, SideTaskWorldPort port, boolean wasActive, boolean nowActive) {
        if (!nowActive) {
            LAST_NUDGE_GAME_TIME.remove(companionId);
            return;
        }
        long now = port.gameTime();
        Long last = LAST_NUDGE_GAME_TIME.get(companionId);
        if (wasActive && last != null && now - last < NUDGE_REPEAT_TICKS) {
            return;
        }
        String type = currentTypeId(companionId);
        RddPlugin.nudge(companionId, "[支线·" + (type == null ? "unknown" : type) + "·建议] "
                + "当前支线仍在执行，主线暂时冻结。请按 <side_task> 中的事实和有限决策处理；"
                + "若现场判断不值得或无法完成，用 rdd_decline_side 写明观察到的原因，"
                + "不要把未知事实当成已完成或永久丢失。");
        LAST_NUDGE_GAME_TIME.put(companionId, now);
    }

    /** 外部取消（主人暂停/换目标/清目标/同伴移除）。 */
    static void cancel(UUID companionId, String reason) {
        if (companionId == null) {
            return;
        }
        ENGINE.cancel(companionId, reason);
        SIDE_CONTEXT.remove(companionId);
        persist();
    }

    static void onServerStopped() {
        ENGINE.onServerStopped();
        SIDE_CONTEXT.clear();
        SleepWakeWatcher.clearAll();
    }

    /** Existing Stage-B decomposer plans a recovery objective, never the current trading theme. */
    private static void tickDeathPreparation(NumenPlayer ap, HostWorldPort port) {
        UUID id = ap.getUUID();
        if (!isDeathRecoveryActive(id)) return;
        var rt = RddPlugin.runtime(id);
        if (rt == null) return;
        var active = ENGINE.current(id).orElse(null);
        var latestBatch = com.dwinovo.numen.rdd.core.RddDeathLedger.latest(id);
        if (active == null || latestBatch != null
                && !String.valueOf(latestBatch.seq()).equals(active.data().get("death_seq"))) return;
        var semantics = RddSemanticsStore.semantics(id);
        Map<String, String> slots = new java.util.LinkedHashMap<>();
        for (var primary : rt.chain().goal().primaryGoals()) {
            for (var requirement : primary.waitFor()) declareWearSlot(requirement.assetKey(), slots);
            for (var sub : primary.subtasks()) if (sub.condition().get("asset_key") instanceof String key)
                declareWearSlot(key, slots);
        }
        for (var key : semantics.keySet()) declareWearSlot(key, slots);
        com.dwinovo.numen.rdd.side.death.DeathPreparation.attach(ENGINE, id, rt.chain(), semantics, slots);
        com.dwinovo.numen.rdd.side.death.DeathPreparation.tick(ENGINE, id, rt.chain(), port,
                (objective, hint, done) -> {
                    var server = ap.getServer();
                    var ticket = RddPlugin.planningTicket(id);
                    if (server == null) { done.accept(java.util.List.of()); return; }
                    var chain = rt.chain();
                    var recovery = ENGINE.current(id).orElse(null);
                    String deathSeq = recovery == null ? "" : recovery.data().getOrDefault("death_seq", "");
                    RddDecomposer.decomposeSpecsWithHint(id, objective, 0, java.util.List.of(), hint,
                            specs -> RddPlugin.onServer(server, ticket, () -> {
                                var live = RddPlugin.runtime(id);
                                var latest = com.dwinovo.numen.rdd.core.RddDeathLedger.latest(id);
                                if (live != null && live.chain() == chain
                                        && (latest == null || String.valueOf(latest.seq()).equals(deathSeq))) {
                                    done.accept(specs);
                                    persist();
                                }
                            }));
                });
        var preparing = ENGINE.current(id).orElse(null);
        if (preparing != null && "true".equals(preparing.data().get("prepare_required"))
                && !"true".equals(preparing.data().get("prepare_body_stopped"))) {
            com.dwinovo.numen.task.CompanionTickDispatcher.stopActive(ap, "death preparation preempts original action");
            ENGINE.setData(id, "prepare_body_stopped", "true");
        }
    }

    /** Registry-declared armor/shield slots only: no suffix/free-text equipment classifier. */
    private static void declareWearSlot(String key, Map<String, String> slots) {
        if (key == null) return;
        ResourceLocation location = ResourceLocation.tryParse(key);
        if (location == null) return;
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(location);
        if (item instanceof net.minecraft.world.item.ArmorItem armor) slots.put(key, armor.getEquipmentSlot().getName());
        else if (item instanceof net.minecraft.world.item.ShieldItem) slots.put(key, "offhand");
    }

    static SideTaskWorldPort.PreparationFacts deathPreparationResult(UUID id, String payload) {
        var body = RddCompanionBody.get(id);
        var task = com.dwinovo.numen.rdd.side.death.DeathPreparation.resumeTask(payload);
        if (body == null || task == null) return SideTaskWorldPort.PreparationFacts.unknown("recovery_subject_unavailable");
        return new HostWorldPort(body).deathPreparationFacts(id, task);
    }

    /** Called only for recovery_not_verified pauses; fresh equipment evidence is the sole release. */
    static boolean holdRecoveryPause(NumenPlayer ap, TaskChain chain) {
        HostWorldPort port = new HostWorldPort(ap);
        if (!com.dwinovo.numen.rdd.side.death.DeathPreparation.holdPaused(chain, ap.getUUID(), port)) return false;
        if (com.dwinovo.numen.rdd.side.death.DeathPreparation.reopenPaused(ENGINE, ap.getUUID(), chain, port)) {
            tickDeathPreparation(ap, port);
            RddPlugin.nudge(ap.getUUID(), "[恢复复评] 原步骤仍暂停；请按恢复条件补齐当前装备，完成后再继续原主线。");
            persist();
        }
        return true;
    }

    /**
     * Sleep-only host seam: real Engine arbitration precedes one existing task_stop-style cancellation.
     * No new body controller or fact cache; nested pure code is testable without loading NeoForge.
     * The API handoff invalidates the client turn/queue before the exact server stop result.
     */
    static final class SleepSafety {
        static boolean mayInterrupt(boolean external, String tool) {
            if (external || tool == null) return false;
            // Positive allowlist: unknown tools, combat, escape, eating, and sleep retain ownership.
            return java.util.Set.of("mine", "build", "craft", "collect_items", "farm_nearby",
                    "farm_cell", "sow_nearby", "fishing").contains(tool);
        }

        static void tick(SideTaskEngine engine, UUID id, SideTaskWorldPort port,
                         MainlineResumeToken token, boolean death, java.util.function.BooleanSupplier interrupt) {
            boolean wasActive = engine.isActive(id);
            engine.tick(id, port, token, death);
            SideTask now = engine.current(id).orElse(null);
            if (!wasActive && now != null && SleepSideTaskType.TYPE_ID.equals(now.typeId())
                    && !death && !port.isSleeping(id) && !port.inCombat(id) && port.bodyBusy(id)
                    && port.sleepRisk(id) == SideTaskWorldPort.SleepRisk.HOSTILES && port.sleepMayInterrupt(id)) {
                if (!interrupt.getAsBoolean()) engine.cancel(id, "sleep body owner changed before interruption");
            }
        }

        static SideTaskWorldPort.SleepRisk risk(
                com.dwinovo.numen.api.observation.WorldObservationService.Response response,
                String world, String session, String dimension, long nowMs) {
            var unknown = SideTaskWorldPort.SleepRisk.UNKNOWN;
            if (response == null || !"entities".equals(response.topic()) || response.items().size() != 1) return unknown;
            var meta = response.metadata();
            if (!meta.identified() || !meta.worldId().equals(world) || !meta.serverSession().equals(session)
                    || !meta.dimension().equals(dimension)
                    || meta.freshness() != com.dwinovo.numen.api.observation.ObservationMetadata.Freshness.CURRENT
                    || meta.observedAtMs() > nowMs || nowMs - meta.observedAtMs() > 6_000L
                    || meta.validUntilMs() <= nowMs) return unknown;
            var item = response.items().get(0);
            var ready = com.dwinovo.numen.api.observation.Readiness.assess(response.status(), response.coverage(),
                    java.util.Set.of("count"), item.values().keySet(), item.unknownFields(), item.staleFields());
            if (!ready.canReportPresence() || item.unknownFields().contains("") || item.staleFields().contains("")) return unknown;
            Object raw = item.values().get("count");
            if (!(raw instanceof Number count) || !Double.isFinite(count.doubleValue())
                    || count.doubleValue() < 0 || count.doubleValue() != Math.rint(count.doubleValue())) return unknown;
            if (count.doubleValue() > 0) return SideTaskWorldPort.SleepRisk.HOSTILES;
            return ready.canProveAbsence() ? SideTaskWorldPort.SleepRisk.CLEAR : unknown;
        }
    }

    // ---- 世界端口 ----

    private static final class HostWorldPort implements SideTaskWorldPort {
        private final NumenPlayer ap;
        private SleepRisk sleepRisk;
        private com.dwinovo.numen.task.TaskRecord sleepAction;

        HostWorldPort(NumenPlayer ap) {
            this.ap = ap;
        }

        @Override public long gameTime() { return ap.level().getGameTime(); }
        @Override public long dayTime() { return ap.level().getDayTime(); }
        @Override public long gameDay() { return Math.floorDiv(ap.level().getDayTime(), 24_000L); }
        @Override public boolean hasDayNightCycle() { return !ap.level().dimensionType().hasFixedTime(); }
        @Override public String worldId() { return ap.level().dimension().location().toString(); }
        @Override public boolean isSleeping(UUID companionId) { return ap.isSleeping(); }
        @Override public boolean isDay() { return ap.level().isDay(); }
        @Override public int hunger(UUID companionId) {
            try { return ap.getFoodData().getFoodLevel(); } catch (RuntimeException e) { return -1; }
        }
        @Override public int health(UUID companionId) {
            try { return (int) Math.ceil(ap.getHealth()); } catch (RuntimeException e) { return -1; }
        }
        @Override public int edibleCount(UUID companionId) {
            try {
                int n = 0;
                var inv = ap.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    var st = inv.getItem(i);
                    if (!st.isEmpty() && st.get(net.minecraft.core.component.DataComponents.FOOD) != null) {
                        n += st.getCount();
                    }
                }
                return n;
            } catch (RuntimeException e) { return -1; }
        }
        @Override public Wake pollWake(UUID companionId) { return SleepWakeWatcher.poll(companionId); }
        @Override public int countItem(UUID companionId, String itemId) {
            // 2026-10-23 恢复支线：按物品 id 数背包（只读）。解析失败/未知 id → 0。
            try {
                if (itemId == null || itemId.isBlank()) return 0;
                var rl = net.minecraft.resources.ResourceLocation.tryParse(itemId);
                if (rl == null) return 0;
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
                if (item == null || item == net.minecraft.world.item.Items.AIR) return 0;
                var inv = ap.getInventory();
                int n = 0;
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    var s = inv.getItem(i);
                    if (!s.isEmpty() && s.getItem() == item) n += s.getCount();
                }
                return n;
            } catch (RuntimeException ex) {
                return 0;
            }
        }
        @Override public SleepRisk sleepRisk(UUID companionId) {
            if (sleepRisk != null) return sleepRisk;
            sleepRisk = SleepRisk.UNKNOWN;
            try {
                var service = com.dwinovo.numen.api.NumenPlugins.capability(
                        com.dwinovo.numen.api.observation.WorldObservationService.class);
                var identities = com.dwinovo.numen.api.NumenPlugins.capability(
                        com.dwinovo.numen.api.ServerIdentityProvider.class);
                var identity = identities == null ? null : identities.snapshot(ap.level().getServer());
                if (service == null || identity == null) return sleepRisk;
                var response = service.read(new com.dwinovo.numen.api.observation.WorldObservationService.Request(
                        "rdd-sleep", worldId(), "entities", Map.of("box",
                        RddObservation.dangerBox(ap.blockPosition(), RddDangerText.CLOSE_RADIUS),
                        "category", "hostile"), 6_000L, 64, 0L));
                sleepRisk = SleepSafety.risk(response, identity.worldId(), identity.serverSession(),
                        worldId(), System.currentTimeMillis());
            } catch (RuntimeException | LinkageError unavailable) { /* UNKNOWN, no local scan fallback. */ }
            return sleepRisk;
        }
        @Override public boolean sleepMayInterrupt(UUID companionId) {
            var task = com.dwinovo.numen.task.CompanionTickDispatcher.currentTaskFor(companionId);
            if (sleepAction == null) sleepAction = task;
            return task != null && task == sleepAction && SleepSafety.mayInterrupt(task.isExternalCall(), task.getToolName());
        }
        @Override public boolean inCombat(UUID companionId) { return ap.hurtTime > 0; }
        /**
         * 2026-10-30：出生点绑没绑 —— 睡觉支线的新完成判据就靠它
         * （原版：对床右键成功 → ServerPlayer 的 respawnPosition 非空）。
         */
        @Override public boolean isSpawnBound(UUID companionId) { return ap.getRespawnPosition() != null; }
        @Override public boolean bodyBusy(UUID companionId) {
            return com.dwinovo.numen.task.CompanionTickDispatcher.isExecutingAction(companionId);
        }
        @Override public String mainlineObjective(UUID companionId) { return mainlineObjectiveText(companionId); }

        @Override public DeathRecoveryFacts deathRecoveryFacts(UUID companionId, SideTask task) {
            try {
                int seq = Integer.parseInt(task.data().getOrDefault("death_seq", "-1"));
                var death = com.dwinovo.numen.rdd.core.RddDeathLedger.openDeath(companionId, seq);
                if (death == null) {
                    return new DeathRecoveryFacts(DeathDropState.UNKNOWN, false, false, true,
                            "death batch is already closed or unavailable");
                }
                Map<String, Integer> inventory = RddDetector.countInventory(ap);
                if (com.dwinovo.numen.rdd.core.RddDeathLedger.checkBatchRecovery(companionId, seq, inventory).satisfied()) {
                    return new DeathRecoveryFacts(DeathDropState.RECOVERED, false,
                            !RddRepairDispatch.backupFromBase(companionId).isEmpty(), true,
                            "all tracked items are back in the current inventory");
                }
                BlockPos deathPos = parseDeathAt(task.data().get("death_at"));
                if (deathPos == null || !worldId().equals(task.data().get("dimension"))) {
                    return new DeathRecoveryFacts(DeathDropState.UNKNOWN, false,
                            !RddRepairDispatch.backupFromBase(companionId).isEmpty(), true,
                            "death position or dimension is unknown");
                }
                if (!ap.serverLevel().hasChunkAt(deathPos)) {
                    return new DeathRecoveryFacts(DeathDropState.CHUNK_UNLOADED, false,
                            !RddRepairDispatch.backupFromBase(companionId).isEmpty(), true,
                            "death-drop chunk is not loaded; no despawn conclusion");
                }
                AABB area = new AABB(deathPos).inflate(12.0D);
                boolean trackedDrop = false;
                for (ItemEntity entity : ap.serverLevel().getEntitiesOfClass(ItemEntity.class, area)) {
                    ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entity.getItem().getItem());
                    if (id != null && death.lostItems().containsKey(id.toString())) {
                        trackedDrop = true;
                        break;
                    }
                }
                boolean danger = !ap.serverLevel().getEntitiesOfClass(
                        Monster.class, area.inflate(4.0D), Mob::isAlive).isEmpty();
                return new DeathRecoveryFacts(trackedDrop ? DeathDropState.DROPS_PRESENT : DeathDropState.DROPS_ABSENT,
                        danger, !RddRepairDispatch.backupFromBase(companionId).isEmpty(), true,
                        trackedDrop ? "tracked drop entities are present in the loaded death area"
                                : "loaded death area contains no tracked drop entity");
            } catch (RuntimeException ex) {
                return DeathRecoveryFacts.unknown("death recovery observation failed: " + ex.getClass().getSimpleName());
            }
        }

        private RddDeathEquipment.Sample equipment;
        private boolean equipmentRead;
        private RddDeathEquipment.Sample equipmentSample() {
            if (equipmentRead) return equipment;
            equipmentRead = true;
            var service = com.dwinovo.numen.api.NumenPlugins.capability(
                    com.dwinovo.numen.api.observation.WorldObservationService.class);
            return equipment = RddDeathEquipment.read(service, ap.getUUID(), worldId(),
                    com.dwinovo.numen.api.WorldContextStamp.snapshot(), System.currentTimeMillis());
        }
        @Override public PreparationFacts deathPreparationFacts(UUID companionId, SideTask task) {
            if (!ap.getUUID().equals(companionId)) return PreparationFacts.unknown("subject_mismatch");
            var latest = com.dwinovo.numen.rdd.core.RddDeathLedger.latest(companionId);
            if (latest != null && !String.valueOf(latest.seq()).equals(task.data().get("death_seq")))
                return PreparationFacts.unknown("stale_death_batch");
            return RddDeathEquipment.facts(equipmentSample(), task, RddRepairDispatch.backupFromBase(companionId),
                    HostWorldPort::equipmentTag);
        }
        @Override public Boolean deathPreparationCondition(UUID companionId, Map<String, Object> condition) {
            if (!ap.getUUID().equals(companionId)) return null;
            return RddDeathEquipment.condition(equipmentSample(), condition, HostWorldPort::equipmentTag);
        }
        private static boolean equipmentTag(String tag, String item) {
            var tagId = ResourceLocation.tryParse(tag.substring(1));
            var itemId = ResourceLocation.tryParse(item);
            if (tagId == null || itemId == null) return false;
            var resolved = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemId);
            return new net.minecraft.world.item.ItemStack(resolved).is(net.minecraft.tags.TagKey.create(
                    net.minecraft.core.registries.Registries.ITEM, tagId));
        }

        private static BlockPos parseDeathAt(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String[] parts = raw.replace(" ", "").split(",");
            if (parts.length != 3) return null;
            try {
                return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }

    /** 主线目标文本（目标 + 当前一级 + 当前二级描述拼接）：仅用于"主线是否明确要求夜间活动"。 */
    private static String mainlineObjectiveText(UUID companionId) {
        try {
            RddRuntime rt = RddPlugin.runtime(companionId);
            if (rt == null || rt.chain() == null || rt.chain().goal() == null) {
                return null;
            }
            TaskChain chain = rt.chain();
            StringBuilder sb = new StringBuilder(chain.goal().description());
            if (chain.currentPrimary() != null) {
                sb.append(' ').append(chain.currentPrimary().description());
            }
            Subtask sub = chain.currentSubtask();
            if (sub != null) {
                sb.append(' ').append(sub.description());
            }
            return sb.toString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ---- 主线恢复令牌 ----

    private static MainlineResumeToken currentMainlineToken(UUID companionId) {
        try {
            RddRuntime rt = RddPlugin.runtime(companionId);
            if (rt == null) {
                return null;
            }
            TaskChain chain = rt.chain();
            if (chain == null || chain.goal() == null) {
                return null;
            }
            Subtask current = chain.currentSubtask();
            return new MainlineResumeToken(companionId, chain.chainInstanceId(), chain.goal().id(),
                    chain.currentPrimary() == null ? null : chain.currentPrimary().id(),
                    current == null ? null : current.id(),
                    chain.planRevision(), chain.planRevision());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static MainlineResumeToken validateMainline(MainlineResumeToken token) {
        if (token == null || token.companionId() == null) {
            return null;
        }
        try {
            RddRuntime rt = RddPlugin.runtime(token.companionId());
            if (rt == null) {
                return null;
            }
            TaskChain chain = rt.chain();
            if (chain == null || chain.goal() == null) {
                return null;
            }
            Subtask current = chain.currentSubtask();
            String primaryId = chain.currentPrimary() == null ? null : chain.currentPrimary().id();
            String subtaskId = current == null ? null : current.id();
            if (current != null && chain.isPaused(current.id()) && chain.pauseReason(current.id()) != null
                    && chain.pauseReason(current.id()).startsWith("recovery_not_verified:")) return null;
            if (!token.matches(chain.chainInstanceId(), chain.goal().id(), primaryId, subtaskId,
                    chain.planRevision())) {
                return null;
            }
            return token;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ---- 上下文缓存 ----

    private static void refreshContext(UUID companionId, SideTaskWorldPort port) {
        Optional<SideTask> current = ENGINE.current(companionId);
        if (current.isEmpty() || current.get().terminal()) {
            SIDE_CONTEXT.remove(companionId);
            return;
        }
        SideTask task = current.get();
        SideTaskType type = ENGINE.typeOf(task.typeId());
        if (type == null) {
            SIDE_CONTEXT.remove(companionId);
            return;
        }
        String block = "<rdd><enabled>true</enabled><active>true</active>"
                + "<primary_status>SIDE_TASK</primary_status>"
                + "<scope>SIDE</scope>"
                + "<instruction>下面是一条**建议性**支线（不是命令）：主线已临时冻结，"
                + "完成、超时、或你拒绝后都会自动恢复主线。"
                + "若现场判断这趟不值得/做不到（太危险、没工具、目标已不可能），"
                + "用 rdd_decline_side 说明理由即可放弃 —— 拒绝会记成 DECLINED（不是失败）。</instruction>"
                + type.renderContext(task, port)
                + "</rdd>";
        Map<String, String> stamp = com.dwinovo.numen.api.WorldContextStamp.snapshot();
        SIDE_CONTEXT.put(companionId, block,
                stamp == null ? "" : stamp.getOrDefault("world_id", ""),
                stamp == null ? "" : stamp.getOrDefault("server_session", ""),
                String.valueOf(port.worldId()), port.gameTime());
    }

    // ---- 事件 + 落盘 ----

    private static void publish(String event, Map<String, Object> data) {
        try {
            if (("side_task_completed".equals(event) || "side_task_skipped".equals(event)
                    || "side_task_cancelled".equals(event) || "side_task_declined".equals(event))
                    && DeathRecoverySideTaskTrigger.TYPE_ID.equals(String.valueOf(data.get("typeId")))) {
                UUID companionId = UUID.fromString(String.valueOf(data.get("companionId")));
                int seq = Integer.parseInt(String.valueOf(data.getOrDefault("deathSeq", "-1")));
                RddRepairDispatch.clearDeathRecoveryHandoff(companionId, seq);
                // 2026-10-31 实机（166 §55）：rdd_decline_side 关闭死亡恢复支线时，
                // data 里没有 terminal_reason（四个 evaluate 分支都会写，但拒绝这条路绕过了 evaluate）。
                // 于是结算带空串落地 → 台账停在 UNVERIFIED，且事后无法区分『放弃回收』与『没说原因』。
                // 这里补上语义等同的既有值，不改任何判定。
                boolean blankReason = String.valueOf(data.getOrDefault("terminal_reason", "")).isBlank();
                if ("side_task_declined".equals(event) && blankReason) {
                    Map<String, Object> patched = new java.util.LinkedHashMap<>(data);
                    patched.put("terminal_reason", "executor_declined_recovery");
                    RddPlugin.onDeathRecoverySettled(companionId, patched);
                } else if ("side_task_cancelled".equals(event) && blankReason) {
                    // 2026-11-01（166 §80.4）：seq36 走的是**第三条路**——引擎 cancel 掉支线，
                    // 结算仍带空理由（terminalReasonMissing=true 已证明这一点，8dd3fd05a 生效）。
                    // 与 decline 同因不同路：这里补一个语义相当的既有值，让"被引擎取消"与"没说原因"可区分。
                    Map<String, Object> patched = new java.util.LinkedHashMap<>(data);
                    patched.put("terminal_reason", "recovery_cancelled");
                    RddPlugin.onDeathRecoverySettled(companionId, patched);
                } else {
                    RddPlugin.onDeathRecoverySettled(companionId, data);
                }
            }
            RddMonitor.publish(event, data);
        } catch (Throwable ignored) {
            // 观测失败不影响支线主流程
        }
    }

    private static void persist() {
        if (sideDir == null) {
            return;
        }
        try {
            Files.createDirectories(sideDir);
            Path tmp = sideDir.resolve("side-tasks.json.tmp");
            Files.writeString(tmp, ENGINE.toJson(), StandardCharsets.UTF_8);
            Files.move(tmp, sideDir.resolve("side-tasks.json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            LOG.warn("[rdd] 保存支线状态失败: {}", ex.toString());
        }
    }

    private static void restore() {
        if (sideDir == null) {
            return;
        }
        try {
            Path file = sideDir.resolve("side-tasks.json");
            if (!Files.isRegularFile(file)) {
                return;
            }
            ENGINE.restore(Files.readString(file, StandardCharsets.UTF_8));
            LOG.info("[rdd] 恢复支线状态 {} 个", ENGINE.runtime().snapshot().size());
        } catch (Exception ex) {
            LOG.warn("[rdd] 恢复支线状态失败: {}", ex.toString());
        }
    }

    /** Idempotent critical check; UNKNOWN values never trigger. */
    private static boolean foodCritical(HostWorldPort port, UUID companionId) {
        return com.dwinovo.numen.rdd.side.FoodSupplyPolicy.needsSupply(
                port.hunger(companionId), port.health(companionId), port.edibleCount(companionId));
    }
}
