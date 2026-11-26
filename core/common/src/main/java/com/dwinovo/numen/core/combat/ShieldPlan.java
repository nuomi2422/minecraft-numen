package com.dwinovo.numen.core.combat;

import java.util.List;
import java.util.Objects;

/**
 * 自动举盾的纯策略：先保证副手 {@code useItem} 不互锁，再按当前威胁决定举、持、放。
 *
 * <p>这里不再按攻击冷却举盾。原版举盾五刻后才生效，未点火爬行者必须在点火线之外，
 * 按实际接近速度提前这五刻；已点火爆炸只允许维持已有格挡，撤退/走位仍由现有战斗路径负责。
 */
public final class ShieldPlan {

    /** 原版盾开始生效前的延迟（刻）。 */
    public static final int BLOCK_DELAY_TICKS = 5;

    public static final int NO_TARGET = -1;

    /** 这一刻拿盾做什么。 */
    public enum Decision {
        /** 没有可执行的举盾动作。 */
        PROCEED,
        /** 手上正用着别的东西（拉弓、吃东西），不打断。 */
        WAIT,
        /** 举起来。 */
        RAISE,
        /** 保持已有格挡。 */
        HOLD,
        /** 放下副手盾。 */
        RELEASE
    }

    /** 弓和盾抢同一个 {@code useItem}；切弓必须先放盾。 */
    public enum Mode { MELEE, BOW }

    /**
     * 一个真实威胁的纯快照。
     *
     * @param id         实体 id
     * @param distanceSq 自身到威胁的中心距离平方
     * @param radiusSq   举盾触发距离平方
     * @param canRaise   现在能否新举盾（已点火爆炸为 false）
     * @param canHold    已有盾时能否继续持有（爆炸仅作辅助格挡）
     */
    public record Threat(int id, double distanceSq, double radiusSq,
                         boolean canRaise, boolean canHold) {}

    public record Plan(Decision decision, int targetId) {}

    /** 原版 {@code travel} 读取的移动输入。 */
    public record MovementInput(float xxa, float zza) {}

    private ShieldPlan() {}

    /**
     * 只转视线、不转移动方向：把旧 yaw 下的输入换算到新 yaw 帧。
     *
     * <p>原版输入近似为 {@code worldX = xxa*cos(yaw) - zza*sin(yaw)}、
     * {@code worldZ = xxa*sin(yaw) + zza*cos(yaw)}；先还原世界方向，再按新 yaw 反算，
     * 因而举盾追脸不会把 nav 刚设置的撤退输入改成朝威胁前进。
     */
    public static MovementInput preserveWorldDirection(MovementInput input,
                                                         float oldYaw, float newYaw) {
        Objects.requireNonNull(input, "input");
        if (input.xxa() == 0.0f && input.zza() == 0.0f) return input;

        double oldRadians = Math.toRadians(oldYaw);
        double oldSin = Math.sin(oldRadians);
        double oldCos = Math.cos(oldRadians);
        double worldX = input.xxa() * oldCos - input.zza() * oldSin;
        double worldZ = input.xxa() * oldSin + input.zza() * oldCos;

        double newRadians = Math.toRadians(newYaw);
        double newSin = Math.sin(newRadians);
        double newCos = Math.cos(newRadians);
        return new MovementInput(
                (float) (worldX * newCos + worldZ * newSin),
                (float) (-worldX * newSin + worldZ * newCos));
    }

    /**
     * 把点火线提前五刻实际接近距离。例如实测接近 0.25 格/刻时，3 格点火线变成 4.25 格。
     * 不使用速度属性直接换算格/刻；调用方提供实测位移或当前相对速度。
     */
    public static double triggerRadius(double baseRadius, double approachPerTick) {
        double approach = Double.isFinite(approachPerTick) ? Math.max(0.0, approachPerTick) : 0.0;
        return baseRadius + approach * BLOCK_DELAY_TICKS;
    }

    public static Plan decide(Mode mode, boolean shieldUsable, boolean usingOtherItem,
                              boolean shieldRaised, List<Threat> threats, int preferredTargetId) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(threats, "threats");

        // 必须在换主手弓之前释放副手盾，否则 shootAt 会被 isUsingItem 永久挡住。
        if (mode == Mode.BOW) {
            if (shieldRaised) return new Plan(Decision.RELEASE, NO_TARGET);
            return new Plan(usingOtherItem ? Decision.WAIT : Decision.PROCEED, NO_TARGET);
        }
        if (usingOtherItem) {
            return new Plan(Decision.WAIT, NO_TARGET);
        }
        // 斧破盾后的冷却期间，已有盾也不再提供有效格挡，不能继续吃举盾减速。
        if (shieldRaised && !shieldUsable) {
            return new Plan(Decision.RELEASE, NO_TARGET);
        }

        int targetId = selectTarget(threats, shieldRaised, preferredTargetId);
        if (targetId == NO_TARGET) {
            return new Plan(shieldRaised ? Decision.RELEASE : Decision.PROCEED, NO_TARGET);
        }
        if (!shieldUsable) {
            return new Plan(Decision.PROCEED, NO_TARGET);
        }
        return new Plan(shieldRaised ? Decision.HOLD : Decision.RAISE, targetId);
    }

    /** 选“最快进入触发圈”的真实威胁；同 urgency 时优先当前攻击目标，再按 id 稳定。 */
    static int selectTarget(List<Threat> threats, boolean shieldRaised, int preferredTargetId) {
        int selected = NO_TARGET;
        double selectedUrgency = Double.POSITIVE_INFINITY;
        boolean selectedPreferred = false;
        for (Threat threat : threats) {
            if (!eligible(threat, shieldRaised)) continue;
            boolean preferred = threat.id() == preferredTargetId;
            double urgency = threat.distanceSq() / threat.radiusSq();
            boolean better = urgency < selectedUrgency
                    || (urgency == selectedUrgency
                        && (preferred && !selectedPreferred
                            || preferred == selectedPreferred && threat.id() < selected));
            if (better) {
                selected = threat.id();
                selectedUrgency = urgency;
                selectedPreferred = preferred;
            }
        }
        return selected;
    }

    private static boolean eligible(Threat threat, boolean shieldRaised) {
        if (threat == null || threat.id() == NO_TARGET
                || !Double.isFinite(threat.distanceSq()) || threat.distanceSq() < 0.0
                || !Double.isFinite(threat.radiusSq()) || threat.radiusSq() <= 0.0
                || threat.distanceSq() > threat.radiusSq()) {
            return false;
        }
        return shieldRaised ? threat.canHold() : threat.canRaise();
    }
}
