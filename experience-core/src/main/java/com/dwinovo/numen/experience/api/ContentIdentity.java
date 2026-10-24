package com.dwinovo.numen.experience.api;

import java.util.List;

/**
 * LC1（78 号工作单 §3）：经验<b>正文</b>的内容指纹 —— 「哪个版本被验证过」的版本号。
 *
 * <p><b>为什么必须有它</b>：stableKey 只看 type+title，于是「同一条经验」更新正文时
 * id 不变，而 {@code verifiedCount / maturity} 是正文无关的计数 —— 旧正文验证十次、
 * 新正文一 learn 进来就继承十次可信，学习线从此越攒越不可靠（78 号 §2.2 点名的缺口）。
 * 内容指纹把「验证过的是哪一版正文」变成可对账的字符串：展开凭证、采用样本、
 * 晋级校验都引用它，跨版本引用一律拒绝归因。
 *
 * <h2>覆盖哪些字段（正文命题），刻意不覆盖哪些（元数据）</h2>
 * <ul>
 *   <li><b>覆盖</b>：description / rationale / rootCause / recommendedResponse
 *       + 七字段槽位 + triggerStrings + toolNames —— 这些变了 = 命题变了。</li>
 *   <li><b>不覆盖</b>：title（身份归 stableKey 管，标题变是合并路径的事）、
 *       tags / priority（检索辅助与排序权重，不是命题内容）、
 *       maturity / verifiedCount / 各时间戳 / retracted / supersedes / publication
 *       （全是对这条经验的<b>评价</b>，评价变化不该换版本号，否则记录评价本身就改版本）。</li>
 * </ul>
 *
 * <h2>为什么是运行时计算而不是落盘字段</h2>
 * {@code ExperienceEntry.toJson} 是跨插件契约（learner 侧 ExperienceDraft.ENTRY_KEYS
 * 钉着键数，加一键三处同步，见 recordEvidence 成功侧 note 的注释）。
 * 指纹从不可变正文现算，永远与正文自洽，不存在「存的指纹和正文对不上」这类二阶病。
 *
 * <p><b>稳定性</b>：与 {@code ExperienceStoreRetrievalBridge} 的 contentHash
 * 同一风格（{@code "h"+十六进制}），但覆盖字段不同（桥的 body hash 只覆盖
 * mechanism+recommendedResponse 截断 400 字的<b>展示正文</b>，这里覆盖完整命题）。
 * 两个 hash 服务两个问题，别混用。</p>
 */
public final class ContentIdentity {

    private ContentIdentity() {}

    /**
     * 正文指纹。null 与空串同形（一个没填的字段不产生版本差异），
     * 字段间用 {@code \u0001} 分隔（正文字段里出现过的字符不与之冲突）。
     */
    public static String fingerprint(ExperienceEntry e) {
        if (e == null) {
            return "c0";
        }
        StringBuilder sb = new StringBuilder(256);
        section(sb, e.description());
        section(sb, e.rationale());
        section(sb, e.rootCause());
        section(sb, e.recommendedResponse());
        section(sb, e.mechanism());
        section(sb, e.preconditions());
        section(sb, e.failureConditions());
        section(sb, e.observableSignal());
        section(sb, e.derivation());
        section(sb, e.efficiency());
        section(sb, e.evidence());
        section(sb, e.triggerStrings());
        section(sb, e.toolNames());
        return "c" + Integer.toHexString(sb.toString().hashCode());
    }

    private static void section(StringBuilder sb, String v) {
        sb.append(v == null ? "" : v.trim()).append('\u0001');
    }

    private static void section(StringBuilder sb, List<String> v) {
        if (v == null || v.isEmpty()) {
            sb.append('\u0001');
            return;
        }
        for (String s : v) {
            sb.append(s == null ? "" : s.trim()).append('\u0002');
        }
        sb.append('\u0001');
    }
}
