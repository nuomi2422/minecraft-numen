package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.core.InventoryGroups;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;
import com.dwinovo.numen.rdd.fact.FactSnapshot;
import com.dwinovo.numen.rdd.fact.StageFact;
import com.dwinovo.numen.rdd.policy.RequirementManifest;
import com.dwinovo.numen.rdd.policy.RequirementView;
import com.dwinovo.numen.rdd.policy.ReservationLedger;
import com.dwinovo.numen.rdd.policy.TransformationDetector;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共同事实的最小生产接线（第三批 N1 消费侧，2026-10-06）。
 *
 * <p><b>为什么要有这个类</b>：第三批的模型（{@link FactSnapshot}/{@link RequirementManifest}/
 * {@link RequirementView}）此前只有测试调用 —— 需求清单在 bind 时算完即丢（只发一条计数事件），
 * 「需求是否满足」从来没有在真机上被持续检测过（{@code RddRequirementDetector.detectAndPublish}
 * 是死代码）。本类把它们接进生产的三条真实出口：
 *
 * <ol>
 *   <li><b>需求清单落地</b>：一级目标生成时缓存清单（按 primaryId 失效），并保留原有
 *       {@code requirement_manifest} 事件；</li>
 *   <li><b>粗粒度需求检测</b>：每 {30 拍 ≈ 30 秒} 用<b>真实背包计数</b>检测当前一级的需求，
 *       <b>只在状态变化时</b>发事件（满足 / 重新不满足 / 缺口集合变化）——
 *       只产事实，不做裁决（检测 ≠ 重规划）；</li>
 *   <li><b>事实进入 LLM 上下文</b>：重规划提示词追加「需求 × 持有对账 + 近期阶段事实」只读块；
 *       执行 AI 的 {@code <rdd>} 上下文追加当前未凑齐的需求（{@code <needs>}）。</li>
 * </ol>
 *
 * <p><b>★ 三个口径（都写死在类型里，不靠调用方自觉）</b>：
 * <ul>
 *   <li>检测读的是 {@link #tick} 传入的<b>真实背包计数</b>（countInventory），
 *       不是 {@link AssetRegistry} 的缓存折叠 —— 规划侧文档早已定调
 *       「实时扫描是持有唯一真相，注册表只作提示」（{@code PlanningAssetSnapshot}）；
 *       注册表口径只用于 {@link RequirementView} 的旁证列。</li>
 *   <li>事件只报<b>状态变化</b>（未满足集合），不报「缺口文字变化」——
 *       缺口文字含现有数量，捡一个苹果就会变一次，那是刷屏而不是信号。</li>
 *   <li>一切失败 fail-soft 并留日志：事实接线是增强，不许拖垮检测 tick / 上下文构建 / 重规划。</li>
 * </ul>
 *
 * <p><b>线程</b>：本类的缓存全部 {@link ConcurrentHashMap}。{@link #tick} 与
 * {@link #onPrimaryBound} 在服务端线程；{@link #executorBlock} 可能在任何构建上下文的线程
 * （只读缓存，不读世界）；{@link #replanBlock} 在重规划路径（服务端线程）。
 */
final class RddFactContext {

    private static final Logger LOG = LoggerFactory.getLogger("rdd-facts");

    /**
     * 快照 TTL：24 小时。
     *
     * <p>快照里的条目是<b>阶段完成事实</b>（里程碑）——「三天前挖到过钻石」不会因为过期而变假。
     * 这里的 TTL 只防「很久以前的血缘记录被当本轮现况」，<b>不是新鲜度指标</b>；
     * 新鲜度看每条自己的 {@code observedAt}（投影行里带）。
     */
    private static final long SNAPSHOT_TTL_MILLIS = 24L * 60 * 60 * 1000;

    /** 重规划事实块最多列几条需求（按关注度排序取前 N，其余只报条数）。 */
    private static final int MAX_REPLAN_ROWS = 8;
    /** 重规划事实块最多列几条近期阶段事实。 */
    private static final int MAX_RECENT_FACTS = 5;
    /** 执行 AI 上下文里最多列几条未凑齐需求。 */
    private static final int MAX_EXECUTOR_GAPS = 3;
    /** 监测事件里最多列几条缺口。 */
    private static final int MAX_EVENT_GAPS = 6;

    /** 已缓存的需求清单：键 = primaryId，清单的 goalId 必须是 true Goal.id（见 forPrimary 注释）。 */
    /**
     * 已缓存的需求清单。
     *
     * <p>G1b2：{@code expanded}/{@code subtaskFingerprint} 是「展开指纹」——一级懒展开会
     * 悄悄扩大需求集，而 primaryId/goalId 都不变；不把指纹并入命中条件，缓存就会
     * 一直用绑定时刻（unexpanded、二级为空）那份清单。
     *
     * <p>G1c-2：指纹从「二级数」升级为「二级 id 连接串」——重规划可能<b>换掉整组二级
     * 但数量不变</b>（例如把「木棍/火把/圆石」换成「铁锭/面包」），只比数量会漏。
     */
    private record Cached(String primaryId, String goalId, boolean expanded, String subtaskFingerprint,
                          RequirementManifest.Manifest manifest) {}

    /** 需求检测状态：按 primaryId 隔离；换 primary 视为新观察（不报跨 primary 的变化）。 */
    private record Watch(String primaryId, Set<String> unmet) {}

    private static final Map<UUID, Cached> MANIFESTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Watch> WATCH = new ConcurrentHashMap<>();
    private static final Map<UUID, FactSnapshot> LATEST = new ConcurrentHashMap<>();
    /** G1c：每同伴一本软预留账本（进程内；换计划即失效，刻意不持久化）。 */
    private static final Map<UUID, ReservationLedger> LEDGERS = new ConcurrentHashMap<>();
    /** G1c：预留租约时长——到期只释放承诺（IC05），不写任何「丢失」。 */
    private static final long RESERVE_LEASE_MILLIS = 5L * 60_000L;
    /** G1c-2：上一次 tick 的真实背包计数（转化检测的对照面；不参与判定）。 */
    private static final Map<UUID, Map<String, Integer>> LAST_COUNTS = new ConcurrentHashMap<>();
    /** G1c-2：一次 tick 最多发几条转化事件（有界纪律）。 */
    private static final int MAX_TRANSFORM_EVENTS = 3;

    private RddFactContext() {
    }

    // ───────────────────────── 纯逻辑（可离线单测） ─────────────────────────

    /**
     * 一条「被跟踪的需求」：{@code asset_key} 或 {@code group}。
     *
     * <p>{@code group} 需求只有三个已知组（food/wood/blocks，见 {@link InventoryGroups}）——
     * 组计数是<b>组内物品件数之和</b>，不是配方等价；未知组名直接不跟踪（不猜）。
     *
     * <p>★ G1b：{@code importance} 透传给渲染层（{@code CRITICAL} 渲染 `[关键]` 前缀，
     * 见 {@link #renderNeedsBlock}）。group 需求一律 NORMAL——组是检测通道的归纳，
     * 不是目标结构的前置，没有「关键门」语义。
     */
    record Need(String key, int minimum, boolean group,
                RequirementManifest.Importance importance) {
        Need(String key, int minimum, boolean group) {
            this(key, minimum, group, RequirementManifest.Importance.NORMAL);
        }

        String display() {
            return group ? "group:" + key : key;
        }

        /** G1b：CRITICAL 需求的人类/AI 可读前缀；NORMAL/OPPORTUNISTIC 无前缀。 */
        String importancePrefix() {
            return importance() == RequirementManifest.Importance.CRITICAL ? "[关键] " : "";
        }
    }

    /**
     * 从一级目标提取被跟踪需求：{@code waitFor} 前置 + 各二级 condition 的
     * {@code asset_key}/{@code group}。
     *
     * <p>同键取<b>更大</b>的 minimum（同一级的两个二级要 2 个和 5 个铁，跟踪 5 个才诚实）；
     * {@code waitFor} 排在前面（它是进入本一级的前置，语义上最先约束）。
     *
     * <p>★ G1b：{@code waitFor} 前置标 {@code CRITICAL}（进本一级的门），condition 标
     * {@code NORMAL}；同键取更高档（与 {@code RddRequirementDetector.merge} 同规则，
     * 两处口径必须一致否则清单与上下文块互相矛盾）。group 需求恒 NORMAL。
     */
    static List<Need> needsOf(PrimaryGoal primary) {
        Map<String, Need> out = new LinkedHashMap<>();
        if (primary == null) {
            return List.of();
        }
        for (AssetRequirement w : primary.waitFor()) {
            if (w != null && w.assetKey() != null && !w.assetKey().isBlank()) {
                mergeNeed(out, w.assetKey(), w.minimum(), false,
                        RequirementManifest.Importance.CRITICAL);
            }
        }
        for (Subtask s : primary.subtasks()) {
            Map<String, Object> c = s.condition();
            if (c == null || c.isEmpty()) {
                continue;
            }
            Object key = c.get("asset_key");
            Object group = c.get("group");
            Object m = c.get("minimum");
            int min = m instanceof Number n ? Math.max(0, n.intValue()) : 1;
            if (key instanceof String ks && !ks.isBlank()) {
                mergeNeed(out, ks, min, false, RequirementManifest.Importance.NORMAL);
            } else if (group instanceof String gs && InventoryGroups.known(gs)) {
                mergeNeed(out, gs, min, true, RequirementManifest.Importance.NORMAL);
            }
        }
        return List.copyOf(out.values());
    }

    private static void mergeNeed(Map<String, Need> out, String key, int minimum, boolean group,
                                  RequirementManifest.Importance importance) {
        int min = Math.max(1, minimum);   // 需求至少 1 件；0 按 1 计（与 AssetRequirement 口径一致）
        String k = group ? "group:" + key : key;
        Need prev = out.get(k);
        if (prev == null) {
            out.put(k, new Need(key, min, group, importance));
        } else if (min > prev.minimum()
                || importance.ordinal() < prev.importance().ordinal()) {   // 同键取更高档（G1b）
            out.put(k, new Need(key, Math.max(min, prev.minimum()), group,
                    importance.ordinal() <= prev.importance().ordinal() ? importance : prev.importance()));
        }
    }

    /** 该需求在给定持有计数下现有多少（group 走组内求和；held 为 null 视为空背包）。 */
    static int have(Need need, Map<String, Integer> held) {
        if (need == null || held == null) {
            return 0;
        }
        if (need.group()) {
            return (int) Math.min(Integer.MAX_VALUE, InventoryGroups.count(need.key(), held));
        }
        Integer v = held.get(need.key());
        return v == null ? 0 : v;
    }

    /** 未满足的需求（display 键集合，保持声明顺序）。 */
    static LinkedHashSet<String> unmetKeys(List<Need> needs, Map<String, Integer> held) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Need need : needs) {
            if (have(need, held) < need.minimum()) {
                out.add(need.display());
            }
        }
        return out;
    }

    /** 需求状态迁移；只对「同一 primary 内」的两次观察有意义。 */
    enum Transition {
        /** 无变化（含两次都满足）。 */
        NONE,
        /** 从「有未满足」变成「全部满足」。 */
        MET,
        /** 从「全部满足」变成「有未满足」（东西用掉了/丢了 —— 必须也说）。 */
        UNMET,
        /** 未满足集合换了内容（得 A 丢 B）。 */
        GAPS_CHANGED
    }

    static Transition transition(Set<String> prev, Set<String> next) {
        if (prev == null || next == null) {
            return Transition.NONE;
        }
        if (!prev.isEmpty() && next.isEmpty()) {
            return Transition.MET;
        }
        if (prev.isEmpty() && !next.isEmpty()) {
            return Transition.UNMET;
        }
        return prev.equals(next) ? Transition.NONE : Transition.GAPS_CHANGED;
    }

    /** 事件里的缺口短文本（有界；只在事件帧用，不用于逐轮上下文）。 */
    static String unmetText(List<Need> needs, Map<String, Integer> held) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Need need : needs) {
            int now = have(need, held);
            if (now >= need.minimum()) {
                continue;
            }
            if (n == MAX_EVENT_GAPS) {
                sb.append("…");
                break;
            }
            if (n > 0) {
                sb.append("；");
            }
            sb.append(need.display()).append(" 需").append(need.minimum()).append(" 有").append(now);
            n++;
        }
        return sb.toString();
    }

    /** 执行 AI 上下文块：只列未凑齐的需求（≤3 条），全凑齐就整块不出现。
     *
     * <p>★ G1b：CRITICAL 需求带 {@code [关键]} 前缀（到达模型请求正文 = 消费者接通的判据，
     * S44）。<b>排序不变</b>——仍按声明序，不按重要度排：排序归检索层（75号 §4.1），
     * 执行侧上下文块只做可见性，不做调度裁决（D09 执行局部应变自由）。
     *
     * <p>★ G1c：{@code reservedByKey} 非空时给对应行追加「（已预留 N）」——让执行 AI
     * 看见「这批里有多少件已经被许给别的需求」，而不是把全部现有量都当自己可用。
     */
    static String renderNeedsBlock(List<Need> needs, Map<String, Integer> held) {
        return renderNeedsBlock(needs, held, Map.of());
    }

    static String renderNeedsBlock(List<Need> needs, Map<String, Integer> held,
                                   Map<String, Integer> reservedByKey) {
        List<Need> unmet = new ArrayList<>();
        for (Need need : needs) {
            if (have(need, held) < need.minimum()) {
                unmet.add(need);
            }
        }
        if (unmet.isEmpty()) {
            return "";
        }
        Map<String, Integer> reserved = reservedByKey == null ? Map.of() : reservedByKey;
        StringBuilder sb = new StringBuilder("<needs>一级目标需求还差：");
        int n = 0;
        for (Need need : unmet) {
            if (n == MAX_EXECUTOR_GAPS) {
                sb.append("…");
                break;
            }
            if (n > 0) {
                sb.append("；");
            }
            sb.append(need.importancePrefix()).append(need.display()).append(" ×").append(need.minimum())
                    .append("（现有 ").append(have(need, held)).append("）");
            int r = reserved.getOrDefault(need.display(), 0);
            if (r > 0) {
                sb.append("（已预留 ").append(r).append("）");
            }
            n++;
        }
        sb.append("。凑齐即消失；这只有数量，不是指令。</needs>");
        return sb.toString();
    }

    /**
     * 重规划事实块：需求 × 持有对账（{@link RequirementView}）+ 近期阶段事实。
     *
     * <p>只读陈述：缺口数字 ≠「没做」；「持有未知」= 没扫过登记表，也不代表 0。
     * 无需求清单时返回空串（不加噪音）。
     */
    static String renderReplanBlock(RequirementManifest.Manifest manifest, CompletedFactStore facts,
                                    AssetRegistry assets, FactSnapshot snapshot, long now) {
        if (manifest == null || manifest.requirements().isEmpty()) {
            return "";
        }
        RequirementView.View view = RequirementView.build(manifest, facts, assets, null);
        if (view.rows().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【需求 × 持有对账（只读；共同事实快照 ").append(ageText(now, snapshot.capturedAt()))
                .append("采样，维度 ").append(snapshot.dimensionId()).append("）】\n");
        int n = 0;
        for (RequirementView.Row row : view.rows()) {
            if (n == MAX_REPLAN_ROWS) {
                sb.append("- ……其余 ").append(view.rows().size() - MAX_REPLAN_ROWS).append(" 条略（关注项已在前）\n");
                break;
            }
            n++;
            // G1b：CRITICAL 需求行带 [关键] 前缀（与 executorBlock 的 needs 块同文案口径）；
            // 排序仍按 attention（可疑度），不按重要度——本块是只读对账，不是调度裁决。
            String impPrefix = row.importance() == RequirementManifest.Importance.CRITICAL
                    ? "[关键] " : "";
            sb.append("- ").append(impPrefix).append(row.requirementKey()).append(" 需要 ").append(row.minimum());
            if (row.held() == null) {
                sb.append("，持有未知（登记表没这个键，不等于 0）");
            } else {
                sb.append("，现有 ").append(row.held());
            }
            if (row.heldGap() > 0) {
                sb.append(" → 缺口 ").append(row.heldGap());
            }
            if (row.factCount() > 0) {
                sb.append("｜事实记录 ").append(row.factCount()).append(" 条");
            }
            // ★ 持有未知时**不能**再渲染 attention 文案：RequirementView 对 UNKNOWN_HELD
            //   刻意给 NONE（"判断不了 ≠ 不用看"），而 attentionText(NONE) 是"持有已达标" ——
            //   实机抓到的错标：同一行同时写「持有未知」与「持有已达标」。
            //   未知行只保留上面那句"持有未知（登记表没这个键，不等于 0）"。
            if (row.held() != null) {
                String attention = attentionText(row.attention());
                if (!attention.isEmpty()) {
                    sb.append("｜").append(attention);
                }
            }
            sb.append('\n');
        }
        String recent = renderRecentFacts(facts, manifest.goalId(), now);
        if (!recent.isEmpty()) {
            sb.append("近期已完成的阶段（共同事实）：\n").append(recent);
        }
        if (view.notes().stream().anyMatch(t -> t != null && t.startsWith("REGISTRY_EMPTY"))) {
            sb.append("（注：资产登记表为空 = 还没扫过背包；上列「持有未知」不代表 0）\n");
        }
        sb.append("（只读陈述：缺口数字 ≠「没做」；对账列不改变任何一侧。）");
        return sb.toString();
    }

    private static String attentionText(RequirementView.Attention attention) {
        return switch (attention) {
            case DONE_BUT_NOT_HELD -> "关注：事实有完成记录但持有不足（疑似消耗/采集口径）";
            case NOT_DONE_AND_NOT_HELD -> "持有不足";
            case HELD_BUT_NO_FACT -> "持有已达标";
            case NONE -> "持有已达标";
        };
    }

    /** 近期阶段事实（按 manifest.goalId = 真 Goal.id 过滤，时间倒序，≤5 条）。 */
    private static String renderRecentFacts(CompletedFactStore facts, String goalId, long now) {
        if (facts == null || goalId == null || goalId.isBlank()) {
            return "";
        }
        List<StageFact> matched = new ArrayList<>();
        for (StageFact f : facts.stageFacts()) {
            if (f != null && goalId.equals(f.goalId()) && f.rawStage() != null && !f.rawStage().isBlank()) {
                matched.add(f);
            }
        }
        if (matched.isEmpty()) {
            return "";
        }
        matched.sort(Comparator.comparingLong(StageFact::completedAtMillis).reversed());
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StageFact f : matched) {
            if (n == MAX_RECENT_FACTS) {
                sb.append("- ……\n");
                break;
            }
            sb.append("- 《").append(f.rawStage()).append("》 ").append(ageText(now, f.completedAtMillis())).append('\n');
            n++;
        }
        return sb.toString();
    }

    private static String ageText(long now, long at) {
        long ms = Math.max(0L, now - at);
        if (ms < 60_000L) {
            return "刚刚";
        }
        if (ms < 60L * 60_000L) {
            return (ms / 60_000L) + " 分钟前";
        }
        return (ms / (60L * 60_000L)) + " 小时前";
    }

    // ───────────────────────── 宿主侧（生产出口） ─────────────────────────

    /**
     * 一级目标生成（或推进到新 primary）时缓存需求清单 —— 对应「架构概念 2/4」的
     * 「生成时同步产出需求清单」。事件字段与旧实现逐字一致（只是不再算完即丢）。
     *
     * @param goalId 真 {@code Goal.id}（事实库的 goalId 就是它；传 primary.id 会让对账列永远 join 不上）
     */
    static void onPrimaryBound(UUID companionId, PrimaryGoal primary, String goalId) {
        if (companionId == null) {
            return;
        }
        try {
            RequirementManifest.Manifest manifest = RddRequirementDetector.forPrimary(primary, goalId);
            // G1b2：绑定时刻的展开指纹必须一起落缓存（unexpanded / 二级数），
            // 否则 ensureManifest 的命中条件永远对不上，会在下一次 tick 白重建一遍。
            MANIFESTS.put(companionId, new Cached(primary == null ? "" : primary.id(), manifest.goalId(),
                    primary != null && !primary.unexpanded(), subtaskFingerprint(primary), manifest));
            WATCH.remove(companionId);   // 换目标 = 检测状态重来（不报跨目标的"变化"）
            publishManifestEvent(companionId, primary, manifest);   // G1b：事件统一出口（带 critical 计数）
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 需求清单生成失败 {}: {}", companionId, ex.toString());
        }
    }

    /** 同伴任务清空时一并清掉事实接线缓存（防内存缓慢增长）。 */
    static void forget(UUID companionId) {
        if (companionId == null) {
            return;
        }
        MANIFESTS.remove(companionId);
        WATCH.remove(companionId);
        LATEST.remove(companionId);
        LEDGERS.remove(companionId);   // G1c：任务清空 = 承诺作废（账本不跨计划存活）
        LAST_COUNTS.remove(companionId);   // G1c-2：清空的同伴不再拿旧快照当对照面
    }

    /** 最近一次快照（观测/测试用；没有就是没采集过，不造空快照）。 */
    static FactSnapshot latest(UUID companionId) {
        return companionId == null ? null : LATEST.get(companionId);
    }

    /**
     * 每 30 秒一跳：缓存清单（primary 推进时补发事件）→ 采集快照 → 需求检测（只在状态变化时发事件）。
     *
     * <p>{@code counts} 必须是<b>本拍真实背包扫描</b>。全部包在 try 里：
     * 事实接线失败不许拖垮检测 tick。
     */
    static void tick(UUID companionId, String dimensionId, RddRuntime rt, Map<String, Integer> counts) {
        if (companionId == null || rt == null) {
            return;
        }
        try {
            PrimaryGoal primary = rt.chain().currentPrimary();
            if (primary == null) {
                return;
            }
            String goalId = rt.chain().goal() == null ? null : rt.chain().goal().id();
            RequirementManifest.Manifest manifest = ensureManifest(companionId, primary, goalId);
            long now = System.currentTimeMillis();
            FactSnapshot snapshot = FactSnapshot.capture(companionId, dimensionId,
                    RddPlugin.facts(companionId), rt.assets(), null, now, SNAPSHOT_TTL_MILLIS);
            LATEST.put(companionId, snapshot);

            List<Need> needs = needsOf(primary);
            Set<String> unmet = Collections.unmodifiableSet(unmetKeys(needs, counts));
            Watch prev = WATCH.get(companionId);
            WATCH.put(companionId, new Watch(primary.id(), unmet));
            // G1c：关键需求软预留（D11）。只给 CRITICAL 需求申请；申请量取
            // min(需求, 现有)——软预留只承诺「现有的」，不去许愿天上有多少。
            int reservedTotal = ensureReservations(companionId, primary, needs, counts, now);
            // G1c-2：转化证据（只落事件，不参与判定）。对照面是「上一次 tick 的真实背包」，
            // 30 秒窗口内的合成/熔炼能被识别成「原木→木板」这类形态变化，
            // 从而与「东西丢了」区分开（75号 §3.2）。
            publishTransforms(companionId, counts);
            if (prev != null && prev.primaryId().equals(primary.id()) && !needs.isEmpty()) {
                switch (transition(prev.unmet(), unmet)) {
                    case MET -> RddMonitor.publish("requirements_met", Map.of(
                            "companionId", companionId.toString(),
                            "primary", primary.id(),
                            "requirements", String.valueOf(needs.size())));
                    case UNMET -> RddMonitor.publish("requirements_unmet", Map.of(
                            "companionId", companionId.toString(),
                            "primary", primary.id(),
                            "gaps", unmetText(needs, counts)));
                    case GAPS_CHANGED -> RddMonitor.publish("requirement_gaps_changed", Map.of(
                            "companionId", companionId.toString(),
                            "primary", primary.id(),
                            "gaps", unmetText(needs, counts)));
                    case NONE -> { }
                }
            }

            Map<String, Object> event = new LinkedHashMap<>(snapshot.describe());
            event.put("primary", primary.id());
            event.put("needs", needs.size());
            event.put("unmet", unmet.size());
            event.put("reserved", reservedTotal);   // G1c：本 primary 的有效预留总量（有界计数）
            RddMonitor.publish("fact_snapshot", event);
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 共同事实 tick 失败 {}: {}", companionId, ex.toString());
        }
    }

    /**
     * 缓存清单；primary/目标变了 <b>或展开指纹变了</b> 才重建（并补发一份清单事件）。
     *
     * <p>★ G1b2（V-20261020-13 债①）：缓存键原本只有 {@code (primaryId, goalId)}，
     * 而 Stage-A 绑定一级时它还是 unexpanded、二级为空 —— 清单只含 waitFor；
     * GoalDriver 懒展开注入二级后 primary.id/goalId 都不变 → 旧缓存永不失效，
     * replan 块与 manifest 事件少报展开后的需求集（执行侧 needs 块走
     * {@link #needsOf} 现算，不受影响）。
     * 修法：把「展开指纹」（unexpanded 标志 + 二级数）并入命中条件 —— 展开就是
     * 「需求集变了」，与「换 primary = 重观察」同一条纪律。
     */
    static RequirementManifest.Manifest ensureManifest(UUID companionId, PrimaryGoal primary, String goalId) {
        String gid = goalId == null || goalId.isBlank() ? primary.id() : goalId;
        boolean expanded = primary != null && !primary.unexpanded();
        String fingerprint = subtaskFingerprint(primary);
        Cached cached = MANIFESTS.get(companionId);
        if (cached != null && primary != null && primary.id().equals(cached.primaryId())
                && gid.equals(cached.goalId())
                && expanded == cached.expanded() && fingerprint.equals(cached.subtaskFingerprint())) {
            return cached.manifest();
        }
        RequirementManifest.Manifest manifest = RddRequirementDetector.forPrimary(primary, gid);
        MANIFESTS.put(companionId, new Cached(primary == null ? "" : primary.id(), gid,
                expanded, fingerprint, manifest));
        publishManifestEvent(companionId, primary, manifest);   // G1b：bind 之外第二个出口同字段
        return manifest;
    }

    /**
     * 展开指纹：二级 id 的连接串。
     *
     * <p>G1c-2：只比「二级数」会漏掉「整组换掉但数量不变」的重规划；id 连接串能识别，
     * 且顺序敏感（重排也算变了——需求集未必变，但按「变了就重算」的保守侧处理）。
     * 未展开时为空串。
     */
    static String subtaskFingerprint(PrimaryGoal primary) {
        if (primary == null || primary.subtasks() == null || primary.subtasks().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < primary.subtasks().size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(primary.subtasks().get(i).id());
        }
        return sb.toString();
    }

    /**
     * G1b：manifest 事件统一出口——旧三字段（companionId/primary/requirements）逐字保留，
     * 新增 {@code critical}（CRITICAL 条数）。只发计数不发逐条清单（事件有界纪律）。
     */
    private static void publishManifestEvent(UUID companionId, PrimaryGoal primary,
                                             RequirementManifest.Manifest manifest) {
        int critical = 0;
        for (RequirementManifest.Requirement r : manifest.requirements()) {
            if (r != null && r.importance() == RequirementManifest.Importance.CRITICAL) {
                critical++;
            }
        }
        RddMonitor.publish("requirement_manifest", Map.of(
                "companionId", companionId.toString(),
                "primary", primary.id(),
                "requirements", String.valueOf(manifest.requirements().size()),
                "critical", String.valueOf(critical)));
    }

    /**
     * G1c：给当前一级的 CRITICAL 需求做软预留（D11：关键资源明确优先用途，可协商不锁死）。
     *
     * <p>规则：
     * <ul>
     *   <li>只给 {@code CRITICAL} 需求申请；{@code NORMAL/OPPORTUNISTIC} 按需分配（D11）。</li>
     *   <li>申请量 = `min(需求, 现有)`——软预留只承诺「现有的」，不去许愿天上有多少；
     *       申请量 0 就不占条目（避免账本里一堆 0）。</li>
     *   <li>换 primary 时 {@code retainOnly} 释放上一级的承诺（预留是「当前计划的承诺」）。</li>
     *   <li>到期 {@code releaseExpired} 只释放承诺，不写「丢失」（IC05）。</li>
     * </ul>
     *
     * @return 本 primary 的有效预留总量（事件里报这个数）
     */
    private static int ensureReservations(UUID companionId, PrimaryGoal primary, List<Need> needs,
                                          Map<String, Integer> counts, long now) {
        try {
            ReservationLedger ledger = ledger(companionId);
            ledger.releaseExpired(now);
            ledger.retainOnly(primary.id());
            for (Need need : needs) {
                if (need.importance() != RequirementManifest.Importance.CRITICAL) {
                    continue;
                }
                int want = Math.min(need.minimum(), have(need, counts));
                if (want <= 0) {
                    continue;
                }
                ledger.reserve(primary.id(), need.display(), want,
                        need.importance().ordinal(), now + RESERVE_LEASE_MILLIS, counts);
            }
            int total = 0;
            for (ReservationLedger.Reservation r : ledger.reservations()) {
                total += r.quantity();
            }
            return total;
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 预留账本失败 {}: {}", companionId, ex.toString());
            return 0;
        }
    }

    /** 该同伴的预留账本（懒建；进程内生命周期，刻意不持久化——换计划即失效）。 */
    static ReservationLedger ledger(UUID companionId) {
        return LEDGERS.computeIfAbsent(companionId, k -> new ReservationLedger());
    }

    /**
     * G1c-2：转化证据。拿「这次 vs 上次」两份真实背包计数跑 {@link TransformationDetector}，
     * 命中就发 {@code asset_transform} 事件（每条只带 kind + 输入/输出短文本，有界）。
     *
     * <p>★ 刻意<b>不改判定</b>：这一批只是把「东西换了形态」记下来，
     * 需求检测仍然只看持有量。让判定不再把转化当缺口，是后续批的事。
     */
    private static void publishTransforms(UUID companionId, Map<String, Integer> counts) {
        try {
            if (counts == null) {
                return;
            }
            Map<String, Integer> prev = LAST_COUNTS.put(companionId, Map.copyOf(counts));
            if (prev == null || prev.isEmpty()) {
                return;   // 第一次扫描没有对照面
            }
            int n = 0;
            for (TransformationDetector.Transform t : TransformationDetector.detect(prev, counts)) {
                if (n == MAX_TRANSFORM_EVENTS) {
                    break;
                }
                RddMonitor.publish("asset_transform", Map.of(
                        "companionId", companionId.toString(),
                        "kind", t.kind(),
                        "inputs", joinCounts(t.inputs()),
                        "outputs", joinCounts(t.outputs())));
                n++;
            }
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 转化检测失败 {}: {}", companionId, ex.toString());
        }
    }

    private static String joinCounts(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) {
                sb.append('+');
            }
            sb.append(e.getKey()).append('×').append(e.getValue());
        }
        return sb.toString();
    }

    /** 当前 primary 的预留（资源键 → 件数），供执行上下文块渲染「已预留 N」。 */
    static Map<String, Integer> reservedFor(UUID companionId, String primaryId) {
        try {
            ReservationLedger ledger = LEDGERS.get(companionId);
            if (ledger == null || primaryId == null) {
                return Map.of();
            }
            Map<String, Integer> out = new LinkedHashMap<>();
            for (ReservationLedger.Reservation r : ledger.reservations()) {
                if (primaryId.equals(r.demandKey())) {
                    out.merge(r.resourceKey(), r.quantity(), Integer::sum);
                }
            }
            return out;
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    /**
     * 执行 AI 的 {@code <rdd>} 上下文块（只读缓存，不读世界）。
     *
     * <p>从未扫过背包（{@code lastInventoryAtMillis == null}）时返回空串 ——
     * 「没扫过」不能让 AI 以为「什么都没有」。清单与当前 primary 对不上时也返回空
     * （不拿上一个阶段的需求误导）。
     */
    static String executorBlock(UUID companionId) {
        try {
            if (companionId == null) {
                return "";
            }
            RddRuntime rt = RddPlugin.runtime(companionId);
            if (rt == null) {
                return "";
            }
            PrimaryGoal primary = rt.chain().currentPrimary();
            Cached cached = MANIFESTS.get(companionId);
            if (primary == null || cached == null || !primary.id().equals(cached.primaryId())) {
                return "";
            }
            if (RddPlugin.lastInventoryAtMillis(companionId) == null) {
                return "";
            }
            return renderNeedsBlock(needsOf(primary), RddPlugin.lastInventory(companionId),
                    reservedFor(companionId, primary.id()));
        } catch (RuntimeException ex) {
            return "";   // 增强块，绝不能拖崩上下文构建
        }
    }

    /**
     * 重规划提示词事实块（只读）。同伴不在线 / 拿不到维度时返回空串 —— 不猜。
     *
     * <p>清单在缓存里对不上当前 primary 时<b>现算一份</b>（用真 Goal.id），不改缓存。
     */
    static String replanBlock(UUID companionId) {
        try {
            if (companionId == null) {
                return "";
            }
            RddRuntime rt = RddPlugin.runtime(companionId);
            if (rt == null) {
                return "";
            }
            PrimaryGoal primary = rt.chain().currentPrimary();
            if (primary == null) {
                return "";
            }
            net.minecraft.server.MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            NumenPlayer ap = server == null ? null : NumenPlayer.findByUuid(server, companionId);
            if (ap == null) {
                return "";   // 同伴不在线：维度与快照都不猜
            }
            String dimensionId = ap.level().dimension().location().toString();
            String goalId = rt.chain().goal() == null ? null : rt.chain().goal().id();
            Cached cached = MANIFESTS.get(companionId);
            RequirementManifest.Manifest manifest =
                    cached != null && primary.id().equals(cached.primaryId())
                            ? cached.manifest()
                            : RddRequirementDetector.forPrimary(primary, goalId);
            long now = System.currentTimeMillis();
            FactSnapshot snapshot = FactSnapshot.capture(companionId, dimensionId,
                    RddPlugin.facts(companionId), rt.assets(), null, now, SNAPSHOT_TTL_MILLIS);
            return renderReplanBlock(manifest, RddPlugin.facts(companionId), rt.assets(), snapshot, now);
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 重规划事实块生成失败 {}: {}", companionId, ex.toString());
            return "";
        }
    }
}
