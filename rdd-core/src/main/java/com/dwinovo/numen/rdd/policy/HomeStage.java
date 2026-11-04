package com.dwinovo.numen.rdd.policy;

import java.util.List;
import java.util.Map;

/**
 * 基地阶段（167 §B3）——由**事实**算出来，不由模型手写、也不落新账本。
 *
 * <p>顺序即门槛：每一档都要求前一档成立。{@code ESTABLISHED} 只表示"有恢复能力"，
 * 不表示可以放行高风险动作（那是 {@code RiskGate} 的事，见 167 §B6）。
 *
 * <p>{@code UNKNOWN} 与 {@code 0} 严格区分：没观测到不等于没有。
 */
public enum HomeStage {
    /** 没有任何基地引用。 */
    NO_HOME,
    /** 只观测到村庄位置，还没建/没登记住所。 */
    CANDIDATE,
    /** 核心屋已登记且结构可用。 */
    SHELTERED,
    /** 床已实际绑定（不只是"看见床方块"）。 */
    RESPAWN_BOUND,
    /** 备用箱最近扫描命中最低备用清单。 */
    STOCKED,
    /** 光照/保护判据通过。 */
    LIT_PROTECTED,
    /** 三件齐：绑床 + 备用 + 光照保护。 */
    ESTABLISHED;

    /**
     * @param baseKnown      是否有基地引用（世界观测里的 world_base）
     * @param houseUsable    核心屋结构验收是否可用
     * @param respawnBound   床是否**实际绑定**
     * @param reserveKnown   备用箱最近扫描是否**新鲜且可读**
     * @param reserveSufficient 备用是否达到最低清单
     * @param litProtected   光照/保护是否通过
     */
    /**
     * 备用是否达标 —— 直接按 {@link ReserveKit#MINIMUM} 判，不再由调用方自己拍"够不够"。
     * {@code null}（未知）不算达标。
     */
    public static boolean reserveSufficient(Map<String, Integer> reserve) {
        return ReserveKit.sufficient(reserve);
    }

    /**
     * TC-RESERVE-01（2026-11-04）：直接吃备用投影，known 与 sufficient 由它自己给。
     *
     * <p>为什么不保留"调方自己拼那两个 boolean"的写法：拼的时候最容易漏掉新鲜度——
     * 过期但足量的库存就是从这里滑进 {@code STOCKED} 的。
     */
    public static HomeStage of(boolean baseKnown, boolean houseUsable, boolean respawnBound,
                               HomeReserve reserve, boolean litProtected) {
        HomeReserve r = reserve == null ? HomeReserve.unobserved() : reserve;
        return of(baseKnown, houseUsable, respawnBound, r.known(), r.sufficient(), litProtected);
    }

    public static HomeStage of(boolean baseKnown, boolean houseUsable, boolean respawnBound,
                               boolean reserveKnown, boolean reserveSufficient, boolean litProtected) {
        if (!baseKnown) return NO_HOME;
        if (houseUsable && respawnBound && reserveKnown && reserveSufficient && litProtected) {
            return ESTABLISHED;
        }
        if (litProtected && reserveKnown && reserveSufficient && respawnBound) return LIT_PROTECTED;
        if (reserveKnown && reserveSufficient) return STOCKED;
        if (respawnBound) return RESPAWN_BOUND;
        if (houseUsable) return SHELTERED;
        return CANDIDATE;
    }

    /** 这一档是否已经具备"死一次能再来"的最低条件。 */
    public boolean hasRecovery() {
        return this == STOCKED || this == LIT_PROTECTED || this == ESTABLISHED;
    }

    /**
     * 供上下文渲染的一行摘要；{@code null} 事实一律写 UNKNOWN，不写 0。
     *
     * <p>「观测到了但里面没有关键物品」和「根本没观测到」必须是两句话：
     * 前者说明箱子在、能读、但没备货（该去补）；后者说明读数不可信（该去扫）。
     */
    public static String describe(Map<String, Integer> reserve, boolean reserveKnown) {
        if (!reserveKnown) return "reserve=UNKNOWN";
        if (reserve == null || reserve.isEmpty()) return "reserve=KNOWN but no critical items";
        List<String> shortfall = ReserveKit.shortfall(reserve);
        String head = shortfall.isEmpty() ? "reserve=OK" : "reserve=SHORT";
        String tail = shortfall.isEmpty() ? "" : " missing=" + String.join(",", shortfall);
        StringBuilder sb = new StringBuilder(head).append(tail).append(" have:");
        reserve.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(6)
                .forEach(e -> sb.append(' ').append(e.getKey()).append(':').append(e.getValue()));
        return sb.toString();
    }
}
