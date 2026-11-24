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
    private static final SleepSideTaskType SLEEP_TYPE = new SleepSideTaskType(SleepSideTaskType.Goal.OVERNIGHT, true);
    private static final DeathRecoverySideTaskType DEATH_RECOVERY_TYPE = new DeathRecoverySideTaskType();
    private static final SideTaskEngine ENGINE =
            new SideTaskEngine(SideTaskHost::publish, SideTaskHost::validateMainline);
    /** 支线上下文缓存：主线程 tick 写入，上下文构建只读（可能在非主线程被调）。 */
    private static final SideContextStore SIDE_CONTEXT = new SideContextStore();
    /** 支线拍醒的重复间隔（tick，约 4 分钟）：支线 active 期间至少这么久再补一次提醒。 */
    private static final long NUDGE_REPEAT_TICKS = 4L * 60L * 20L;
    private static final Map<UUID, Long> LAST_NUDGE_GAME_TIME = new ConcurrentHashMap<>();
    /** Explicit server-tick identity for callbacks published during this tick; never a global stamp. */
    private static final ThreadLocal<Map<String, String>> TICK_IDENTITY = ThreadLocal.withInitial(Map::of);
    /** 2026-11-08: after a food task settles, do not rebuild for 5 real minutes. */
    private static final Map<UUID, Long> LAST_FOOD_DONE_MS = new ConcurrentHashMap<>();
    private static final long FOOD_COOLDOWN_MS = 5L * 60L * 1000L;

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
        // Ordinary HP/hunger/food advice is B: publish the request, never stop or replace the body.
        RddMonitor.publish("survival_food_requested", java.util.Map.of(
                "companionId", id.toString(), "reason", reason == null ? "" : reason));
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

    /** 夜B的只读 AC 投影：setup 记目录；缺文件/读失败/未知一律不报可用。 */
    private static volatile Path HOST_CONFIG_DIR;
    private static volatile String ACX_HINT_CACHE = "";
    private static volatile long ACX_HINT_MTIME = -1L;
    private static volatile long ACX_HINT_SIZE = -1L;

    static void setup(Path configDir) {
        HOST_CONFIG_DIR = configDir;
        ACX_HINT_CACHE = "";
        ACX_HINT_MTIME = -1L;
        ACX_HINT_SIZE = -1L;
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

    /** Called after the original current-plan commit; candidates alone are never authoritative. */
    static void registerDailyCandidates(com.dwinovo.numen.rdd.core.TaskChain chain,
                                       java.util.List<Subtask> candidates) {
        DailyRegistration.registerChain(chain, ENGINE);
        persist();
    }

    /** Pure registration seam: loading it does not install MC event listeners. */
    static final class DailyRegistration {
        private static final Logger LOG = LoggerFactory.getLogger(DailyRegistration.class);

        /**
         * 2026-11-15: daily condition 的纯可测 seam —— 只做「同伴/线程/schema/type」四道闸，然后
         * **委托注入的既有 matcher**（生产是 {@link RddWorldFacts#matches}；测试可注入假函数）。
         * 不在这里复制 WorldFacts 的任何判定；返回 null = UNKNOWN（调用方不得当完成）。
         */
        static Boolean dailyConditionFor(UUID subject, UUID self, boolean serverThread,
                                         Map<String, Object> condition,
                                         java.util.function.Function<Map<String, Object>, Boolean> matcher) {
            if (subject == null || self == null || !subject.equals(self)) return null;   // 只接本同伴
            if (!serverThread) return null;                                              // 非 server 线程不可用
            if (condition == null) return null;
            if (!com.dwinovo.numen.rdd.core.WorldFactConditions.valid(condition)) return null;  // 原 schema 判据
            if (!"block_nearby".equals(String.valueOf(condition.get("type")))) return null;     // 只接已证类型
            try {
                return matcher.apply(condition);   // 真实判定只有一个入口：既有 matcher
            } catch (RuntimeException unavailable) {
                return null;                       // 未知 ≠ 完成
            }
        }

        static void restoreChain(TaskChain chain, SideTaskEngine engine) {
            registerChain(chain, engine, true);
        }

        static void registerChain(TaskChain chain, SideTaskEngine engine) {
            registerChain(chain, engine, false);
        }

        private static void registerChain(TaskChain chain, SideTaskEngine engine, boolean missingOnly) {
            if (chain == null || chain.goal() == null || engine == null) return;
            var candidates = chain.goal().primaryGoals().stream()
                    .flatMap(primary -> primary.subtasks().stream()).toList();
            var accepted = register(chain, candidates, engine, missingOnly);
            // An unexpanded or legacy all-DAILY current stage is not an authoritative empty set.
            // DAILY's mainline SKIPPED state is routing, not withdrawal of its recurrence.
            if (accepted == null || chain.currentPrimary() == null || chain.currentPrimary().unexpanded()
                    || chain.currentPrimary().subtasks().stream().noneMatch(s ->
                            com.dwinovo.numen.rdd.api.NodeTypePolicy.countsTowardMainline(s.nodeType()))) return;
            engine.reconcileRecurringForGoal(chain.goal().id(), accepted);
        }

        static void register(com.dwinovo.numen.rdd.core.TaskChain chain,
                             java.util.List<Subtask> candidates, SideTaskEngine engine) {
            register(chain, candidates, engine, false);
        }

        private static java.util.Set<String> register(TaskChain chain, java.util.List<Subtask> candidates,
                                     SideTaskEngine engine, boolean missingOnly) {
            try {
                if (chain == null || chain.goal() == null || candidates == null || engine == null) return null;
                boolean complete = true;
                java.util.List<com.dwinovo.numen.rdd.side.RecurringSideTaskSpec> built = new java.util.ArrayList<>();
                for (var sub : candidates) {
                    if (sub == null) return null;
                    if (sub.nodeType() != com.dwinovo.numen.rdd.api.NodeType.DAILY) continue;
                    if (sub.recurrence() == null) { complete = false; continue; }
                    var rec = sub.recurrence();
                    var data = new java.util.LinkedHashMap<String, String>();
                    data.put("description", sub.description());
                    Object key0 = sub.condition().get("asset_key");
                    LOG.info("[rdd] daily candidate {} type={} condition={} asset_key0={}", sub.id(),
                            sub.condition().get("type"), sub.condition(), key0);
                    boolean typedBlock = "block_nearby".equals(String.valueOf(sub.condition().get("type")))
                            && com.dwinovo.numen.rdd.core.WorldFactConditions.valid(sub.condition());
                    if (typedBlock) {
                        // Keep the complete typed condition (type/block and optional radius/dimension/
                        // anchor/properties) instead of flattening block into an inventory asset_key.
                        data.put("condition", new com.google.gson.Gson().toJson(sub.condition()));
                        data.put("requires_farm_built", String.valueOf(rec.requiresFarmBuilt()));
                        LOG.info("[rdd] daily spec {} typed=block_nearby; condition JSON kept", sub.id());
                    } else {
                        Object key = key0;
                        if (!(key instanceof String) || ((String) key).isBlank()) key = sub.condition().get("block");
                        if (!(key instanceof String) || ((String) key).isBlank()) key = sub.condition().get("item");
                        if (!(key instanceof String) || ((String) key).isBlank()) {
                            LOG.info("[rdd] daily candidate {} skipped: no countable asset_key/block/item", sub.id());
                            complete = false;
                            continue; // incomplete source cannot withdraw committed registrations
                        }
                        data.put("asset_key", (String) key);
                        Object min = sub.condition().get("minimum");
                        data.put("minimum", String.valueOf(min instanceof Number n ? Math.max(1, n.intValue()) : 1));
                        data.put("requires_farm_built", String.valueOf(rec.requiresFarmBuilt()));
                        LOG.info("[rdd] daily spec {} asset={} min={}", sub.id(), key, data.get("minimum"));
                    }
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
                java.util.Set<String> acceptedIds = new java.util.LinkedHashSet<>();
                for (var spec : built) acceptedIds.add(spec.scheduleId());
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
                return complete ? acceptedIds : null;
            } catch (RuntimeException ignored) {
                // Unknown/failed input never retires a committed registration.
                return null;
            }
        }
    }

    static boolean isActive(UUID companionId) {
        return companionId != null && ENGINE.isActive(companionId);
    }

    /**
     * 是否真的占住 MAIN。普通 B 建议的 advisoryOnly 不算；未知/未来硬类型默认算，
     * 避免新增类型被静默当成可忽略建议。
     */
    static boolean blocksMainline(UUID companionId) {
        if (companionId == null) return false;
        return ENGINE.current(companionId).filter(task -> !task.terminal()).map(task -> {
            com.dwinovo.numen.rdd.side.SideTaskType type = ENGINE.typeOf(task.typeId());
            return type == null || !type.advisoryOnly(task);
        }).orElse(false);
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

    /**
     * 只读 B 型建议：inner XML only，不含 {@code <rdd>}，不写 SIDE_CONTEXT，不占身体。
     * 调用方只在自己正常 MAIN 的 {@code <rdd>} 内 append；同一轮可多条。
     */
    static String advisoryContextFor(UUID companionId, String currentWorldId, String currentSession) {
        if (companionId == null) return "";
        // Server tick wrote this ticket; the request identity must match it, never a global stamp.
        SideContextStore.Advisory cached = SIDE_CONTEXT.advisoryFor(companionId, currentWorldId, currentSession);
        if (cached == null) return "";
        String world = cached.worldId();
        String session = cached.serverSession();
        long dayTime = cached.dayTime();
        int hunger = cached.hunger();
        int health = cached.health();
        int edible = cached.edible();
        StringBuilder out = new StringBuilder();

        // 装备/穿戴：没有新鲜 SELF 样本时明确 UNKNOWN，不把“背包有”冒充“身穿好”。
        var service = com.dwinovo.numen.api.NumenPlugins.capability(
                com.dwinovo.numen.api.observation.WorldObservationService.class);
        Map<String, String> stamp = Map.of("world_id", world, "server_session", session);
        var sample = RddDeathEquipment.cached(service, companionId, cached.dimension(), stamp, System.currentTimeMillis());
        String gearFact = sample == null ? "UNKNOWN: current worn equipment observation unavailable"
                : "armor=" + sample.armor() + " mainhand=" + sample.mainhand() + " offhand=" + sample.offhand();
        out.append(advisory("equipment", world, session, cached.dimension(), cached.sampledGameTime(), gearFact,
                "更好/更合适的装备能降低死亡和返程风险；背包里有不等于已穿好。",
                "换装会花时间，并可能打断当前动作。",
                "继续当前装备 / 现在换 / 先核对耐久与六槽", "装备、耐久或背包变化后重评"));

        // HP/饱食：普通 B 建议，不触发 forced food，也不 stop 当前动作。
        if (health >= 0 && health < 20 || hunger >= 0 && hunger < 20) {
            out.append(advisory("hp_eat", world, session, cached.dimension(), cached.sampledGameTime(),
                    "hp=" + health + "/20 hunger=" + hunger + "/20 edible=" + edible,
                    "血/饱食未满会影响持续作业和逃生余量；进食/回血有前提，不是提醒即回满。",
                    "停下来吃或用掉一份食物会占时间；不做则继续带着余量风险。",
                    "继续 / 现在吃 / 先补食物 / 暂缓并观察", "hp、hunger、可食物变化后重评"));
        }

        // 备用食物：报真实持有数；是否够取决于行程与储备需求（未声明=UNKNOWN），不把 2 件当通用门槛。
        String edibleFact = edible < 0 ? "edible=UNKNOWN" : "edible=" + edible;
        String hungerFact = hunger < 0 ? "hunger=UNKNOWN" : "hunger=" + hunger + "/20";
        String healthFact = health < 0 ? "health=UNKNOWN" : "health=" + health + "/20";
        out.append(advisory("food_reserve", world, session, cached.dimension(), cached.sampledGameTime(),
                edibleFact + " " + hungerFact + " " + healthFact
                        + "; enough_for_trip=UNKNOWN (no declared route/reserve requirement)",
                "出远门、夜间或低血路线需要预留食物；当前是否够用取决于行程长度、退路和能否取到补给。",
                "补粮会占行程；不补则遇到饥饿/受伤时选择更少。",
                "继续 / 就近补粮 / 换路线 / 推迟外出", "可食物、行程或血量变化后重评"));

        // 夜前约 60s：复用既有 SleepSideTaskTrigger 窗口，不另造计时器；无昼夜维度不误报。
        if (com.dwinovo.numen.rdd.side.sleep.SleepSideTaskTrigger.enabled()
                && cached.hasDayNightCycle()
                && dayTime >= com.dwinovo.numen.rdd.side.sleep.SleepSideTaskTrigger.TRIGGER_DAY_TIME
                && dayTime < com.dwinovo.numen.rdd.side.sleep.SleepSideTaskTrigger.WINDOW_END_DAY_TIME) {
            out.append(advisory("sleep", world, session, cached.dimension(), cached.sampledGameTime(),
                    "dayTime=" + dayTime + " body_busy=" + cached.bodyBusy()
                            + " sleeping=" + cached.sleeping()
                            + " spawn_bound=" + cached.spawnBound()
                            + " sleep_risk=UNKNOWN(not sampled in this server-tick snapshot)",
                    "夜前约 60 秒是准备窗口；夜里在野外/低光/无床会放大敌对与死亡风险。",
                    "找床/回床/挖三填一会暂停当前任务；不做则夜里继续暴露。",
                    "继续主线 / 就近找床 / 回已有床 / 有条件安全挖三填一 / 拒绝" + acxShelterHint(),
                    "dayTime、床、风险或当前任务变化后重评"));
        }
        return out.toString();
    }

    /**
     * 夜B只读脚本投影（2026-11-24，按019「任务宿主用只读能力取活动版本」）：
     * 只读 {@code config/numen/acx/library.json} 的 defs shape（同 LearnerSideTaskLoader 文件投影方式），
     * 只列 active+STABLE 且 tags 含 shelter/emergency 的脚本；撤回/离线没有 active 指针，自然不出现。
     * 不 import acx、不硬编码名字、不自动执行；缺文件/坏文件/未知返回空串。
     * 有界：mtime+size 不变复用上次结果；最多 2 条、总长 ≤ 240；只在渲染夜间B建议时调用。
     */
    private static String acxShelterHint() {
        Path dir = HOST_CONFIG_DIR;
        if (dir == null) return "";
        Path file = dir.resolve("acx").resolve("library.json");
        try {
            if (!Files.isRegularFile(file)) return "";
            long mtime = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            if (mtime == ACX_HINT_MTIME && size == ACX_HINT_SIZE) return ACX_HINT_CACHE;
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            StringBuilder out = new StringBuilder();
            var defs = com.google.gson.JsonParser.parseString(raw).getAsJsonObject().getAsJsonObject("defs");
            int shown = 0;
            if (defs != null) {
                for (var e : defs.entrySet()) {
                    if (shown >= 2 || out.length() > 180) break;
                    var def = e.getValue().getAsJsonObject();
                    if (def == null || !def.has("active_version") || !def.get("active_version").isJsonPrimitive()) continue;
                    String active = def.get("active_version").getAsString();
                    var versions = def.getAsJsonObject("versions");
                    var ver = versions == null ? null : versions.getAsJsonObject(active);
                    if (ver == null) continue;
                    String status = ver.has("status") && ver.get("status").isJsonPrimitive()
                            ? ver.get("status").getAsString() : "STABLE";   // 旧库缺省=STABLE（FileAcxLibrary 兼容口径）
                    if (!"STABLE".equalsIgnoreCase(status)) continue;
                    var d = ver.getAsJsonObject("definition");
                    if (d == null) continue;
                    boolean shelter = false;
                    if (d.has("tags") && d.get("tags").isJsonArray()) {
                        for (var tag : d.getAsJsonArray("tags")) {
                            if (!tag.isJsonPrimitive()) continue;
                            String s = tag.getAsString().toLowerCase(java.util.Locale.ROOT);
                            if (s.equals("shelter") || s.equals("emergency")) { shelter = true; break; }
                        }
                    }
                    if (!shelter) continue;
                    String name = d.has("name") && d.get("name").isJsonPrimitive() ? d.get("name").getAsString() : e.getKey();
                    String desc = d.has("description") && d.get("description").isJsonPrimitive() ? d.get("description").getAsString() : "";
                    int cut = desc.indexOf('。');
                    if (cut > 0) desc = desc.substring(0, cut);
                    if (desc.length() > 40) desc = desc.substring(0, 40);
                    if (out.length() > 0) out.append("；");
                    out.append(name).append(" v").append(active);
                    if (!desc.isBlank()) out.append(" — ").append(desc.trim());
                    out.append("：acx_execute ac_name=").append(name);
                    shown++;
                }
            }
            String built = shown == 0 ? "" : "；可用脚本（活动版本，建议非命令）：" + out;
            ACX_HINT_MTIME = mtime;
            ACX_HINT_SIZE = size;
            ACX_HINT_CACHE = built;
            return built;
        } catch (Exception unknown) {
            return "";   // 读不到 ≠ 没有：不报可用，也不缓存成“无”
        }
    }

    private static String advisory(String type, String world, String session, String dimension,
                                   long sampledGameTime, String fact, String reason,
                                   String cost, String alternatives, String recheck) {
        return "<advisory type=\"" + contextXml(type) + "\" scope=\"B\" world_id=\"" + contextXml(world)
                + "\" server_session=\"" + contextXml(session) + "\" dimension=\"" + contextXml(dimension)
                + "\" sampled_game_time=\"" + sampledGameTime + "\"><fact>" + contextXml(fact)
                + "</fact><reason>" + contextXml(reason) + "</reason><cost>" + contextXml(cost)
                + "</cost><alternatives>" + contextXml(alternatives) + "</alternatives><recheck>"
                + contextXml(recheck) + "</recheck><instruction>建议，不是命令；可自行选择/暂缓/拒绝。无需调用 rdd_decline_side；该工具只适用当前 ACTIVE 的硬支线。</instruction></advisory>";
    }

    static SideTask activeSleepOwner(UUID companionId) {
        return ENGINE.current(companionId).filter(com.dwinovo.numen.rdd.side.sleep.SleepSideTaskType::isOvernight)
                .filter(task -> !task.terminal()).orElse(null);
    }
    static com.dwinovo.numen.rdd.side.SleepCycleEvidence readSleepCycle(NumenPlayer ap, SideTask expected) {
        if (ap == null || expected == null || !ap.getUUID().equals(expected.companionId())
                || RddCompanionBody.get(ap.getUUID()) != ap)
            return com.dwinovo.numen.rdd.side.SleepCycleEvidence.UNKNOWN;
        return SleepWakeWatcher.poll(ap, expected);
    }

    /** Current live side owner, not the independently cached model context. */
    static SideTask activeYieldOwner(UUID companionId) {
        return companionId == null ? null : ENGINE.current(companionId)
                .filter(task -> !task.terminal())
                .filter(task -> {
                    com.dwinovo.numen.rdd.side.SideTaskType type = ENGINE.typeOf(task.typeId());
                    return type == null || !type.advisoryOnly(task);
                }).orElse(null);
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
        return chooseDeathRecovery(companionId, decision, reason, null, "", -1, "");
    }

    static boolean chooseDeathRecovery(UUID companionId, String decision, String reason, Map<String, String> review,
                                       String instance, long generation, String deathSeq) {
        if (companionId == null || (!isDeathRecoveryActive(companionId) && review == null)) return false;
        com.dwinovo.numen.rdd.side.death.DeathRecoveryDecision parsed =
                com.dwinovo.numen.rdd.side.death.DeathRecoveryDecision.parse(decision);
        if (parsed == null) return false;
        if (review != null) {
            var rt = RddPlugin.runtime(companionId);
            var task = ENGINE.current(companionId).orElseGet(() -> rt == null ? null
                    : com.dwinovo.numen.rdd.side.death.DeathPreparation.pausedTask(rt.chain()));
            int seq;
            try { seq = Integer.parseInt(deathSeq); } catch (RuntimeException invalid) { return false; }
            if (!DeathPreparationHost.settlementIdentity(companionId, seq, task, rt)
                    || !com.dwinovo.numen.rdd.side.death.DeathPreparation.review(ENGINE, companionId, rt.chain(),
                            instance, generation, deathSeq, review)) return false;
        }
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
        String declinedType = currentTypeId(companionId);
        boolean declined = ENGINE.decline(companionId, reason, worldId, gameDay);
        if (declined && "food_supply".equals(declinedType)) {
            LAST_FOOD_DONE_MS.put(companionId, System.currentTimeMillis());
        }
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
            if (!wasActive) {
                port.pollWake(companionId); // 无支线时排空陈旧醒来边沿，避免泄漏到下一晚
            }
            MainlineResumeToken token = currentMainlineToken(companionId);
            boolean deathOccupying = RddRepairDispatch.lastRepair(companionId) != null;
            // Preserve death preparation before the single arbitration tick; sleep only adds its handoff.
            Map<String, String> priorIdentity = TICK_IDENTITY.get();
            TICK_IDENTITY.set(serverIdentity(server));
            try {
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
            } finally {
                TICK_IDENTITY.set(priorIdentity);
            }
            boolean nowActive = ENGINE.isActive(companionId);
            if (wasActive && "food_supply".equals(wasType) && !nowActive) {
                LAST_FOOD_DONE_MS.put(companionId, System.currentTimeMillis());
            }
            refreshContext(ap.level().getServer() == server ? server : null, companionId, port);
            refreshAdvisorySnapshot(server, companionId, port);
            nudgeIfNeeded(companionId, port, wasActive, blocksMainline(companionId));
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
    private static void nudgeIfNeeded(UUID companionId, SideTaskWorldPort port, boolean wasActive, boolean nowOwner) {
        if (!nowOwner) {
            // Ordinary B has no active owner: leave its shared repeat clock intact.  Only a hard
            // owner that just ended releases its own clock at this original transition point.
            if (wasActive) LAST_NUDGE_GAME_TIME.remove(companionId);
            return;
        }
        NudgeDelivery.sendIfNeeded(companionId, LAST_NUDGE_GAME_TIME, wasActive, nowOwner,
                nowOwner ? port.gameTime() : 0L, () -> {
                    String type = currentTypeId(companionId);
                    // A new sleep owner must not inherit a mainline reminder cooldown.
                    // Like kickoff, reset only this companion on admission; nudge still checks pause/API.
                    if (!wasActive && SleepSideTaskType.TYPE_ID.equals(type)) {
                        RddPlugin.clearNudgeThrottle(companionId);
                    }
                    if (!RddPlugin.nudgeAllowed(companionId)) return false;
                    return RddPlugin.nudge(companionId, "[支线·" + (type == null ? "unknown" : type) + "·建议] "
                            + "当前支线仍在执行，主线暂时冻结。请按 <side_task> 中的事实和有限决策处理；"
                            + "若现场判断不值得或无法完成，用 rdd_decline_side 写明观察到的原因，"
                            + "不要把未知事实当成已完成或永久丢失。");
                });
    }

    /**
     * 2026-11-14: mechanically extracted delivery seam for the V16 rejected-reminder regression.
     * Uses the existing host map and repeat interval; tests supply only the Boolean send receipt.
     * Record the existing repeat clock only after the send port accepts the reminder.
     * No alternate RddPlugin throttle, Minecraft body, clock or state registry is introduced.
     */
    /**
     * Route one published B occurrence through the existing nudge clock.  Identity and gameTime
     * come from the event envelope; stale/missing identity is rejected, not borrowed from a global.
     * Returns true only when the send port accepted the reminder (then and only then is time recorded).
     */
    static boolean nudgeAdvisoryEvent(String event, Map<String, Object> data,
                                      Map<UUID, Long> lastNudgeGameTime,
                                      String currentWorldId, String currentSession,
                                      java.util.function.BooleanSupplier send) {
        if (!"side_task_advisory".equals(event) || data == null || lastNudgeGameTime == null || send == null) return false;
        if (currentWorldId == null || currentWorldId.isBlank()
                || currentSession == null || currentSession.isBlank()) return false;
        if (!currentWorldId.equals(String.valueOf(data.get("world_id")))
                || !currentSession.equals(String.valueOf(data.get("server_session")))) return false;
        UUID id;
        try { id = UUID.fromString(String.valueOf(data.get("companionId"))); }
        catch (RuntimeException invalid) { return false; }
        Object raw = data.get("gameTime");
        if (!(raw instanceof Number now)) return false;
        // Reuse the shared LAST_NUDGE table and its NUDGE_REPEAT_TICKS gate; no second clock.
        boolean seen = lastNudgeGameTime.containsKey(id);
        return NudgeDelivery.sendIfNeeded(id, lastNudgeGameTime, seen, true, now.longValue(), send);
    }

    static final class NudgeDelivery {
        private NudgeDelivery() {}

        static boolean sendIfNeeded(UUID companionId, Map<UUID, Long> lastNudgeGameTime,
                                    boolean wasActive, boolean nowActive, long now,
                                    java.util.function.BooleanSupplier send) {
            if (!nowActive) {
                lastNudgeGameTime.remove(companionId);
                return false;
            }
            Long last = lastNudgeGameTime.get(companionId);
            if (wasActive && last != null && now - last < NUDGE_REPEAT_TICKS) {
                return false;
            }
            if (!send.getAsBoolean()) return false;
            lastNudgeGameTime.put(companionId, now);
            return true;
        }
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
        DeathPreparationHost.tick(ap.getUUID(), ENGINE, port, new DeathPreparationHost.Access() {
            @Override public RddRuntime runtime(UUID id) { return RddPlugin.runtime(id); }
            @Override public Map<String, com.dwinovo.numen.rdd.policy.AssetSemantics> semantics(UUID id) {
                return RddSemanticsStore.semantics(id);
            }
            @Override public void declareWearSlot(String key, Map<String, String> slots) {
                SideTaskHost.declareWearSlot(key, slots);
            }
            @Override public com.dwinovo.numen.rdd.side.death.DeathPreparation.Planner capturePlanner(UUID id) {
                var server = ap.getServer();
                var ticket = RddPlugin.planningTicket(id);
                if (server == null) return null;
                return (objective, hint, done) -> RddDecomposer.decomposeSpecsWithHint(
                        id, objective, 0, java.util.List.of(), hint,
                        specs -> RddPlugin.onServer(server, ticket, () -> done.accept(specs)));
            }
            @Override public void stopBody() {
                com.dwinovo.numen.task.CompanionTickDispatcher.stopActive(ap, "death preparation preempts original action");
            }
            @Override public void persist() { SideTaskHost.persist(); }
        });
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
        return DeathPreparationHost.holdRecoveryPause(ap.getUUID(), ENGINE, chain, port, () -> {
            tickDeathPreparation(ap, port);
            RddPlugin.nudge(ap.getUUID(), "[恢复复评] 原步骤仍暂停；请按恢复条件补齐当前装备，完成后再继续原主线。");
            persist();
        });
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
            engine.tick(id, port, token, death, RddReplanTransition.deferPlannerDaily(id, token));
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
        @Override public com.dwinovo.numen.rdd.side.SleepCycleEvidence pollSleepCycle(SideTask expected) {
            return readSleepCycle(ap, expected);
        }
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

        /**
         * 2026-11-15: daily condition 的宿主接线。
         * 只接本同伴 + server 线程 + 原 {@code WorldFactConditions} 合法 + type=block_nearby；
         * 其余一律 UNKNOWN（null）。true/false 原样交给既有 {@link RddWorldFacts#matches}，
         * 不复制任何世界事实判定。
         */
        @Override public Boolean dailyCondition(UUID companionId, Map<String, Object> condition) {
            if (ap == null) return null;
            boolean serverThread = ap.getServer() != null && ap.getServer().isSameThread();
            return DailyRegistration.dailyConditionFor(companionId, ap.getUUID(), serverThread, condition,
                    c -> RddWorldFacts.matches(ap, c));
        }

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
            return DeathPreparationHost.facts(ap.getUUID(), companionId, task,
                    () -> RddDeathEquipment.facts(equipmentSample(), task, RddRepairDispatch.backupFromBase(companionId),
                            RddWorldFacts::equipmentTag));
        }
        @Override public Boolean deathPreparationCondition(UUID companionId, Map<String, Object> condition) {
            if (!ap.getUUID().equals(companionId)) return null;
            return RddDeathEquipment.condition(equipmentSample(), condition, RddWorldFacts::equipmentTag);
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
        refreshContext(companionId, port, com.dwinovo.numen.api.WorldContextStamp.snapshot());
    }

    /** Explicit server-tick identity; failure is an empty map, never the global stamp. */
    private static Map<String, String> serverIdentity(MinecraftServer server) {
        if (server == null || !server.isSameThread()) return Map.of();
        com.dwinovo.numen.api.ServerIdentityProvider provider =
                com.dwinovo.numen.api.NumenPlugins.capability(com.dwinovo.numen.api.ServerIdentityProvider.class);
        var identity = provider == null ? null : provider.snapshot(server);
        if (identity == null || identity.worldId() == null || identity.worldId().isBlank()
                || identity.serverSession() == null || identity.serverSession().isBlank()) return Map.of();
        return Map.of("world_id", identity.worldId(), "server_session", identity.serverSession());
    }

    /** Separate server-tick B sampling; registration refresh stays world-read-free. */
    private static void refreshAdvisorySnapshot(MinecraftServer server, UUID companionId, SideTaskWorldPort port) {
        if (companionId == null || server == null || !server.isSameThread()) {
            SIDE_CONTEXT.removeAdvisory(companionId);
            return;
        }
        Map<String, String> identity = serverIdentity(server);
        if (identity.isEmpty()) {
            SIDE_CONTEXT.removeAdvisory(companionId);
            return;
        }
        SIDE_CONTEXT.putAdvisory(companionId, new SideContextStore.Advisory(
                identity.get("world_id"), identity.get("server_session"), port.worldId(),
                Math.floorMod(port.dayTime(), 24_000L), port.hasDayNightCycle(),
                port.hunger(companionId), port.health(companionId), port.edibleCount(companionId),
                port.isSleeping(companionId), port.isSpawnBound(companionId), port.bodyBusy(companionId),
                port.gameTime()));
    }

    /** Server-tick capture only. Request construction never enumerates the engine or reads the world. */
    private static void refreshContext(MinecraftServer server, UUID companionId, SideTaskWorldPort port) {
        // A failed refresh must not keep yesterday's registration/advisory facts current.
        SIDE_CONTEXT.removeRegistrations(companionId);
        SIDE_CONTEXT.removeAdvisory(companionId);
        if (server == null || !server.isSameThread()) return;
        com.dwinovo.numen.api.ServerIdentityProvider provider =
                com.dwinovo.numen.api.NumenPlugins.capability(com.dwinovo.numen.api.ServerIdentityProvider.class);
        var identity = provider == null ? null : provider.snapshot(server);
        boolean knownIdentity = identity != null && identity.worldId() != null && !identity.worldId().isBlank()
                && identity.serverSession() != null && !identity.serverSession().isBlank();
        Map<String, String> stamp = !knownIdentity ? Map.of()
                : Map.of("world_id", identity.worldId(), "server_session", identity.serverSession());
        refreshContext(companionId, port, stamp);
        MainlineResumeToken owner = validateMainline(currentMainlineToken(companionId));
        if (identity == null || identity.worldId() == null || identity.worldId().isBlank()
                || identity.serverSession() == null || identity.serverSession().isBlank()
                || owner == null || !companionId.equals(owner.companionId())
                || owner.goalId() == null || owner.goalId().isBlank()) {
            SIDE_CONTEXT.removeRegistrations(companionId);
            return;
        }
        String prefix = "daily:" + owner.goalId() + ":";
        var registered = ENGINE.recurringSpecsSnapshot().stream()
                .filter(spec -> spec.scheduleId().startsWith(prefix)
                        && spec.scheduleId().lastIndexOf(':') == prefix.length() - 1
                        && spec.scheduleId().length() > prefix.length())
                .toList();
        SIDE_CONTEXT.putRegistrations(companionId, new SideContextStore.Ticket(identity.worldId(),
                identity.serverSession(), port.worldId(), owner, port.gameDay()), registered);
    }

    /** Cached registration facts, not action admission. Caller supplies its already captured identity. */
    static String registrationContextFor(UUID companionId, SideContextStore.Ticket current) {
        var specs = SIDE_CONTEXT.registrationsFor(companionId, current);
        if (specs == null) return "<daily_registrations status=\"UNKNOWN\"/>";
        StringBuilder out = new StringBuilder("<daily_registrations status=\"CURRENT\">");
        for (var spec : specs) {
            out.append("<registration><schedule_id>").append(contextXml(spec.scheduleId()))
                    .append("</schedule_id><template_id>").append(contextXml(spec.templateId()))
                    .append("</template_id><sampled_game_day>").append(current.gameDay())
                    .append("</sampled_game_day><calendar_due>").append(spec.dueOn(current.gameDay()))
                    .append("</calendar_due><enabled>").append(spec.enabled())
                    .append("</enabled><prompt_only>").append(spec.promptOnly())
                    .append("</prompt_only><data>").append(contextXml(String.valueOf(spec.data())))
                    .append("</data><admission>UNKNOWN</admission></registration>");
        }
        return out.append("</daily_registrations>").toString();
    }

    private static String contextXml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static void refreshContext(UUID companionId, SideTaskWorldPort port, Map<String, String> stamp) {
        Optional<SideTask> current = ENGINE.current(companionId);
        if (current.isEmpty() || current.get().terminal()) {
            SIDE_CONTEXT.remove(companionId);
            return;
        }
        SideTask task = current.get();
        SideTaskType type = ENGINE.typeOf(task.typeId());
        if (type == null || type.advisoryOnly(task)) {
            // 普通 B 只走 advisoryContextFor；旧 ACTIVE 实例保留 history，但不再占 SIDE 硬 owner 槽。
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
        SIDE_CONTEXT.put(companionId, block,
                stamp == null ? "" : stamp.getOrDefault("world_id", ""),
                stamp == null ? "" : stamp.getOrDefault("server_session", ""),
                String.valueOf(port.worldId()), port.gameTime());
    }

    // ---- 事件 + 落盘 ----

    static void clearTerminalSleepCycle(String event, Map<String, Object> data) {
        if ("sleep".equals(data.get("typeId")) && ("side_task_completed".equals(event)
                || "side_task_skipped".equals(event) || "side_task_cancelled".equals(event)
                || "side_task_declined".equals(event))) {
            UUID id = UUID.fromString(String.valueOf(data.get("companionId")));
            SleepWakeWatcher.clear(id, String.valueOf(data.get("instanceId")),
                ((Number)data.get("generation")).longValue(), RddCompanionBody.get(id));
        }
    }

    private static void publish(String event, Map<String, Object> data) {
        try {
            clearTerminalSleepCycle(event, data);
            if ("side_task_advisory".equals(event)) {
                // One B occurrence may wake an idle model, but only through the existing nudge
                // throttle and never by stopping/clearing the current body action.
                Map<String, Object> enriched = new java.util.LinkedHashMap<>(data);
                Map<String, String> identity = TICK_IDENTITY.get();
                String world = identity.getOrDefault("world_id", "");
                String session = identity.getOrDefault("server_session", "");
                if (!world.isBlank() && !session.isBlank()) {
                enriched.put("world_id", world);
                enriched.put("server_session", session);
                nudgeAdvisoryEvent(event, enriched, LAST_NUDGE_GAME_TIME, world, session, () -> {
                    UUID id;
                    try { id = UUID.fromString(String.valueOf(enriched.get("companionId"))); }
                    catch (RuntimeException invalid) { return false; }
                    // A B reminder must never replace or interrupt a real body action; nudgeAllowed
                    // only covers supervision/throttle, so check the live action here as well.
                    if (blocksMainline(id)
                            || com.dwinovo.numen.task.CompanionTickDispatcher.isExecutingAction(id)) return false;
                    String type = String.valueOf(enriched.getOrDefault("typeId", "side"));
                    return RddPlugin.nudgeAllowed(id) && RddPlugin.nudge(id,
                            "[支线·建议] " + type + " 有新提醒；主线仍可继续。请按当前事实自行选择、暂缓或拒绝，"
                                    + "无需调用 rdd_decline_side；该工具只适用当前 ACTIVE 的硬支线。");
                });
                }
                data = enriched;
            }
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
                    data.put("prepareSettlementAccepted", RddPlugin.onDeathRecoverySettled(companionId, patched));
                } else if ("side_task_cancelled".equals(event) && blankReason) {
                    // 2026-11-01（166 §80.4）：seq36 走的是**第三条路**——引擎 cancel 掉支线，
                    // 结算仍带空理由（terminalReasonMissing=true 已证明这一点，8dd3fd05a 生效）。
                    // 与 decline 同因不同路：这里补一个语义相当的既有值，让"被引擎取消"与"没说原因"可区分。
                    Map<String, Object> patched = new java.util.LinkedHashMap<>(data);
                    patched.put("terminal_reason", "recovery_cancelled");
                    data.put("prepareSettlementAccepted", RddPlugin.onDeathRecoverySettled(companionId, patched));
                } else {
                    data.put("prepareSettlementAccepted", RddPlugin.onDeathRecoverySettled(companionId, data));
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

}
