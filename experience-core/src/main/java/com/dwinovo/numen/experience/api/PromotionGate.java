package com.dwinovo.numen.experience.api;

import java.util.List;

/**
 * LC1（78 号工作单 §3.1/§7）：<b>晋级/归因门</b> —— 一条 experience_verify 的回报，
 * 必须先证明「被回报的那版正文真的到过模型手里」。
 *
 * <h2>为什么要有这道门</h2>
 * 呈现（渲染进请求）会被预算裁掉、目录只给标题、旧凭证对应旧正文 ——
 * 三条路都能让模型「凭印象回报」，而回报直接推动 verifiedCount/maturity。
 * 没有交付证明的晋级 = 自我认证：谁也没看过的正文，靠一句 success=true 就变 VERIFIED。
 *
 * <h2>四个判定（全部拒绝时给「怎么补」）</h2>
 * <ul>
 *   <li>{@code OK} —— 本会话内该 ref 的<b>当前版本</b>正文有过完整交付（EXPANDED/AUTO_EXPANDED
 *       且未截断），归因与晋级放行；</li>
 *   <li>{@code RETRACTED} —— 条目已撤回（不可逆状态），不再接受验证归因；</li>
 *   <li>{@code NEVER_DELIVERED} —— 本会话没有完整交付记录。目录与自动注入块不可信
 *       （可能被预算裁掉），要补证就调 {@code experience_expand} / {@code experience_recall}；</li>
 *   <li>{@code CONTENT_DRIFT} —— 交付过、但那是<b>旧版本</b>；正文已变，旧验证不许跟着新内容走。</li>
 * </ul>
 *
 * <h2>为什么成功与失败回报一起拦</h2>
 * 78 号 §7 LC1 判据是「未展开引用<b>拒绝归因</b>」——不只是拒绝晋级。
 * 没收到正文的失败回报同样是假归因（模型可能在对着标题批评），
 * 假反例会污染 counterexamples 并触发降级。两边都拦，理由同源。
 *
 * <p><b>拒绝不倒写游戏事实</b>：任务本身的成败在任务链/五态账里如实保留，
 * 这里只拒绝「把它归到这条经验头上」这一步。</p>
 *
 * <p>纯函数、零 IO —— 门禁判据必须能离线钉死（与 TrialVerdict 同一纪律）。
 * 交付记录由调用方从 {@link ExposureLedger} 取，本类不持有状态。</p>
 */
public final class PromotionGate {

    /** 门禁判定。{@code deliveredVersions} 供审计与「你交付的是旧版」的确切证据。 */
    public record Decision(boolean allowed, String code, String message, List<String> deliveredVersions) {
        public Decision {
            code = code == null || code.isBlank() ? "OK" : code;
            message = message == null ? "" : message;
            deliveredVersions = deliveredVersions == null ? List.of() : List.copyOf(deliveredVersions);
        }
    }

    private static final Decision OK = new Decision(true, "OK", "", List.of());

    private PromotionGate() {}

    /**
     * @param entryRef              条目 ref（{@code exp:<id>}），只用于拒绝时指路
     * @param currentVersion        条目当前正文指纹（{@link ContentIdentity#fingerprint}）
     * @param retracted             条目是否已撤回
     * @param latestDeliveredVersion 本会话最近一次<b>完整</b>交付的版本；{@code ""} = 从未完整交付
     * @param everDeliveredVersions  本会话交付过的全部版本（审计/提示用）
     */
    public static Decision check(String entryRef, String currentVersion, boolean retracted,
                                 String latestDeliveredVersion, List<String> everDeliveredVersions) {
        String ref = entryRef == null ? "" : entryRef.trim();
        String cur = currentVersion == null ? "" : currentVersion.trim();
        String got = latestDeliveredVersion == null ? "" : latestDeliveredVersion.trim();
        List<String> seen = everDeliveredVersions == null ? List.of() : everDeliveredVersions;

        if (retracted) {
            return new Decision(false, "RETRACTED",
                    "这条经验已被撤回（不可逆状态），不再接受验证归因。要恢复只能显式 unretract。",
                    seen);
        }
        if (!cur.isEmpty() && cur.equals(got)) {
            return OK;
        }
        if (got.isEmpty()) {
            return new Decision(false, "NEVER_DELIVERED",
                    "本会话没有这条经验正文的完整交付记录（目录与自动注入块可能被预算裁掉，不算交付）。"
                            + "先调 experience_expand（ref=" + ref + "）或 experience_recall 确认正文，"
                            + "再回来回报采用结果。", seen);
        }
        return new Decision(false, "CONTENT_DRIFT",
                "你最近收到的是正文旧版本（" + got + "），库里当前是 " + cur
                        + " —— 条目正文在交付之后变了，旧验证不许跟着新内容走。"
                        + "请重新 experience_expand / experience_recall 确认新正文后再回报。"
                        + (seen.isEmpty() ? "" : " 本会话交付过的版本：" + seen), seen);
    }
}
