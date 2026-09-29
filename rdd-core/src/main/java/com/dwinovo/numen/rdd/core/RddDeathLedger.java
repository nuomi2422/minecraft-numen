package com.dwinovo.numen.rdd.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 死亡台账：<b>每一次死亡单独一行，带掉落物的到期时刻</b>。
 *
 * <h2>为什么要有（2026-09-29 实机）</h2>
 * 旧实现只在死亡瞬间发一条 {@code companion_assets_invalidated}{count}，
 * 于是产生两个真 bug：
 * <ol>
 *   <li><b>它以为东西已经没了</b>：死亡后 5 分钟内掉落物<b>还在地上</b>，
 *       但资产已被标失效，没有任何人告诉它"还剩 200 秒，快去捡" →
 *       它按"东西没了"重新规划。</li>
 *   <li><b>连死两次就分不清了</b>：实机 15:50:53 死在 (200,51,-130)，
 *       15:51:09 又死在 (134,31,-96)，相隔 16 秒。两个掉落点的到期时刻不同，
 *       旧实现只留"最后一条"，于是"哪一次的掉落已经过期"根本无法回答。</li>
 * </ol>
 *
 * <h2>时间基准</h2>
 * 掉落物 despawn 按<b>游戏时间</b>，不是墙钟 —— 退出重进世界不加载时游戏时间不前进，
 * 掉落物也就不会消失。本类因此同时记 {@code gameTime}（用于到期判定，与原版同口径）
 * 和 {@code wallClockMillis}（仅供人看/排序）。到期判定只认 gameTime。
 *
 * <h2>有界</h2>
 * 每个同伴最多留 {@link #MAX_ENTRIES} 条（默认 8），按 gameTime 淘汰最老的。
 * 理由：连续死亡是常态，无界增长会让监测台 payload 膨胀到没法看。
 */
public final class RddDeathLedger {

    /**
     * 原版掉落物 despawn 时长：<b>5 分钟</b>。
     *
     * <p>必须是 {@code 20 tick/s * 60 s * 5 min = 6000}。第一版误写成
     * {@code 20 * 60}（=1200 tick = 1 分钟），单测直接量出 "还剩 60 秒" 才发现 ——
     * 窗口缩水到 1/5，她会在还有 4 分钟的时候就以为东西没了。
     */
    public static final long DROPS_LIVE_TICKS = 20L * 60L * 5L;

    /** 每个同伴保留的死亡记录条数上限（连续死亡是常态，无界会让 payload 膨胀）。 */
    public static final int MAX_ENTRIES = 8;

    /** 一次死亡的证据状态（2026-09-30 深审 R07）。 */
    public enum State {
        /** 只有时钟估计，没有真实证据 —— 默认落在这一档。 */
        UNVERIFIED,
        /** 已确认丢失：扫描确认不在 / 被销毁 / 已被墓碑模组收殓。 */
        CONFIRMED_LOST,
        /** 已确认捡回：掉落物还在而且已经拿回背包。 */
        RECOVERED
    }

    /**
     * 一次死亡。字段全 final：记录一经写下就不该被"修正"。
     *
     * <p>{@code state} 是 2026-09-30 深审 R07 加的：<b>「超时」不等于「没了」</b>。
     * 原版只在掉落物所在区块被 tick 时给它计龄，所以"过了 5 分钟"完全可能只是
     * "那边没加载"。没有这一档区分，{@link #render} 就只能说谎。
     */
    public record Death(int seq, long gameTime, long wallClockMillis,
                        String deathAt, int lostEntries,
                        State state, String evidence) {

        public Death(int seq, long gameTime, long wallClockMillis,
                     String deathAt, int lostEntries) {
            this(seq, gameTime, wallClockMillis, deathAt, lostEntries, State.UNVERIFIED, "");
        }

        /** 这一次的掉落物在 {@code nowGameTime} 时是否<b>可能</b>还在地上（纯估计）。 */
        public boolean stillRecoverable(long nowGameTime) {
            return state == State.UNVERIFIED && nowGameTime - gameTime < DROPS_LIVE_TICKS;
        }

        /** 距离<b>估计</b> despawn 还有多少 tick（已过期返回 0）。 */
        public long ticksLeft(long nowGameTime) {
            long left = DROPS_LIVE_TICKS - (nowGameTime - gameTime);
            return Math.max(0, left);
        }

        public long secondsLeft(long nowGameTime) {
            return ticksLeft(nowGameTime) / 20L;
        }

        public Death confirmedLost(String reason) {
            return new Death(seq, gameTime, wallClockMillis, deathAt, lostEntries,
                    State.CONFIRMED_LOST, reason);
        }

        public Death recovered(String reason) {
            return new Death(seq, gameTime, wallClockMillis, deathAt, lostEntries,
                    State.RECOVERED, reason);
        }
    }

    private static final Map<UUID, List<Death>> LEDGER = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SEQ = new ConcurrentHashMap<>();

    private RddDeathLedger() {}

    /**
     * 记一次死亡，返回新写入的那条。
     *
     * <p>{@code gameTime} 用 RddInstrumentation 的游戏时间（与 despawn 同口径）；
     * 传负数表示"读不到游戏时间"，此时本条不参与到期判定（{@code ticksLeft} 恒为满窗），
     * 免得用墙钟去猜一个和原版不同口径的时钟。
     */
    public static Death record(UUID companionId, long gameTime, long wallClockMillis,
                               String deathAt, int lostEntries) {
        UUID id = companionId == null ? new UUID(0, 0) : companionId;
        int seq = SEQ.merge(id, 1, Integer::sum);
        Death d = new Death(seq, gameTime, wallClockMillis,
                deathAt == null ? "?" : deathAt, Math.max(0, lostEntries));
        List<Death> list = LEDGER.computeIfAbsent(id, k -> new ArrayList<>());
        synchronized (list) {
            list.add(d);
            while (list.size() > MAX_ENTRIES) {
                list.remove(0);
            }
        }
        return d;
    }

    /**
     * 记下「这一次死亡的东西**确实**没了」的证据（2026-09-30 深审 R07）。
     *
     * <p>没有这个方法，系统只能靠时钟猜——而时钟猜不出来（原版只在区块被 tick 时给掉落物计龄）。
     * 宿主拿到<b>真实证据</b>时（世界扫描确认不在 / 已拾回 / 被火岩浆销毁 / 墓碑模组已收殓）调它，
     * 规划器才被允许说"永久丢失"。
     *
     * @param seq   {@link Death#seq()}；传负数 = 标记最近那一次
     * @param reason 证据来源（人话，会进事件与规划上下文）
     */
    public static boolean confirmLost(UUID companionId, int seq, String reason) {
        List<Death> list = LEDGER.get(companionId);
        if (list == null || list.isEmpty()) return false;
        synchronized (list) {
            Death target = null;
            for (Death d : list) {
                if (seq < 0 || d.seq() == seq) { target = d; break; }
            }
            if (target == null) return false;
            list.remove(target);
            list.add(target.confirmedLost(reason == null || reason.isBlank() ? "confirmed gone" : reason));
            return true;
        }
    }

    /**
     * 记下「这一次死亡的东西<b>已经捡回来了</b>」。
     *
     * <p>与 {@link #confirmLost} 分开是因为语义相反：一个是"没了"，
     * 一个是"还在而且已经拿回来了"——后者应该让规划器**去掉**这个回收目标。
     */
    public static boolean confirmRecovered(UUID companionId, int seq, String reason) {
        List<Death> list = LEDGER.get(companionId);
        if (list == null || list.isEmpty()) return false;
        synchronized (list) {
            Death target = null;
            for (Death d : list) {
                if (seq < 0 || d.seq() == seq) { target = d; break; }
            }
            if (target == null) return false;
            list.remove(target);
            list.add(target.recovered(reason == null || reason.isBlank() ? "picked up" : reason));
            return true;
        }
    }

    /** 已确认丢失的那些（真正"没了"的）。 */
    public static List<Death> confirmedLost(UUID companionId) {
        List<Death> out = new ArrayList<>();
        for (Death d : all(companionId)) {
            if (d.state() == State.CONFIRMED_LOST) out.add(d);
        }
        return List.copyOf(out);
    }

    /** 已确认捡回的那些。 */
    public static List<Death> recovered(UUID companionId) {
        List<Death> out = new ArrayList<>();
        for (Death d : all(companionId)) {
            if (d.state() == State.RECOVERED) out.add(d);
        }
        return List.copyOf(out);
    }

    /** 全部记录，最新在前。 */
    public static List<Death> all(UUID companionId) {
        List<Death> list = LEDGER.get(companionId);
        if (list == null) return List.of();
        synchronized (list) {
            List<Death> copy = new ArrayList<>(list);
            copy.sort(Comparator.comparingLong(Death::gameTime).reversed());
            return List.copyOf(copy);
        }
    }

    /** 掉落物<b>可能</b>还在地上的那些（按到期时刻从早到晚排：先捡最先过期的）。 */
    public static List<Death> recoverable(UUID companionId, long nowGameTime) {
        List<Death> out = new ArrayList<>();
        for (Death d : all(companionId)) {
            if (d.stillRecoverable(nowGameTime)) out.add(d);
        }
        out.sort(Comparator.comparingLong(Death::gameTime));
        return List.copyOf(out);
    }

    /**
     * 超过拾取窗口的（<b>不等于"没了"</b>，只是"按估计过期了"）。
     *
     * <p>深审 R07：这个方法名容易被读成"已丢失"，所以语义收紧为
     * "过期且未确认"。真正丢失的看 {@link #confirmedLost}。
     */
    public static List<Death> expired(UUID companionId, long nowGameTime) {
        List<Death> out = new ArrayList<>();
        for (Death d : all(companionId)) {
            if (d.state() == State.UNVERIFIED && !d.stillRecoverable(nowGameTime)) out.add(d);
        }
        return List.copyOf(out);
    }

    /** 最近一条；没有则 null。 */
    public static Death latest(UUID companionId) {
        List<Death> a = all(companionId);
        return a.isEmpty() ? null : a.get(0);
    }

    /** 死亡次数（台账内计数）。 */
    public static int deathsRecorded(UUID companionId) {
        return all(companionId).size();
    }

    /**
     * 渲染成一句话交给规划器/执行层。
     *
     * <p><b>2026-09-30 深审 R07（codex 判定必须进第一批红线）：区分「估计」与「确认」。</b>
     *
     * <p>旧实现只要"距死亡超过 5 分钟"就断言 {@code ALL ... have already despawned}、
     * {@code treat those items as permanently gone}。但原版掉落物的年龄只在<b>它自己所在的
     * 区块被 tick</b> 时增长：玩家跑远、区块卸载、世界时间照样走，掉落物其实还在原处。
     * 按时钟断言"永久丢失"会让士兵**白跑一趟甚至放弃本来还在的东西**，
     * 而这正踩在死亡恢复红线上。
     *
     * <p>现在分三档：
     * <ul>
     *   <li><b>还在窗口内</b> → 「去拿，还剩约 N 秒」（估计，可信度最高）</li>
     *   <li><b>超窗但未确认</b> → 「可能已消失，也可能只是那边没加载；
     *       顺路经过就捡，别专门跑」——<b>不再断言丢失</b></li>
     *   <li><b>确认丢失</b> → 只有拿到真实证据（世界扫描找不到 / 已拾回 / 被销毁）才这么说</li>
     * </ul>
     */
    public static String render(UUID companionId, long nowGameTime) {
        List<Death> a = all(companionId);
        if (a.isEmpty()) {
            return "no death recorded — nothing to recover";
        }
        List<Death> live = recoverable(companionId, nowGameTime);
        StringBuilder sb = new StringBuilder();
        if (live.isEmpty()) {
            List<Death> confirmed = confirmedLost(companionId);
            if (confirmed.isEmpty()) {
                sb.append(a.size()).append(" death record(s), all past the ~").append(DROPS_LIVE_TICKS / 20)
                  .append("s pickup window. That does NOT prove the items are gone: vanilla only ages")
                  .append(" drops in chunks that are still loaded, so they may be sitting there")
                  .append(" untouched while the area is unloaded. If you happen to pass that way,")
                  .append(" look for them; do not make a special trip, and do not count them as lost yet.");
                return sb.toString();
            }
            sb.append(confirmed.size()).append(" of ").append(a.size())
              .append(" death drop(s) are CONFIRMED gone (scanned and absent, picked up, or destroyed).")
              .append(" Treat only these as permanently gone and plan without them.");
            return sb.toString();
        }
        sb.append(live.size()).append(" death drop(s) still within the pickup window (of ")
          .append(a.size()).append(" recorded):");
        for (Death d : live) {
            sb.append("\n  #").append(d.seq()).append(' ').append(d.deathAt())
              .append(" — ").append(d.lostEntries()).append(" items, est. despawns in ")
              .append(d.secondsLeft(nowGameTime)).append('s');
        }
        if (live.size() > 1) {
            sb.append("\nGo to the NEAREST one first — the later ones have more time left.");
        }
        return sb.toString();
    }
    /** 清掉某同伴的台账（任务重置/世界重载时用，免得把上一局的死算进这一局）。 */
    public static void clear(UUID companionId) {
        LEDGER.remove(companionId);
        SEQ.remove(companionId);
    }

    public static void clearAll() {
        LEDGER.clear();
        SEQ.clear();
    }
}
