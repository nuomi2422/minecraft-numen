package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.policy.AssetSemantics;
import com.dwinovo.numen.rdd.policy.LossImpactAnalyzer;
import com.dwinovo.numen.rdd.policy.RequirementManifest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Death-loss analysis and recomputation only; dispatch owns repair windows and their death batch.
 * Uses the existing analyzer and cached base observations, never scans the world or dispatches a body task.
 * Restart/armed guards remain process-lived as before; remove only clears phase deduplication.
 */
final class RddRepairLossAnalysis {
    private RddRepairLossAnalysis() {}
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(RddRepairDispatch.class);

    /**
     * 重算幂等键：{@code companion|deathSeq|phase}。
     *
     * <p>回收判据每拍都会问一次，"回收完成"与"超时结账"在边界上可能同拍/相邻拍都成立；
     * 没有这个集合，同一批损失会重复发事件、重复补一句 nudge（长会话里就是噪音洪水）。
     */
    private static final java.util.Set<String> RECOMPUTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Recompute events and successful advice are separate receipts. A rejected nudge may
    // be retried only by another invocation of this same lifecycle phase and plan owner.
    private record AdviceScope(com.dwinovo.numen.rdd.core.TaskChain chain, int revision, String primary) {}
    private static final Map<String, AdviceScope> REJECTED_ADVICE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * S1 读路径（死亡瞬间）：只报"库存未知"，不下够/不够结论。
     *
     * <p><b>为什么不在这一拍读背包</b>：死亡与复活之间有一个窗口（原版掉落还在尸体上、
     * 同伴实体已 0 血），此时读到的"空"既可能是"全掉了"，也可能是"还没准备好"。
     * 把两者混成一个空表，就会在下一步推出"备用足够"——那是本批要修的核心假象
     * （81 号 §4.1：读取失败与真实空库存必须分开）。
     *
     * <p>所以这里固定传 {@code inventoryKnown=false}、{@code backup={}}：
     * 结论必然是 {@code CONFIRM_STOCK}，文案说"先确认库存"，绝不说"0 件"。
     * 真正的判定在 {@link #recomputeAfterDeath}。
     *
     * <p>fail-soft：任何一步炸了返回 null（事件不带 lossImpact 键、文案不带语义段），
     * 死亡支线本身照旧 —— 语义分析是增强，不是依赖（分析器「只产事实与建议」的红线）。
     */
    /** 食物损失在“人正饿/没食物”时不能被 NONE 放行；至少先确认库存。 */
    private static LossImpactAnalyzer.LossImpact escalateFoodLossIfHungry(
            UUID companionId, Map<String, Integer> lost, LossImpactAnalyzer.LossImpact impact) {
        if (impact == null) return null;
        boolean food = lost != null && lost.keySet().stream()
                .anyMatch(k -> com.dwinovo.numen.rdd.core.InventoryGroups.contains("food", k));
        if (!food) return impact;
        var body = RddCompanionBody.get(companionId);
        int foodLevel = body == null ? -1 : body.getFoodData().getFoodLevel();
        return LossImpactAnalyzer.escalateFoodLossWhileHungry(impact, true, foodLevel);
    }

    static LossImpactAnalyzer.LossImpact analyzeDeathLoss(UUID companionId,
            com.dwinovo.numen.rdd.core.RddDeathLedger.Death death) {
        try {
            if (death == null || death.lostItems().isEmpty()) {
                return null;   // 没记录掉落清单（旧台账/无背包数据）→ 不硬凑分析
            }
            RequirementManifest.Manifest manifest = RddFactContext.currentManifest(companionId);
            List<RequirementManifest.Manifest> manifests =
                    manifest == null ? List.of() : List.of(manifest);
            Map<String, AssetSemantics> semantics =
                    RddSemanticsStore.forAnalyzer(RddSemanticsStore.semantics(companionId));
            return LossImpactAnalyzer.analyze(
                    new LinkedHashMap<>(death.lostItems()),
                    Map.of(),                       // 随身：未知，不是空
                    Map.of(),                       // 备用：P0 只认"真观测到"的基地容器
                    manifests,
                    semantics,
                    false,                          // 掉落还在地上：未确认丢失
                    false);                         // ★ 库存未确认（81 号 §4.1）
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * 死亡后重算：回收完成 / 超时结账那一拍，手里有**真实背包**才算得准。
     *
     * <p>三条输入都有出处：{@code counts} 是本拍真实扫描；{@code backup} 来自基地容器观测
     * （{@link #backupFromBase}，带新鲜度门槛）；{@code lossConfirmed} 由调用方按台账状态给
     * （回收成功 = false，超时结账 = true，语义是"本支线放弃追踪"而不是"物理销毁"）。
     *
     * <p>幂等：同一 {@code deathSeq + phase} 只发一次事件。重复调用只返回结论，不重复打扰。
     *
     * @return 分析结论；没有死亡记录/清单为空/异常时 null
     */
    /**
     * 与内存无关的兜底（2026-10-28 实机教训）：死亡后派的"修理工单"靠静态内存表计时
     * （REPAIR_CREATED_TICK / LAST_REPAIR），**实例一重启就清空**，于是"5 分钟超时重算"
     * 再也不会触发。实测 11:18 那次死亡工单事件在案，但 loss_impact_recomputed 恒为 0，
     * 而那段时间重启过两次。这里给一条不依赖内存的入口：对台账里最近一次死亡重算一次
     * （内部按 companion|seq|phase 去重，同一会话只算一次）。
     */
    /**
     * 已按哪一笔死亡做过"重启后重算"（companion → seq）。
     *
     * <p>2026-10-29 实机事故：{@code tickRuntime} **每 tick** 都调 {@link #recomputeLatestFromLedger}，
     * 而死亡台账里的 latest 不会自己消失 ⇒ 它每 tick 都重算一遍、并且每次先扫一遍已知容器。
     * 实机表现是**每秒 4 次 {@code [rdd-storage] 扫描}** + {@code [rdd-repair] latest=27 lostKinds=31}
     * 三行日志无限重复，把 Server thread 占满，AI **完全停摆 1 小时 40 分钟**
     * （06:21 之后没有任何新回合/工具调用）。
     *
     * <p>修法：按 (companion, seq) **幂等** —— 同一笔死亡只重算一次；新死亡（seq 变）才会再跑。
     */
    private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> LAST_RECOMPUTED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 每同伴每进程只打一次 "armed"，证明兜底真的被调用过，又不逐 tick 刷屏。 */
    private static final java.util.Set<UUID> ARMED_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void recomputeLatestFromLedger(UUID companionId, java.util.Map<String, Integer> counts) {
        try {
            if (companionId == null) return;
            // "到底有没有被调到"必须与台账是否有货无关：armed 放在台账判断之前，每同伴一次。
            if (RddRepairLogPolicy.shouldLogArmed(ARMED_LOGGED, companionId)) {
                LOG.info("[rdd-repair] recompute-from-ledger armed companion={}", companionId);
            }
            // ★ 2026-10-28（静态定案）：死亡台账是**懒加载**的，加载点只有两处 ——
            //   ① onDeath（死亡那一刻；重启后这条路不会再走）；
            //   ② deathDropsBackInInventory（要求 lastRepair != null，而 lastRepair 也是内存表，重启即空）。
            //   所以**重启之后**跑这段兜底时，内存台账是空的：latest() 为 null、静默返回
            //   （上一版日志放在这之后，于是看起来"从没被调用"）。ensureLedgerLoaded 按 UUID 幂等，补一次即可。
            RddPlugin.ensureLedgerLoaded(companionId);
            com.dwinovo.numen.rdd.core.RddDeathLedger.Death latest =
                    com.dwinovo.numen.rdd.core.RddDeathLedger.latest(companionId);
            if (latest == null || latest.lostItems().isEmpty()) return;
            var owner = RddPlugin.runtime(companionId);
            if (owner != null && owner.chain().primaryStatus() == com.dwinovo.numen.rdd.api.PrimaryGoalStatus.COMPLETED) {
                REJECTED_ADVICE.keySet().removeIf(key -> key.startsWith(companionId + "|"));
                return; // completed work cannot receive a delayed route-review request
            }
            // Existing Detector cadence supplies fresh counts; retry only a rejected confirmed
            // advisory, preserving its original phase. Ordinary same-batch analysis stays latched.
            String prefix = companionId + "|" + latest.seq() + "|";
            for (String key : List.copyOf(REJECTED_ADVICE.keySet())) {
                if (key.startsWith(prefix)) {
                    LAST_RECOMPUTED.put(companionId, (long) latest.seq());
                    if (!RddPlugin.nudgeAllowed(companionId)) return; // read-only pause/API/throttle peek, no repeat logs
                    recomputeAfterDeath(companionId, counts, true, key.substring(prefix.length()), latest.seq());
                    return;
                }
                if (key.startsWith(companionId + "|")) REJECTED_ADVICE.remove(key);
            }
            // 2026-10-29（本方法每 tick 都被调用）：同一笔死亡只重算一次，否则就是死循环。
            // 2026-11-06（热点）：日志必须放在幂等 guard **之后**，否则每 tick 两条 INFO 把
            // latest.log 撑到 4.6MB/6 分钟（12:40–12:41 实测）；"是否被调用"用每同伴一次 armed 保留。
            long lastRecomputedSeq = LAST_RECOMPUTED.getOrDefault(companionId, (long) Integer.MIN_VALUE);
            if (RddRepairLogPolicy.skipSameBatch(lastRecomputedSeq, latest.seq())) {
                return;
            }
            LAST_RECOMPUTED.put(companionId, (long) latest.seq());
            var impact = recomputeAfterDeath(companionId, counts, false, "restart", latest.seq());
            LOG.info("[rdd-repair] recompute-from-ledger done companion={} seq={} impact={}",
                    companionId, latest.seq(), impact == null ? "null" : impact.recommendation());
        } catch (RuntimeException e) {
            // fail-soft：兜底不能拖垮 tick；但把异常打出来（原来这里是静默的，导致整条链无迹可查）
            LOG.info("[rdd-repair] recompute-from-ledger FAILED companion={} : {}", companionId, e.toString());
        }
    }
    static LossImpactAnalyzer.LossImpact recomputeAfterDeath(UUID companionId,
                                                             Map<String, Integer> counts,
                                                             boolean lossConfirmed,
                                                             String phase) {
        return recomputeAfterDeath(companionId, counts, lossConfirmed, phase, -1);
    }

    /**
     * @param seqHint 调用方手上的死亡批次号（超时结账会先清 OPEN_DEATH_SEQ，必须由调用方留底）。
     *                -1 = 让它自己找（当前支线批次，找不到退最近一条）。
     */
    static LossImpactAnalyzer.LossImpact recomputeAfterDeath(UUID companionId,
                                                             Map<String, Integer> counts,
                                                             boolean lossConfirmed,
                                                             String phase,
                                                             int seqHint) {
        try {
            if (companionId == null) return null;
            var activeRuntime = RddPlugin.runtime(companionId);
            if (activeRuntime != null && activeRuntime.chain().primaryStatus() == com.dwinovo.numen.rdd.api.PrimaryGoalStatus.COMPLETED) {
                REJECTED_ADVICE.keySet().removeIf(key -> key.startsWith(companionId + "|"));
                return null;
            }
            com.dwinovo.numen.rdd.core.RddDeathLedger.Death death = deathForRecompute(companionId, seqHint);
            if (death == null || death.lostItems().isEmpty()) return null;
            String dedupeKey = companionId + "|" + death.seq() + "|" + phase;
            boolean firstObservation = RECOMPUTED.add(dedupeKey);
            var retryRuntime = RddPlugin.runtime(companionId);
            var retryChain = retryRuntime == null ? null : retryRuntime.chain();
            var retryPrimary = retryChain == null ? null : retryChain.currentPrimary();
            AdviceScope scope = retryPrimary == null ? null
                    : new AdviceScope(retryChain, retryChain.planRevision(), retryPrimary.id());
            if (!firstObservation) {
                var rejected = REJECTED_ADVICE.get(dedupeKey);
                if (rejected == null) return null;
                var latest = com.dwinovo.numen.rdd.core.RddDeathLedger.latest(companionId);
                if (!rejected.equals(scope) || latest == null || latest.seq() != death.seq()) {
                    REJECTED_ADVICE.remove(dedupeKey);
                    return null; // never carry old advice into a new plan or death batch
                }
            }
            if (RECOMPUTED.size() > 256) { RECOMPUTED.clear(); REJECTED_ADVICE.clear(); }   // 有界：死亡很多次也不让集合长成漏勺

            // ★ 2026-10-28 实机（#245）：清单缓存**重启即空**（只在"绑一级目标那一刻"写），
            //   于是重算拿不到需求清单 → 只能走下面"无清单也要留证"的兜底（noManifest=true），
            //   "打穿了哪条需求、要不要降档"永远算不出来。
            //   先尝试**按当前一级重建清单**（ensureManifest 现成、按展开指纹幂等）；
            //   重建成功就走完整分析，只有链本身没有一级目标时才退回兜底。
            RequirementManifest.Manifest manifest0 = RddFactContext.currentManifest(companionId);
            if (manifest0 == null) {
                try {
                    var rt0 = RddPlugin.runtime(companionId);
                    var primary0 = rt0 == null ? null : rt0.chain().currentPrimary();
                    if (primary0 != null) {
                        manifest0 = RddFactContext.ensureManifest(companionId, primary0, null);
                        RddMonitor.publish("manifest_rebuilt_for_recompute", Map.of(
                                "companionId", companionId.toString(),
                                "primary", primary0.id(),
                                "reason", "清单缓存重启即空，按当前一级重建后再算损失影响"));
                    }
                } catch (RuntimeException ignored) {
                    // fail-soft：重建失败就退回下面的兜底（至少保住"丢了什么"这条事实）
                }
            }
            var runtime = RddPlugin.runtime(companionId);
            var chain = runtime == null ? null : runtime.chain();
            List<RequirementManifest.Manifest> manifests = remainingManifests(chain, manifest0);
            if (manifests.isEmpty()) {
                Map<String, Object> ev0 = new LinkedHashMap<>();
                ev0.put("companionId", companionId.toString());
                ev0.put("deathSeq", death.seq());
                ev0.put("phase", phase);
                ev0.put("noManifest", true);
                ev0.put("lostKinds", death.lostItems().size());
                ev0.put("lostTotal", death.lostItems().values().stream().mapToInt(Integer::intValue).sum());
                ev0.put("lostItems", new LinkedHashMap<>(death.lostItems()));
                ev0.put("inventoryKnown", counts != null);
                ev0.put("recommendation", "CONFIRM_STOCK");
                ev0.put("summary", "没有当前目标清单，无法按目标算影响；但这次确实丢了 "
                        + death.lostItems().size() + " 种物品，先确认背包再决定补不补");
                if (firstObservation) RddMonitor.publish("loss_impact_recomputed", ev0);
                REJECTED_ADVICE.remove(dedupeKey);
                return null;
            }
            Map<String, AssetSemantics> semantics =
                    RddSemanticsStore.forAnalyzer(RddSemanticsStore.semantics(companionId));
            Map<String, Integer> backup = backupFromBase(companionId);
            LossImpactAnalyzer.LossImpact impact = analyzeRemainingLoss(
                    manifests, death.lostItems(), counts, backup, semantics, lossConfirmed);
            // T4 实证（2026-11-03）：丢 8 cooked_beef、同分钟 hungry:P1/no_food，分析器仍给 NONE。
            // 食物损失 + 身体饥饿/无食物时，至少要 CONFIRM_STOCK，不能宣称“无重要影响”。
            impact = escalateFoodLossIfHungry(companionId, death.lostItems(), impact);

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("companionId", companionId.toString());
            ev.put("deathSeq", death.seq());
            ev.put("phase", phase);
            ev.put("recommendation", impact.recommendation().name());
            ev.put("reason", impact.reason());
            ev.put("importantLoss", impact.importantLoss());
            ev.put("backupSufficient", impact.backupSufficient());
            ev.put("lossConfirmed", impact.lossConfirmed());
            ev.put("inventoryKnown", impact.inventoryKnown());
            ev.put("backupKeyCount", backup.size());
            ev.put("summary", impact.summary());
            List<Map<String, Object>> goals = new ArrayList<>();
            for (LossImpactAnalyzer.GoalImpact g : impact.affectedGoals()) {
                Map<String, Object> gm = new LinkedHashMap<>();
                gm.put("goalId", g.goalId());
                gm.put("requirementKey", g.requirementKey());
                gm.put("minimum", g.minimum());
                gm.put("available", g.available());
                gm.put("backup", g.backup());
                gm.put("coveredBy", g.coveredBy());
                goals.add(gm);
            }
            ev.put("affectedGoals", goals);
            if (firstObservation) RddMonitor.publish("loss_impact_recomputed", ev);

            // 只在她"该改主意"的时候补一句：有备用 → 别去补货；确认缺口 → 去补。
            // CONFIRM_STOCK / NONE 不打扰（死亡那条 nudge 已经说过"先捡、先确认"）。
            boolean delivered = deliverLossAdvice(impact, chain, text -> RddPlugin.nudge(companionId, text));
            if (!delivered && scope != null
                    && impact.recommendation() == LossImpactAnalyzer.Recommendation.SUPPLEMENT
                    && impact.lossConfirmed() && impact.inventoryKnown() && impact.importantLoss())
                REJECTED_ADVICE.put(dedupeKey, scope);
            else REJECTED_ADVICE.remove(dedupeKey);
            return impact;
        } catch (RuntimeException ex) {
            return null;   // 增强旁路：分析炸了不影响支线结账
        }
    }

    /** Read declared remaining requirements only: no lazy expansion, cache overwrite or world read. */
    static List<RequirementManifest.Manifest> remainingManifests(
            com.dwinovo.numen.rdd.core.TaskChain chain, RequirementManifest.Manifest current) {
        if (chain == null || chain.primaryStatus() == com.dwinovo.numen.rdd.api.PrimaryGoalStatus.COMPLETED) return List.of();
        var all = chain.goal().primaryGoals();
        int index = all.indexOf(chain.currentPrimary());
        if (index < 0) return current == null ? List.of() : List.of(current);
        List<RequirementManifest.Manifest> out = new ArrayList<>();
        for (int i = index; i < all.size(); i++) {
            // The UUID cache may still describe the previous stage; read current declarations locally.
            out.add(RddRequirementDetector.forPrimary(all.get(i), chain.goal().id()));
        }
        return List.copyOf(out);
    }

    /** One transient union prevents per-stage derivation from marking another stage's keys stale. */
    static LossImpactAnalyzer.LossImpact analyzeRemainingLoss(List<RequirementManifest.Manifest> manifests,
            Map<String, Integer> lost, Map<String, Integer> counts, Map<String, Integer> backup,
            Map<String, AssetSemantics> existing, boolean confirmed) {
        List<RequirementManifest.Requirement> requirements = new ArrayList<>();
        for (var manifest : manifests) requirements.addAll(manifest.requirements());
        String goalId = manifests.isEmpty() ? "" : manifests.get(0).goalId();
        Map<String, AssetSemantics> semantics = new LinkedHashMap<>(existing);
        var derived = RddSemanticsStore.derive(new RequirementManifest.Manifest(goalId, requirements), goalId, null);
        for (var entry : derived.entrySet()) {
            var previous = semantics.get(entry.getKey());
            if (previous == null || previous.importance() != AssetSemantics.Importance.IMPORTANT)
                semantics.put(entry.getKey(), entry.getValue());
        }
        return LossImpactAnalyzer.analyze(lost, counts == null ? Map.of() : counts, backup,
                manifests, semantics, confirmed, counts != null);
    }

    /** The original executor nudge is advisory; this never requests planning or mutates a chain. */
    static boolean deliverLossAdvice(LossImpactAnalyzer.LossImpact impact,
            com.dwinovo.numen.rdd.core.TaskChain chain, java.util.function.Predicate<String> nudge) {
        if (impact.recommendation() == LossImpactAnalyzer.Recommendation.SWAP_BACKUP) {
            return nudge.test("[损失影响] 回收这一趟结束：" + impact.summary()
                    + "\n→ **基地备用/替代已经够用**：换装继续原目标即可，不必为了这几件回炉重做。"
                    + "如果备用也已经被用掉，就说一声，再去补。");
        } else if (impact.recommendation() == LossImpactAnalyzer.Recommendation.SUPPLEMENT) {
            String review = "";
            if (impact.lossConfirmed() && impact.inventoryKnown() && impact.importantLoss() && chain != null) {
                StringBuilder stages = new StringBuilder();
                var all = chain.goal().primaryGoals();
                int index = all.indexOf(chain.currentPrimary());
                for (int i = index; i >= 0 && i < all.size() && i < index + 12; i++) {
                    var p = all.get(i);
                    String line = p.description() + "；进入前需求：" + p.waitFor();
                    stages.append("\n- ").append(line.length() > 240 ? line.substring(0, 240) + "…（本行截断）" : line);
                }
                if (all.size() > index + 12) stages.append("\n…其余阶段未列，请查看完整任务链。");
                review = "\n本次确认的重要能力缺口可能影响后续阶段，请重新评估整条剩余路线及依赖是否仍可行。"
                        + "可以保留原路、补齐能力或采用替代；仅当后续路线确实不成立时，调用 rdd_replan_chain 改当前阶段之后的阶段。"
                        + "当前阶段与已完成事实不重置，不要因无关消耗重做整链。剩余阶段（最多12条）：" + stages;
            }
            return nudge.test("[损失影响] 回收结束，缺口确认：" + impact.summary()
                    + "\n这几件是真缺（不是没找到），接下来按目标需要补上；别把'没捡到'当成'已经够了'。" + review);
        }
        return false;
    }

    /** 重算要盯的那次死亡：优先当前支线批次，批次已结账则用最近一条。 */
    private static com.dwinovo.numen.rdd.core.RddDeathLedger.Death deathForRecompute(UUID companionId,
                                                                                int seqHint) {
        int seq = seqHint >= 0 ? seqHint : RddRepairDispatch.openDeathSeq(companionId);
        if (seq >= 0) {
            var open = com.dwinovo.numen.rdd.core.RddDeathLedger.openDeath(companionId, seq);
            if (open != null) return open;
        }
        return com.dwinovo.numen.rdd.core.RddDeathLedger.latest(companionId);
    }

    /**
     * 已知备用：只读**缓存里的基地容器观测**（{@code world_base.important_items}，最多 24 种）。
     *
     * <p>三条诚实边界：
     * <ol>
     *   <li>只认"看到过的"：没进前 24 种类的物品视为<b>未观测</b>，不能据此判"没有"；</li>
     *   <li>有新鲜度门槛（10 分钟墙钟）：过期观测只能当线索，不能当"家里有"；</li>
     *   <li>不读世界、不扫容器：这一层在 tick 里被调用，绝不允许为了分析去翻区块。</li>
     * </ol>
     */
    static Map<String, Integer> backupFromBase(UUID companionId) {
        try {
            if (companionId == null) return Map.of();
            var rt = RddPlugin.runtime(companionId);
            if (rt == null || rt.assets() == null) return Map.of();
            long now = System.currentTimeMillis();
            for (var entry : RddAssetContext.worldAssets(rt.assets())) {
                if (!"world_base".equals(entry.observation().type())) continue;
                long age = now - entry.observation().observedAt();
                if (age < 0 || age > 10L * 60L * 1000L) return Map.of();   // 过期 = 未知，不装新鲜
                Object items = entry.observation().value().get("important_items");
                if (!(items instanceof Map<?, ?> map)) return Map.of();
                Map<String, Integer> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!(e.getKey() instanceof String key) || key.isBlank()) continue;
                    Integer n = asInt(e.getValue());
                    if (n != null && n > 0) out.put(key, n);
                }
                return out;
            }
        } catch (RuntimeException ignored) {
            // 备用读不到 = 未知；调用方会走 CONFIRM_STOCK，不会拿"空"当"没有"
        }
        return Map.of();
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String str) {
            try { return Integer.parseInt(str.trim()); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    /**
     * S1 文案渲染：把分析结论翻译给执行 AI（判据 S2/S4）。
     *
     * <p>只有**有话说**时才渲染——importantLoss=false 且无受影响目标 = 没丢重要的，
     * 硬加一段只会教她「什么损失都值得大惊小怪」。SUPPLEMENT 时点明
     * 「目标 X 需要补 xxx ×N」（判据 S4 的行为通道：她下一步该去补）。
     */
    static String renderSemanticNote(LossImpactAnalyzer.LossImpact impact) {
        if (impact == null) return "";
        if (!impact.importantLoss() && impact.affectedGoals().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n[损失影响] ");
        sb.append(impact.summary());
        if (!impact.inventoryKnown()) {
            // 死亡瞬间的口径：读不到 ≠ 0。这句话必须出现，否则执行体容易把"还没数"当"已经没了"。
            sb.append("。此刻还没有重新读到背包（不是\"0 件\"）——先按上面的顺序捡包，捡完再核对。");
            return sb.toString();
        }
        switch (impact.recommendation()) {
            case SUPPLEMENT -> sb.append("。先按上面的顺序把能捡的捡回；捡不回的缺口（尤其受影响目标里的物品）需要重新补充，别装作没看见。");
            case CONFIRM_STOCK -> sb.append("。捡回后核对背包：只确认丢失的部分才需要补，不确定就先数一数再决定。");
            case SWAP_BACKUP -> sb.append("。备用/替代仍能满足需求：换装继续即可，不必专门补货。");
            default -> { return sb.toString(); }
        }
        return sb.toString();
    }

    static void remove(UUID companionId) {
        RECOMPUTED.removeIf(k -> companionId != null && k.startsWith(companionId.toString()));
        REJECTED_ADVICE.keySet().removeIf(k -> companionId != null && k.startsWith(companionId.toString()));
    }
}
