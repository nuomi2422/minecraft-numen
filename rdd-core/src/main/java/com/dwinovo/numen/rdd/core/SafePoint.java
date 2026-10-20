package com.dwinovo.numen.rdd.core;

/**
 * G3e：<b>安全点凭据</b>（IC75-r1 §15.8「结构变更必须落在安全点上」）。
 *
 * <p><b>它解决的那个洞</b>：`replaceCurrentSubtasks` / `expandCurrentPrimary` 是<b>整链结构变更</b>，
 * 但它们不看「此刻身体在干什么」——重规划可以在一个 {@code goto}/{@code mine} 正跑到一半时把整条链换掉，
 * 那个动作的 {@code task_finished} 回来时已经没有它的节点了。现有代码靠 {@code staleResultsIgnored}
 * 事后丢弃，那是<b>事后</b>而不是事前。本凭据把「现在能不能安全地改计划」变成一张可校验、一次性、
 * 会过期、换 owner 即作废的凭据。
 *
 * <p><b>三条边界（刻意定死）</b>：
 * <ol>
 *   <li><b>凭据只管新动作，不打断在跑动作</b>。冻结期拒绝 {@code beginAttempt}，
 *       但不会叫停在跑的那一件——最坏情况是「这件干完、等新计划」，不是「卡死」。</li>
 *   <li><b>一次性 + 会过期</b>：消费即失效，过期自动解冻（宁可放行旧计划，也不要永久冻住身体）。</li>
 *   <li><b>旧 epoch 一律作废</b>：换 owner（换人设/死亡复活重放）后旧凭据 {@code STALE_EPOCH}。</li>
 * </ol>
 *
 * <p><b>为什么不是接口而是 record</b>：签发时机（身体是否空闲）属于宿主，链只做校验与消费；
 * 把「签发条件」也塞进这里，迟早会出现第二套「身体是否空闲」的判据——那正是本项目反复踩的坑。
 */
public record SafePoint(String value, long ownerEpoch, Purpose purpose,
                        long issuedAtMillis, long expiresAtMillis) {

    /** 凭据用途：一种凭据只授权一种结构变更。 */
    public enum Purpose {
        REPLACE_PLAN,
        EXPAND_PRIMARY,
        PATCH_NODE
    }

    public static final String OK = "OK";
    public static final String NO_TOKEN = "NO_TOKEN";
    public static final String STALE_EPOCH = "STALE_EPOCH";
    public static final String WRONG_PURPOSE = "WRONG_PURPOSE";
    public static final String EXPIRED = "EXPIRED";
    public static final String ALREADY_CONSUMED = "ALREADY_CONSUMED";

    /** 校验结果：{@code ok=true} 时 {@code code=OK}。 */
    public record Check(boolean ok, String code) {
        public static Check pass() {
            return new Check(true, OK);
        }

        public static Check reject(String code) {
            return new Check(false, code);
        }
    }

    /** 默认 TTL：120 秒。足够「等这一件干完」，又不至于让规划器崩了就把身体永久冻住。 */
    public static final long DEFAULT_TTL_MILLIS = 120_000L;

    public SafePoint {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("safe point value required");
        if (purpose == null) throw new IllegalArgumentException("safe point purpose required");
        if (expiresAtMillis < issuedAtMillis) throw new IllegalArgumentException("safe point expires before issued");
    }

    public boolean expiredAt(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }

    public long remainingMillis(long nowMillis) {
        return Math.max(0L, expiresAtMillis - nowMillis);
    }
}
