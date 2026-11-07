package com.dwinovo.numen.acx.api;

/**
 * AC 版本的生命周期（用户架构原话：{@code generated → 待验证/待使用 → 上线 / 删或重写}）。
 *
 * <pre>
 *   GENERATED ──静态校验通过──→ STABLE（自动晋级，verifiedBy=machine:auto；唯一可作为生产 active）
 *       │                        │                     │
 *       └──────────人工否决──────┴─────────────────────┴──→ REJECTED ──reopen──→ GENERATED
 * </pre>
 *
 * <p>与旧 AC 的关键差别：旧 {@code FileAcVersionStore.latest(name)} 取版本号最大的直接生效，
 * 没有状态可查；这里每个版本各记各的状态，{@code STABLE} 才是生产可用的唯一状态。</p>
 */
public enum AcxLifecycleState {

    /** 刚发布：作者（游戏内 AI / 执行 AI）写出来的原样，还没跑过。 */
    GENERATED,

    /** 兼容保留：历史版本可能有此状态；自动晋级不再经过它。 */
    PENDING,

    /** 校验通过、可 active（2026-11-07 起静态校验通过即自动到达）。 */
    STABLE,

    /** 不可用：坏剧本 / 反复失败 / 被否决。保留在库里可回查，但绝不 active。 */
    REJECTED;

    /** 只有 STABLE 允许作为生产 active。 */
    public boolean isActive() {
        return this == STABLE;
    }

    public boolean isRejected() {
        return this == REJECTED;
    }

    /** 状态迁移白名单。同状态不算迁移。 */
    public boolean canTransitionTo(AcxLifecycleState next) {
        if (next == null || next == this) {
            return false;
        }
        return switch (this) {
            case GENERATED -> next == PENDING || next == STABLE || next == REJECTED;
            case PENDING -> next == STABLE || next == REJECTED || next == GENERATED;
            case STABLE -> next == REJECTED;
            case REJECTED -> next == GENERATED;
        };
    }
}
