package com.dwinovo.numen.plugins.learner.core;

import java.util.Map;

/**
 * 产物投放口（{@code 38} v3 B16）。
 *
 * <p><b>用户 2026-10-01 原话</b>：「学习者你要留个接口，就是他可以自己写 ac，
 * 是我们这一波的话，<b>不包含 ac，暂时不要包含，降低复杂度</b>，你要要留个接口，懂我意思吧？」
 *
 * <p>所以本接口<b>只声明、不实现</b>：留它是为了以后加时不动结构，<b>不是</b>现在就写 AC。
 *
 * <p><b>⚠️ 反「假红线」的硬要求</b>：接口在、永远没人实现 = 门禁形同虚设
 * （这是 GPT-6 审稿点名过的风险类型）。因此配套的 {@link UnsupportedArtifactSink}
 * <b>必须显式抛错，不许静默 no-op</b> —— 「调了却没实现」要变成一眼看得见的失败。
 */
public interface ArtifactSink {

    /** 投放一条经验（第 2 批真正接通的那一步）。 */
    void publishExperience(String experienceId, Map<String, Object> experience);

    /**
     * 投递一份 AC 脚本。
     *
     * @param scriptName 脚本名
     * @param scriptBody 脚本体
     * @return 落地后的可引用标识
     */
    String publishAcScript(String scriptName, String scriptBody);

    /** 投放一份携带器内容。 */
    void publishCarrier(String carrierName, String content);

    /** 这个 sink 现在能不能用（第 1 批恒为 false）。 */
    boolean available();
}