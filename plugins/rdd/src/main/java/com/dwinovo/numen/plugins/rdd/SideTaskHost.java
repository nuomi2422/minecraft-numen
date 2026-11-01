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
    private static final Map<UUID, String> SIDE_CONTEXT = new ConcurrentHashMap<>();
    /** 支线拍醒的重复间隔（tick，约 4 分钟）：支线 active 期间至少这么久再补一次提醒。 */
    private static final long NUDGE_REPEAT_TICKS = 4L * 60L * 20L;
    private static final Map<UUID, Long> LAST_NUDGE_GAME_TIME = new ConcurrentHashMap<>();
    private static volatile Path sideDir;

    static {
        ENGINE.registerType(SLEEP_TYPE);
        ENGINE.registerTrigger(new SleepSideTaskTrigger());
        // 2026-10-23：恢复支线（主人在意的"丢了东西就插支线，不重写主线"）。
        ENGINE.registerType(new com.dwinovo.numen.rdd.side.recovery.RecoverySideTaskType());
        ENGINE.registerTrigger(new com.dwinovo.numen.rdd.side.recovery.RecoverySideTaskTrigger());
        ENGINE.registerType(DEATH_RECOVERY_TYPE);
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
    static String contextFor(UUID companionId) {
        return companionId == null ? null : SIDE_CONTEXT.get(companionId);
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
        boolean set = ENGINE.setData(companionId,
                com.dwinovo.numen.rdd.side.death.DeathRecoverySideTaskType.KEY_DECISION,
                parsed.name());
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
            if (!wasActive) {
                port.pollWake(companionId); // 无支线时排空陈旧醒来边沿，避免泄漏到下一晚
            }
            MainlineResumeToken token = currentMainlineToken(companionId);
            boolean deathOccupying = RddRepairDispatch.lastRepair(companionId) != null;
            ENGINE.tick(companionId, port, token, deathOccupying);
            boolean nowActive = ENGINE.isActive(companionId);
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

    // ---- 世界端口 ----

    private static final class HostWorldPort implements SideTaskWorldPort {
        private final NumenPlayer ap;

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
        @Override public boolean inCombat(UUID companionId) { return ap.hurtTime > 0; }
        /**
         * 2026-10-30：出生点绑没绑 —— 睡觉支线的新完成判据就靠它
         * （原版：对床右键成功 → ServerPlayer 的 respawnPosition 非空）。
         */
        @Override public boolean isSpawnBound(UUID companionId) { return ap.getRespawnPosition() != null; }
        @Override public boolean bodyBusy(UUID companionId) {
            return com.dwinovo.numen.task.CompanionTickDispatcher.currentTaskFor(companionId) != null;
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
            return new MainlineResumeToken(companionId, chain.goal().id(),
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
            if (!token.matches(chain.goal().id(), primaryId, subtaskId, chain.planRevision())) {
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
        SIDE_CONTEXT.put(companionId, block);
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
}
