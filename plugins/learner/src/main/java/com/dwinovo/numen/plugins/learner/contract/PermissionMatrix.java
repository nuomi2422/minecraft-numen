package com.dwinovo.numen.plugins.learner.contract;

import java.util.ArrayList;
import java.util.List;

/**
 * 产物类型 × Learner 模式的<strong>权限矩阵</strong>（158 §7、159 F）。
 *
 * <h2>为什么必须写死在代码/配置里，而不是写进提示词</h2>
 * 158 §7 原文：「产物类型 × Learner 模式的权限必须在模组配置中硬编码」。
 * 原因很直接：提示词是**建议**，模型可以（也会）不遵守；
 * 而"内部学习者不许直接把自动动作推到生产"是一条安全边界，
 * 它不能依赖模型自觉。161 §9 也把「质量门、权限门只写在 prompt 里」列为停工条件。
 *
 * <h2>三档而不是两档</h2>
 * <ul>
 *   <li>{@link Ruling#ALLOW} —— 可提交，走质量门；</li>
 *   <li>{@link Ruling#TRIAL_ONLY} —— 只准进试用态（158 的「禁止或仅 TRIAL」）；
 *       它能被检索到但只在该试用的范围里可见；</li>
 *   <li>{@link Ruling#DENY} —— 直接拒绝。</li>
 * </ul>
 * 把"仅 TRIAL"折成"禁止"会让内部学习者永远无法贡献自动动作草稿（连参考都不行）；
 * 折成"允许"则等于把安全边界抹掉。两折都不对。
 *
 * <h2>内部学习者为什么对自动动作/ACX 只到 TRIAL</h2>
 * 158 §1 原文：内部版归因质量天然可能低于外部版，这是<b>能力边界</b>而不是异常。
 * 所以给它的是"可以提，但不能自己说了算"。
 *
 * <p>纯 JVM、无 IO，可离线单测。
 */
public final class PermissionMatrix {

    /** 判定档位。 */
    public enum Ruling {
        ALLOW,
        TRIAL_ONLY,
        DENY
    }

    /**
     * 一条判定。
     *
     * @param code 稳定代码（`trial_only:AUTO_ACTION` / `denied:ACX_REQUEST`），给统计用
     */
    public record Decision(Ruling ruling, String code, String reason) {
        public Decision {
            code = code == null ? "" : code;
            reason = reason == null ? "" : reason;
        }

        public boolean allowed() {
            return ruling != Ruling.DENY;
        }
    }

    private PermissionMatrix() {
    }

    /**
     * 查矩阵。
     *
     * @param mode 提交者（内部/外部）
     * @param kind 产物类型
     */
    public static Decision rulingFor(Submission.Mode mode, ArtifactKind kind) {
        if (mode == null || kind == null) {
            return new Decision(Ruling.DENY, "denied:unknown", "模式或产物类型缺失");
        }
        return switch (mode) {
            case INTERNAL -> switch (kind) {
                // 158 §7：内部学习者的自动动作与 ACX/编译请求「禁止或仅 TRIAL」。
                // 选 TRIAL：完全禁止等于连参考都不给，而它的材料本来就更少。
                case AUTO_ACTION -> new Decision(Ruling.TRIAL_ONLY, "trial_only:AUTO_ACTION",
                        "内部学习者的自动动作只能进试用态，不得直接生产生效");
                case ACX_REQUEST -> new Decision(Ruling.TRIAL_ONLY, "trial_only:ACX_REQUEST",
                        "内部学习者的 ACX/编译请求只能进试用态");
                // 158 §7 表里没有单列，但 SELF_COMPILE 归「编译请求」一类。
                case CAPABILITY_REQUEST -> new Decision(Ruling.TRIAL_ONLY, "trial_only:CAPABILITY_REQUEST",
                        "能力/编译类请求按编译请求处理：内部只到试用态");
                default -> new Decision(Ruling.ALLOW, "", "内部学习者可提交（仍走质量门）");
            };
            case EXTERNAL -> switch (kind) {
                // 158 §7：外部「受质量门和授权限制」—— 矩阵层放行，限制交给质量门与授权。
                case AUTO_ACTION, ACX_REQUEST -> new Decision(Ruling.ALLOW, "gated:EXTERNAL_" + kind.name(),
                        "外部学习者可提交，但受质量门与授权限制（矩阵层不代替它们）");
                default -> new Decision(Ruling.ALLOW, "", "外部学习者可提交（仍走质量门）");
            };
        };
    }

    /**
     * 批量检查。
     *
     * @return 与入参等长的判定列表（顺序一致，便于按 `artifact#i` 定位）
     */
    public static List<Decision> check(Submission.Mode mode, List<ArtifactDraft> artifacts) {
        List<Decision> out = new ArrayList<>();
        if (artifacts == null) {
            return out;
        }
        for (ArtifactDraft a : artifacts) {
            out.add(rulingFor(mode, a == null ? null : a.kind()));
        }
        return out;
    }
}
