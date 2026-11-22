package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.policy.RiskGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

/**
 * 高风险准入适配：分类、当前事实、基地门与裁决观测，由 RddDetector 委托。
 *
 * <p>不另建读数或门禁框架：复用 RiskGateGuard 与现有背包/重生点 getter，
 * 共用 Detector 的快照限频；遥测失败不改变已定裁决，也不推进 runtime。
 */
final class RddRiskAdmission {
    private static final Logger LOG = LoggerFactory.getLogger(RddRiskAdmission.class);

    private RddRiskAdmission() {}

    /**
     * 高风险准入**硬门**（防"铁套进下界"式死亡循环）：
     * 当前一级若是下界/末地级，用 {@link RiskGate#checkWithRecovery}（物资+药水+恢复点）硬判；
     * 不达标 → 不激活、不派工（保持 WAITING）。NORMAL/MINING 直接放行。
     */
    /**
     * 风险门开关（B3/166 起默认开）。
     *
     * <p>历史：2026-09-28 曾按当时指示默认关闭，理由是 {@code levelForText} 把「也绝不进入下界」
     * 这类禁止项误判成要去下界，给原地待命阶段挂了下界硬门，任务永远派不下去。
     * 该误判已在 {@code RiskGate.actionFor} 用否定句守卫修掉（B3），所以门重新默认生效：
     * 缺恢复点/备用/食物/武器时不再放行高风险阶段。
     *
     * <p>仍保留 {@code -Drdd.riskGate=false} 与 {@link #setRiskGateEnabled(boolean)} 作为显式关闭口，
     * 关闭时必须在 run envelope / taskchain_snapshot 里可见（事件 {@code risk_gate_state}）。
     */
    private static volatile boolean riskGateEnabled = !"false".equalsIgnoreCase(
            System.getProperty("rdd.riskGate", "true"));

    /** 运行时开关风险门（默认开）。 */
    static void setRiskGateEnabled(boolean enabled) {
        riskGateEnabled = enabled;
    }

    /** 风险门当前是否生效（诊断用）。 */
    static boolean riskGateActive() {
        return riskGateEnabled;
    }

    /** B3/166：风险门去重键 → 上次判定（只在状态变化时发事件，避免每秒刷屏）。 */
    private static final Map<String, String> LAST_RISK_VERDICT = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean riskGateAllows(NumenPlayer ap, String primaryDescription) {
        // B3/166：门默认开。2026-09-28 关它的根因（禁止项被判成行动）已在 RiskGate 用否定句守卫修掉；
        // 显式关闭仍可用 -Drdd.riskGate=false 或 setRiskGateEnabled(false)，关闭事实会落 risk_gate_state。
        if (!riskGateEnabled) {
            return true;
        }
        // 2026-11-04（037/T7 · SAFE-01）：分类失败不再当成"不危险"。
        com.dwinovo.numen.rdd.policy.RiskLevel level;
        try {
            level = com.dwinovo.numen.rdd.policy.RiskGate.levelForText(primaryDescription);
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 风险分类异常，按高风险处理: {}", ex.toString());
            return applyGateDecision(ap, primaryDescription, null,
                    com.dwinovo.numen.rdd.policy.RiskGateGuard.classifyFailed(ex), Map.of());
        }
        // 事实采集整体交给 RiskGateGuard：非高风险级它根本不采（NORMAL/MINING 行为不变），
        // 高风险级观测失败则拒绝——「观测越糟越容易放行」的旧语义到此为止。
        Map<String, Integer> inv = new java.util.LinkedHashMap<>();
        var decision = com.dwinovo.numen.rdd.policy.RiskGateGuard.decide(level, lv -> {
            inv.putAll(RddDetector.countInventory(ap));
            boolean hasRecovery = RddDetector.hasBoundRespawn(ap);
            // 167 §B6：把基地备用接进门。只收紧不放松——基地达标时与 checkWithRecovery 完全一致。
            var baseRegistry = RddPlugin.assets(ap.getUUID());
            // 2026-11-04（TC-RESERVE-01）：known / 缺口 / 阶段从**同一份**基地视图派生，共用一个 now。
            var homeIdentity = RddPlugin.cachedHomeIdentity(ap.getUUID());
            var home = RddHomeFacts.HomeView.of(baseRegistry, System.currentTimeMillis(),
                    homeIdentity == null ? null : homeIdentity.identity(), homeIdentity == null ? null : homeIdentity.x(),
                    homeIdentity == null ? null : homeIdentity.y(), homeIdentity == null ? null : homeIdentity.z());
            var verdict = com.dwinovo.numen.rdd.policy.RiskGate.checkWithBase(lv, inv, hasRecovery,
                    home.reserve().known(), home.reserve().shortfall());
            // 167 §B3：基地阶段是高风险动作的硬依赖（只收紧）。物资齐了但家里没底，同样不许进。
            return com.dwinovo.numen.rdd.policy.HomeGate.enforce(verdict, home.stage());
        });
        return applyGateDecision(ap, primaryDescription, level, decision, inv);
    }

    /**
     * 裁决收口：先定裁决，再发遥测——**遥测失败不许改变裁决**（037/T7 的"隔离日志失败"要求）。
     */
    private static boolean applyGateDecision(NumenPlayer ap, String primaryDescription,
                                             com.dwinovo.numen.rdd.policy.RiskLevel level,
                                             com.dwinovo.numen.rdd.policy.RiskGateGuard.Decision decision,
                                             Map<String, Integer> inventory) {
        try {
            if (decision.error() != null) {
                publishGateException(ap, primaryDescription, decision, inventory);
            } else if (decision.verdict() != null) {
                publishRiskVerdict(ap, primaryDescription, decision.level() == null ? level : decision.level(),
                        decision.verdict(), inventory == null ? Map.of() : inventory);
            }
        } catch (RuntimeException ignored) {
            // 遥测是旁路：发不出去也不能反过来改变"放不放行"。
        }
        return decision.allowed();
    }

    /**
     * 风险门观测/分类异常的拦截事件（037/T7）。
     *
     * <p>为什么必须发事件而不只写日志：日志没人看，模型和面板只能看到"没派工"。
     * 事件里带 reason 与 error，恢复后下一次正常裁决会照常发 {@code risk_gate_opened/blocked}，
     * 所以不需要清除什么状态，也就不会留下永久锁。
     */
    private static void publishGateException(NumenPlayer ap, String primaryDescription,
                                             com.dwinovo.numen.rdd.policy.RiskGateGuard.Decision decision,
                                             Map<String, Integer> inventory) {
        try {
            UUID uuid = ap.getUUID();
            if (!RddDetector.shouldPublishGatedSnapshot(uuid, String.valueOf(primaryDescription).hashCode() + "|" + decision.reason(), "riskgate_error")) {
                return;   // 同一目标同一原因 10 秒内只报一次
            }
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("companionId", uuid.toString());
            data.put("riskLevel", decision.level() == null ? "UNKNOWN" : decision.level().name());
            data.put("allowed", false);
            data.put("reason", decision.reason());
            data.put("error", decision.error());
            data.put("missing", java.util.List.of("risk_gate=error need fresh observation"));
            data.put("prepTasks", java.util.List.of("先让观测恢复正常（背包/恢复点/基地读数），再重试高风险动作"));
            data.put("primaryHint", String.valueOf(primaryDescription).length() > 120
                    ? String.valueOf(primaryDescription).substring(0, 120) : String.valueOf(primaryDescription));
            if (inventory != null && !inventory.isEmpty()) {
                data.put("inventoryKeys", Math.min(inventory.size(), 8));
            }
            RddMonitor.publish("risk_gate_blocked", data);
        } catch (RuntimeException ignored) {
            // 事件发不出去不影响已经定下的裁决
        }
    }

    /**
     * B3/166：风险门裁决只在「同一阶段结论变化」时发一次事件。
     * 允许 → {@code risk_gate_opened}；阻止 → {@code risk_gate_blocked}（带 missing 缺口）。
     */
    private static void publishRiskVerdict(NumenPlayer ap, String primaryDescription,
                                           com.dwinovo.numen.rdd.policy.RiskLevel level,
                                           com.dwinovo.numen.rdd.policy.RiskGate.Verdict verdict,
                                           Map<String, Integer> inventory) {
        try {
            UUID uuid = ap.getUUID();
            String key = uuid + "|" + String.valueOf(primaryDescription).hashCode();
            String state = verdict.allowed() ? "ALLOWED" : "BLOCKED";
            if (state.equals(LAST_RISK_VERDICT.put(key, state))) {
                return;   // 结论没变，不重复发
            }
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("companionId", uuid.toString());
            data.put("riskLevel", level.name());
            data.put("allowed", verdict.allowed());
            data.put("missing", verdict.missing());
            // 2026-10-31（166 B3）：只报 missing 时，无法分辨"配额太高"还是"键不可满足"。
            // 把要求键与实有数量一起带出（有界），下次拦停即可自证。
            try {
                data.put("observed", com.dwinovo.numen.rdd.policy.GateObservation.observe(
                        com.dwinovo.numen.rdd.policy.ResourceBudget.requiredFor(level), inventory, 16));
            } catch (RuntimeException ignore) {
                // 观测失败不影响裁决
            }
            data.put("prepTasks", verdict.prepTasks());
            data.put("primaryHint", String.valueOf(primaryDescription).length() > 120
                    ? String.valueOf(primaryDescription).substring(0, 120) : String.valueOf(primaryDescription));
            RddMonitor.publish(verdict.allowed() ? "risk_gate_opened" : "risk_gate_blocked", data);
        } catch (RuntimeException ignored) {
            // 观测失败不影响门本身的判定
        }
    }
}
