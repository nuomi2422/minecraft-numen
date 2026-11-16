package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.carrier.CarrierAlarms;
import com.dwinovo.numen.api.carrier.CarrierChain;
import java.util.List;
import java.util.function.IntSupplier;

/** Text projection of existing carrier results; no world reads, rules, caches or wake ownership. */
final class RddCarryProjection {
    private RddCarryProjection() {}
    private static final int MAX_ITEMS = 4;
    private static final long STALE_MS = 10_000L;
    /** 一次渲染的产物：注入文本 + 本次命中的独立闹钟（供边沿埋点）。 */
    record Rendered(String xml, List<CarrierAlarms.Hit> alarms) {
        /**
         * 语义指纹：**携带项 + 闹钟规则**，<b>不含</b> {@code <basis>} 里的精确血量。
         *
         * <p>为什么需要它（2026-10-21 实机）：`REFRESH_MS=2000`，`<basis>` 里带着 `血量=有(19)`
         * 这种会随回血变化的数字，于是「内容变了」每 2 秒都成立 → 日志 7 分钟 127 条、约 4 万行/天，
         * 把当轮真正要看的事件（预算拦截/判定/复盘）全埋了。
         * 注入内容一字不动，只是**日志按语义变化打**：血量从 19 变 20 不是新闻，携带项/闹钟变了才是。
         */
        String fingerprint() {
            StringBuilder sb = new StringBuilder();
            int start = xml.indexOf("<item>");
            int end = xml.indexOf("</carry>");
            if (start >= 0) sb.append(xml, start, end < 0 ? xml.length() : end);
            for (CarrierAlarms.Hit h : alarms) {
                sb.append('|').append(h.rule()).append('@').append(h.version());
            }
            return sb.toString();
        }
    }

    static Rendered render(CarrierChain.Facts facts, CarrierChain.Result r, List<CarrierAlarms.Hit> alarms, IntSupplier ruleCount) {
        if (r.carry().isEmpty() && alarms.isEmpty()) {
            return new Rendered("", alarms);
        }
        StringBuilder sb = new StringBuilder();
        if (!r.carry().isEmpty()) {
            sb.append("<carry>");
            sb.append("<basis>").append(esc(shorten(basisOf(facts, r), 200))).append("</basis>");
            if (r.shortCircuited()) {
                // ★ 短路信息必须让执行 AI 看到 —— 不说的话它会以为全部级都判过了
                sb.append("<basis_note>").append(esc(basisNote(r, ruleCount))).append("</basis_note>");
            }
            int n = 0;
            for (String item : r.carry()) {
                if (n++ >= MAX_ITEMS) {
                    break;
                }
                sb.append("<item>").append(esc(item)).append("</item>");
            }
            sb.append("<note>这些是建议，不是指令。一般不要仅为携带建议偏离当前任务；当前可信生存风险变化时，按现场威胁和身体状态重新评估非生存动作。未知或过期提示不作为自动抢占依据。</note>");
            sb.append("</carry>");
        }
        if (!alarms.isEmpty()) {
            sb.append("<alarms scope=\"nearby16\">");
            for (CarrierAlarms.Hit h : alarms) {
                // A4（96 号）：每条提醒带采样时刻/年龄/来源/覆盖；过期显式标 stale，不冒充当前。
                long ageMs = facts.capturedAtMs() > 0 ? Math.max(0, System.currentTimeMillis() - facts.capturedAtMs()) : -1;
                boolean stale = ageMs > STALE_MS;
                sb.append("<alarm rule=\"").append(esc(h.rule())).append("\" v=\"").append(h.version())
                        .append("\" pri=\"").append(esc(h.prio()))
                        .append("\" asOf=\"").append(esc(facts.capturedAtMs() > 0
                                ? java.time.Instant.ofEpochMilli(facts.capturedAtMs()).toString() : "unknown"))
                        .append("\" age=\"").append(ageMs < 0 ? "unknown" : (ageMs / 1000) + "s")
                        .append("\" source=\"").append(esc(facts.source()))
                        .append("\" coverage=\"").append(esc(facts.coverage())).append("\"")
                        .append(stale ? " stale=\"true\"" : "")
                        .append(" facts=\"")
                        .append(esc(shorten(h.facts(), 120))).append("\">")
                        .append(esc(shorten(h.advice(), 200))).append("</alarm>");
            }
            sb.append(RddAlarmResponseText.note(alarms, facts, System.currentTimeMillis()));
            sb.append("</alarms>");
        }
        return new Rendered(sb.toString(), alarms);
    }

    private static String basisOf(CarrierChain.Facts f, CarrierChain.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("指向=").append(f.hostileNearby() ? "HOSTILE_NEARBY" : (f.passiveNearby() ? "PASSIVE_ONLY" : "NONE"));
        sb.append(" 护甲=").append(f.hasRealArmor() ? "有" : "无");
        sb.append(" 武器=").append(f.hasRealWeapon() ? "有" : "无");
        sb.append(" 血量=").append(f.hpBand()).append(f.hasHp() ? ("(" + f.hp() + ")") : "");
        return sb.toString();
    }

    private static String basisNote(CarrierChain.Result r, IntSupplier ruleCount) {
        int notEvaluated = Math.max(0, ruleCount.getAsInt() - r.stoppedAt() - 1);
        return "第 " + (r.stoppedAt() + 1) + " 级不成立，后续 " + notEvaluated + " 项未求值";
    }

    private static String shorten(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
