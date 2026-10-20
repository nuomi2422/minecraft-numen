package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.context.ContextBudget;
import com.dwinovo.numen.ai.AiLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 经济调控（E1）：按角色（执行/规划/学习）的滑动窗口预算策略。
 *
 * <p><b>两种模式</b>：{@code shadow}（默认）只发 {@code budget_advice} 建议事件，
 * 绝不拦截任何调用；{@code enforce} 在角色限额用尽时<b>拦截该角色的新调用</b>——
 * 拦截由调用点执行（{@link #checkBlock} 是纯求值，调用点据此决定：
 * 执行=不开下一轮并给出明确状态、规划=回落占位链、学习=推迟复盘并恢复队列），
 * 拦截通知走 {@link #notifyBlock} 限频，绝无静默停。0 = 不限的角色永不拦截。
 *
 * <p><b>安全边界</b>：拦截只管「要不要再发新调用」；一轮收尾的判定调用（goal_judging）
 * 与已发出的重试不拦——最后一轮成果必须判完（沿用引擎既有原则）。学习预算只拦学习调用，
 * 不影响执行/生存路径；确定性生存反射（不等远程 LLM）属 E2，不在此机制内。
 *
 * <p>配置：{@code <configDir>/budget-policy.json}（不存在 = 默认策略：shadow、全部不限），
 * 首次 install 时另写一份 {@code budget-policy.example.json} 供参考，不覆盖已有示例。
 * 加载入口 {@link #install(Path)}：客户端在 EntityAgentLoop 类加载时装一次；
 * RDD / 学习者插件各自 setup 里装一次（同一文件、幂等，mtime 未变不重读）。
 *
 * <p>计量：{@link #record} 由 {@link NumenLlmClient} 在每次调用完成时记一笔
 * （token 用 totalTokens；usage 缺失按 0 token 记但调用次数与耗时照记）。
 * 窗口 = 最近 {@code windowMinutes} 分钟的滑动窗口，仅存内存、重启清零——
 * 建议用途足够；跨重启对账走 monitor 的 {@code llm_usage} 事件。
 *
 * <p>角色映射（相位 → 角色）见 {@link #roleOf}。本类任何异常都不上抛给请求路径。
 */
public final class BudgetPolicy {

    private BudgetPolicy() {}

    // ---- 角色映射 ----

    /**
     * 相位 → 三脑角色。execution/execution_retry/goal_judging/compaction → execution；
     * stage_a/stage_b/fallback → planning；review → learning；其余 → unknown（只计量不建策）。
     */
    public static String roleOf(String phase) {
        if (phase == null || phase.isBlank()) return "unknown";
        return switch (phase) {
            case "execution", "execution_retry", "goal_judging", "compaction" -> "execution";
            case "stage_a", "stage_b", "fallback" -> "planning";
            case "review" -> "learning";
            default -> "unknown";
        };
    }

    // ---- 配置模型 ----

    /** 单角色限额；0 = 不限。 */
    public record Limit(long tokensPerWindow, long callsPerWindow, long minutesPerWindow) {
        public static final Limit NONE = new Limit(0, 0, 0);

        public boolean isNone() {
            return tokensPerWindow <= 0 && callsPerWindow <= 0 && minutesPerWindow <= 0;
        }
    }

    /**
     * 生效策略快照。{@code mode} = {@code shadow}（只建议）或 {@code enforce}（超限拦截）。
     *
     * @param context G4b：请求侧上下文预算（§15.5）。缺省 = 不限，行为与没有本机制一致
     * @param estimateMultiplier G4c：<b>预算专用</b>的估算校准系数（默认 1.0 = 不改行为）。
     *        为什么要有它：影子期实测 `est/actual` 中位 **3.16**、P90 **3.69**（28 对，见 G4c 卡 §6.2），
     *        开闸后按这个估算判额 → 约 2/3 配额是「假的紧」。有了这个系数，校准是**改配置 + 热重载**，
     *        不用改代码、不用重启、也不用动 {@code CompactSplit}（那把尺同时管 replay 窗口与压缩闸）。
     */
    public record Policy(int version, String mode, int windowMinutes, int adviceCooldownMinutes,
                         Map<String, Limit> roles, ContextBudget context, double estimateMultiplier) {}

    private static final Policy DEFAULT_POLICY =
            new Policy(0, "shadow", 60, 5, Map.of(), ContextBudget.unlimited(), 1.0);

    private static volatile Policy policy = DEFAULT_POLICY;
    private static volatile Path loadedFrom;
    private static volatile long loadedMtimeMs = -1L;

    // ---- 滑动窗口计量 ----

    private record Call(long atMs, long tokens, long millis) {}

    private static final Map<String, ArrayDeque<Call>> METER = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_ADVICE_MS = new ConcurrentHashMap<>();
    private static volatile LongSupplier clock = System::currentTimeMillis;

    /** 窗口用量快照。 */
    public record WindowUsage(long calls, long tokens, long millis) {
        public static final WindowUsage EMPTY = new WindowUsage(0, 0, 0);
    }

    /**
     * 一条预算求值结果：shadow 模式 = 建议事件内容；enforce 模式 = 拦截依据
     * （{@link #checkBlock} 的返回值）。{@code projectedTokens} 是「已用 + 本次预估」。
     */
    public record Advice(String role, List<String> exceeded, long usedTokens, long projectedTokens,
                         WindowUsage used, Limit limit, String mode) {}

    /** 记一笔已完成的调用（成功与失败都记；usage 缺失时 tokens 传 0）。 */
    public static void record(String phase, long tokens, long millis) {
        recordRole(roleOf(phase), tokens, millis);
    }

    /** 按<b>角色</b>记账（预扣的 charge 走这里：预留里存的已经是角色，不再做相位映射）。 */
    private static void recordRole(String role, long tokens, long millis) {
        try {
            ArrayDeque<Call> q = METER.computeIfAbsent(role, r -> new ArrayDeque<>());
            long now = clock.getAsLong();
            synchronized (q) {
                q.addLast(new Call(now, Math.max(0, tokens), Math.max(0, millis)));
                prune(q, now);
            }
        } catch (RuntimeException ex) {
            AiLog.LOG.warn("[numen-econ] 预算计量失败(不影响请求): {}", ex.toString());
        }
    }

    // ---- G4a：并发预留（reserve / charge / release）----

    /**
     * 一条预算预留（「这轮调用先占多少额度」）。
     *
     * <p><b>为什么需要它</b>：{@link #record} 是<b>事后</b>记账——两个并发请求都会先看到
     * 同一份窗口用量、都认为还有额度，于是双双发出、事后双双记超。执行角色目前是串行，
     * 问题被掩盖；检索/学习一旦并发出调用，这条缺口就是真超支（IC09）。
     *
     * @param policyVersion 占额时生效的策略版本（G4b-fix）。<b>扣账必须校验它</b>：
     *                      热重载让「占额在 v10、落地在 v11」成为真事，不校验就会出现
     *                      「按旧限额占的额度、按新限额记账」这种对不上账的账
     */
    public record Reservation(long id, String role, long tokens, long expiresAtMs, int policyVersion) {}

    /** 预留默认租约：5 分钟。漏 release 的异常路径不会永久占额。 */
    public static final long DEFAULT_RESERVATION_TTL_MS = 5L * 60_000L;

    private static final Map<Long, Reservation> RESERVATIONS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong RESERVATION_IDS =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 申请预留：在「窗口已用 + 其他活跃预留」上判限额。
     *
     * @return 预留；限额不足 / 角色不限 / 异常 → null（调用方按 {@link #checkBlock} 语义处理）
     */
    public static Reservation reserve(String phase, long estimatedTokens, long ttlMillis) {
        refreshIfChanged();
        try {
            Policy p = policy;
            String role = roleOf(phase);
            Limit limit = p.roles().get(role);
            if (limit == null || limit.isNone()) {
                return null;   // 不限的角色不占额（占了也拦不住任何东西）
            }
            long now = clock.getAsLong();
            pruneReservations(now);
            long want = Math.max(0, estimatedTokens);
            WindowUsage withRes = windowUsageWithReservations(role);
            if (limit.tokensPerWindow() > 0 && withRes.tokens() + want > limit.tokensPerWindow()) {
                return null;
            }
            if (limit.callsPerWindow() > 0 && withRes.calls() + 1 > limit.callsPerWindow()) {
                return null;
            }
            long ttl = ttlMillis <= 0 ? DEFAULT_RESERVATION_TTL_MS : ttlMillis;
            long id = RESERVATION_IDS.incrementAndGet();
            Reservation reservation = new Reservation(id, role, want, now + ttl, p.version());
            RESERVATIONS.put(id, reservation);
            return reservation;
        } catch (RuntimeException ex) {
            AiLog.LOG.warn("[numen-econ] 预算预留失败(按不预留处理): {}", ex.toString());
            return null;
        }
    }

    /**
     * 扣账：把预留换成一次真实调用（用<b>实际</b>用量，不是预估）。
     *
     * @return true = 该预留存在并已扣账；false = 预留不存在/已过期（不补记，避免凭空多一笔）
     */
    public static boolean charge(long reservationId, long actualTokens) {
        return charge(reservationId, actualTokens, 0L);
    }

    /** 扣账结果：为什么没扣成，调用点要能说清楚（而不是只知道「false」）。 */
    public record ChargeOutcome(boolean charged, String reason) {
        static final String CHARGED = "CHARGED";
        static final String NOT_FOUND = "NOT_FOUND";
        static final String EXPIRED = "EXPIRED";
        static final String POLICY_CHANGED = "POLICY_CHANGED";
    }

    /**
     * 扣账（带耗时，带策略版本校验）。
     *
     * <p>{@code minutesPerWindow} 限额看的是耗时，所以耗时与用量必须一起记。
     *
     * <p><b>策略版本校验（2026-10-20 复审要求）</b>：热重载让「占额在 v10、落地在 v11」成为真事。
     * 版本不一致时<b>不按预留记账</b>（那张额度是按旧规则占的），把账交还调用点用
     * {@link #record} 记一笔真实用量——<b>账不能丢</b>，但要能被数出来（{@link #policyVersionMismatches()}）。
     *
     * @return {@code charged=true} 只有一种情况：预留存在、未过期、策略版本未变
     */
    public static ChargeOutcome chargeDetailed(long reservationId, long actualTokens, long millis) {
        Reservation r = RESERVATIONS.get(reservationId);
        if (r == null) {
            return new ChargeOutcome(false, ChargeOutcome.NOT_FOUND);
        }
        // 过期预留不可扣账：它占的额度已按 TTL 释放，再补记就变成凭空多一笔调用。
        if (clock.getAsLong() >= r.expiresAtMs()) {
            RESERVATIONS.remove(reservationId);
            return new ChargeOutcome(false, ChargeOutcome.EXPIRED);
        }
        int nowVersion = policy.version();
        if (r.policyVersion() != nowVersion) {
            RESERVATIONS.remove(reservationId);
            POLICY_VERSION_MISMATCHES.incrementAndGet();
            AiLog.LOG.info("[numen-econ] 预留跨策略版本落地：占额 v{} → 扣账 v{}，改由调用点直接记账",
                    r.policyVersion(), nowVersion);
            return new ChargeOutcome(false, ChargeOutcome.POLICY_CHANGED);
        }
        RESERVATIONS.remove(reservationId);
        recordRole(r.role(), actualTokens, millis);
        return new ChargeOutcome(true, ChargeOutcome.CHARGED);
    }

    /**
     * 扣账（boolean 形式，语义同 {@link #chargeDetailed}）。
     *
     * @param millis 本次调用耗时；{@code minutesPerWindow} 按它累计
     * @return true = 该预留存在、未过期、且策略版本未变，已扣账
     */
    public static boolean charge(long reservationId, long actualTokens, long millis) {
        return chargeDetailed(reservationId, actualTokens, millis).charged();
    }

    private static final java.util.concurrent.atomic.AtomicInteger POLICY_VERSION_MISMATCHES =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 「预留跨策略版本落地」的次数（只观测）。
     *
     * <p>为什么要能数：热重载是好事，但它让「占额时的规则」与「扣账时的规则」可以不同。
     * 次数长期为 0 说明没人边跑边改配置；一旦开闸（enforce）后仍频繁非 0，
     * 就意味着限额口径在漂移——那是要查的，不是要忍的。
     */
    public static int policyVersionMismatches() {
        return POLICY_VERSION_MISMATCHES.get();
    }

    /**
     * 失败释放：只移除预留，<b>不记调用</b>——没发生的事不许记账。
     *
     * @return true = 确有预留被释放
     */
    public static boolean release(long reservationId) {
        return RESERVATIONS.remove(reservationId) != null;
    }

    /** 该角色当前活跃预留数（观测/测试用）。 */
    public static int activeReservations(String role) {
        pruneReservations(clock.getAsLong());
        int n = 0;
        for (Reservation r : RESERVATIONS.values()) {
            if (r.role().equals(role)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 「窗口已用 + 活跃预留」的用量视图（求值用）。
     *
     * <p>{@link #windowUsage} 保持原义（只算真实调用）；本方法把预留也算进 calls/tokens，
     * 否则并发两路都会看到「还没用」。
     */
    public static WindowUsage windowUsageWithReservations(String role) {
        WindowUsage used = windowUsage(role);
        pruneReservations(clock.getAsLong());
        long tokens = used.tokens();
        long calls = used.calls();
        for (Reservation r : RESERVATIONS.values()) {
            if (r.role().equals(role)) {
                tokens += r.tokens();
                calls += 1;
            }
        }
        return new WindowUsage(calls, tokens, used.millis());
    }

    private static void pruneReservations(long now) {
        RESERVATIONS.entrySet().removeIf(e -> now >= e.getValue().expiresAtMs());
    }

    private static void prune(ArrayDeque<Call> q, long now) {
        long cutoff = now - policy.windowMinutes() * 60_000L;
        while (!q.isEmpty() && q.peekFirst().atMs() < cutoff) q.removeFirst();
    }

    /** 该角色当前窗口用量。 */
    public static WindowUsage windowUsage(String role) {
        ArrayDeque<Call> q = METER.get(role);
        if (q == null) return WindowUsage.EMPTY;
        long now = clock.getAsLong();
        synchronized (q) {
            prune(q, now);
            long calls = 0, tokens = 0, millis = 0;
            for (Call c : q) {
                calls++;
                tokens += c.tokens();
                millis += c.millis();
            }
            return new WindowUsage(calls, tokens, millis);
        }
    }

    /**
     * 发出建议前的求值（仅 {@code shadow} 模式；enforce 模式返回 null，拦截走
     * {@link #checkBlock}）：超限返回一条 {@link Advice}（调用方发 {@code budget_advice}
     * 事件），否则 null。同一角色在 {@code adviceCooldownMinutes} 内最多建议一次（平稳心跳限频）。
     */
    public static Advice advice(String phase, long estimatedPromptTokens) {
        refreshIfChanged();
        try {
            Policy p = policy;
            if (!"shadow".equals(p.mode())) return null;
            Advice a = evaluate(p, phase, estimatedPromptTokens);
            if (a == null) return null;
            long now = clock.getAsLong();
            Long last = LAST_ADVICE_MS.get(a.role());
            if (last != null && now - last < p.adviceCooldownMinutes() * 60_000L) return null;
            LAST_ADVICE_MS.put(a.role(), now);
            return a;
        } catch (RuntimeException ex) {
            AiLog.LOG.warn("[numen-econ] 预算求值失败(不影响请求): {}", ex.toString());
            return null;
        }
    }

    /**
     * 拦截求值（仅 {@code enforce} 模式）：该角色限额用尽 → 返回 {@link Advice}
     * （调用方据此拦截本次调用），否则 null。
     *
     * <p><b>纯函数：无冷却、无副作用</b>——调用点可能每个唤醒事件都查一次（零成本），
     * 事件/聊天栏通知的限频由 {@link #notifyBlock} 负责，二者分开。
     */
    public static Advice checkBlock(String phase, long estimatedPromptTokens) {
        refreshIfChanged();
        try {
            Policy p = policy;
            if (!"enforce".equals(p.mode())) return null;
            return evaluate(p, phase, estimatedPromptTokens);
        } catch (RuntimeException ex) {
            AiLog.LOG.warn("[numen-econ] 拦截求值失败(放行本次): {}", ex.toString());
            return null;
        }
    }

    /**
     * G4c S107（<b>方案 A：主人回合豁免</b>）：主人的话撞上预算用尽时，<b>不拦</b>。
     *
     * <p><b>为什么选 A 而不是 B</b>：预算管的是「花多少钱」，不是「谁说了算」。
     * 把主人的一句指令压到「等窗口重置」，等于系统推翻主人（RL-10 的精神），
     * 而代价只是这一轮多花一点额度 —— 主人是老板，这个取舍是划算的。
     * 同时发一条 {@code budget_owner_override} 事件，让「预算被主人刷过头」这件事可被数出来，
     * 而不是变成暗账。
     *
     * <p><b>方案 B（照拦 + 立刻告知排队）</b>只差一行：把本方法改成直接调 {@link #checkBlock}，
     * 由调用点负责把「等 N 分钟」说给主人听。**两种都行，唯一不许的是第三种：静默压着。**
     *
     * <p>注意：豁免只作用于「这一轮要不要发」，<b>不改变计量</b>——花掉的 token 照样记，
     * 所以下一轮会不会被拦，取决于真实用量，而不是取决于这次豁免。
     *
     * @param ownerSpokeThisTurn 本轮是否由主人的输入触发（{@code false} = 她自发的轮次）
     * @return {@code null} = 放行（主人回合，或本来就没超限/不是 enforce）；否则同 {@link #checkBlock}
     */
    public static Advice checkBlockForTurn(String phase, long estimatedPromptTokens,
                                           boolean ownerSpokeThisTurn) {
        if (ownerSpokeThisTurn) return null;   // 方案 A
        return checkBlock(phase, estimatedPromptTokens);
    }

    /** 超限判定（两种模式共用的纯求值，不带冷却）。 */
    private static Advice evaluate(Policy p, String phase, long estimatedPromptTokens) {
        String role = roleOf(phase);
        Limit limit = p.roles().get(role);
        if (limit == null || limit.isNone()) return null;
        WindowUsage used = windowUsage(role);
        long estimated = Math.max(0, estimatedPromptTokens);
        long projected = used.tokens() + estimated;
        List<String> exceeded = new ArrayList<>();
        // G4c S108：**退化配置**——窗口里一次调用都没有，单看这一次请求就已经超出整个窗口额度。
        // 这种额度拦了也解不开：窗口滚过之后用量仍是 0，下一次还是被同一份请求撑爆。
        // 拦住 = 把她永久关死；放行并大声告警，才是诚实的做法（宁可超预算，也不许卡死）。
        boolean degenerateTokens = limit.tokensPerWindow() > 0
                && used.tokens() == 0 && estimated > limit.tokensPerWindow();
        if (degenerateTokens) {
            noteDegenerateConfig(role, estimated, limit.tokensPerWindow());
        } else if (limit.tokensPerWindow() > 0 && projected > limit.tokensPerWindow()) {
            exceeded.add("tokens");
        }
        if (limit.callsPerWindow() > 0 && used.calls() + 1 > limit.callsPerWindow()) exceeded.add("calls");
        if (limit.minutesPerWindow() > 0 && used.millis() > limit.minutesPerWindow() * 60_000L) {
            exceeded.add("minutes");
        }
        if (exceeded.isEmpty()) return null;
        return new Advice(role, List.copyOf(exceeded), used.tokens(), projected, used, limit, p.mode());
    }

    /**
     * 退化配置的告警（每个「角色 + 额度」组合只喊一次，免得刷屏）。
     *
     * <p>为什么必须喊：放行是刻意的取舍，但<b>不能让这件事静默</b>——否则主人只会看到
     * 「预算配了却不拦」，然后以为预算坏了。
     */
    private static void noteDegenerateConfig(String role, long estimated, long limitTokens) {
        try {
            String key = role + "|" + limitTokens;
            if (DEGENERATE_WARNED.add(key)) {
                DEGENERATE_CONFIGS.incrementAndGet();
                AiLog.LOG.warn("[numen-econ] 预算配置退化：{} 角色单次请求就要 {} token，已超过整个窗口额度 {} ——"
                                + "本次放行（拦了也解不开，会把同伴永久关死）。请把 tokensPerWindow 调到大于单次请求。",
                        role, estimated, limitTokens);
            }
        } catch (RuntimeException ignored) {
            // 观测不许影响求值
        }
    }

    /** 已告警的退化组合（去重用；"角色|额度"）。 */
    private static final java.util.Set<String> DEGENERATE_WARNED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final java.util.concurrent.atomic.AtomicInteger DEGENERATE_CONFIGS =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 识别到「退化额度」的次数（只观测）。
     *
     * <p>长期非 0 = 有人配了「永远拦死」的额度（或被放过一次而没改配置）。
     * 这条数只用来提醒，不参与任何判定。
     */
    public static int degenerateConfigs() {
        return DEGENERATE_CONFIGS.get();
    }

    /**
     * 拦截通知限频：同一角色在 {@code adviceCooldownMinutes} 内最多通知一次
     * （事件 + 聊天栏）。返回 true = 这次该通知，已记账；false = 冷却内，静默放行到下一轮。
     */
    public static boolean notifyBlock(Advice block) {
        if (block == null) return false;
        try {
            Policy p = policy;
            long now = clock.getAsLong();
            Long last = LAST_ADVICE_MS.get(block.role());
            if (last != null && now - last < p.adviceCooldownMinutes() * 60_000L) return false;
            LAST_ADVICE_MS.put(block.role(), now);
            return true;
        } catch (RuntimeException ex) {
            AiLog.LOG.warn("[numen-econ] 拦截通知限频失败(按通知处理): {}", ex.toString());
            return true;
        }
    }

    // ---- 加载 ----

    /**
     * 加载 {@code <configDir>/budget-policy.json}；文件缺失 = 默认策略（shadow、不限）。
     * 同一文件 mtime 未变则不重复读。任何异常都不上抛（观测/建议层）。
     */
    public static void install(Path configDir) {
        if (configDir == null) return;
        try {
            Path file = configDir.resolve("budget-policy.json");
            writeExampleIfMissing(configDir);
            if (!Files.exists(file)) {
                if (!file.equals(loadedFrom) || loadedMtimeMs != -1L) {
                    policy = DEFAULT_POLICY;
                    loadedFrom = file;
                    loadedMtimeMs = -1L;
                    AiLog.LOG.info("[numen-econ] 未发现 budget-policy.json → 默认策略（shadow、全部不限）");
                }
                return;
            }
            long mtime = Files.getLastModifiedTime(file).toMillis();
            if (file.equals(loadedFrom) && mtime == loadedMtimeMs) return;
            Policy parsed = parse(Files.readString(file, StandardCharsets.UTF_8));
            policy = parsed;
            loadedFrom = file;
            loadedMtimeMs = mtime;
            AiLog.LOG.info("[numen-econ] 预算策略已加载: version={}, mode={}, window={}min, 角色限额={}, 上下文预算={}",
                    parsed.version(), parsed.mode(), parsed.windowMinutes(), describeLimits(parsed),
                    parsed.context() == null ? "unlimited" : parsed.context().describe());
        } catch (Exception ex) {
            policy = DEFAULT_POLICY;
            AiLog.LOG.warn("[numen-econ] 预算策略加载失败，退回默认（shadow、全部不限）: {}", ex.toString());
        }
    }

    private static Policy parse(String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        int version = (int) longOr(root, "version", 1);
        String requestedMode = strOr(root, "mode", "shadow").trim().toLowerCase();
        String mode;
        if ("enforce".equals(requestedMode)) {
            mode = "enforce";
        } else {
            mode = "shadow";
            if (!"shadow".equals(requestedMode)) {
                AiLog.LOG.warn("[numen-econ] 未知 mode '{}' → 按 shadow 执行", requestedMode);
            }
        }
        int window = (int) clamp(longOr(root, "windowMinutes", 60), 5, 1440);
        int cooldown = (int) clamp(longOr(root, "adviceCooldownMinutes", 5), 1, 60);
        Map<String, Limit> roles = new LinkedHashMap<>();
        JsonElement rolesEl = root.get("roles");
        if (rolesEl != null && rolesEl.isJsonObject()) {
            for (var e : rolesEl.getAsJsonObject().entrySet()) {
                JsonElement v = e.getValue();
                if (!v.isJsonObject()) {
                    AiLog.LOG.warn("[numen-econ] 角色 '{}' 的限额不是对象，已忽略", e.getKey());
                    continue;
                }
                JsonObject o = v.getAsJsonObject();
                for (String key : o.keySet()) {
                    if (!key.equals("tokensPerWindow") && !key.equals("callsPerWindow")
                            && !key.equals("minutesPerWindow")) {
                        AiLog.LOG.warn("[numen-econ] 角色 '{}' 含未知字段 '{}'（拼错的字段会被当成不限！）",
                                e.getKey(), key);
                    }
                }
                roles.put(e.getKey(), new Limit(
                        Math.max(0, longOr(o, "tokensPerWindow", 0)),
                        Math.max(0, longOr(o, "callsPerWindow", 0)),
                        Math.max(0, longOr(o, "minutesPerWindow", 0))));
            }
        }
        return new Policy(version, mode, window, cooldown, Map.copyOf(roles), parseContext(root),
                parseEstimateMultiplier(root));
    }

    /**
     * 解析可选的 {@code context} 段（G4b，§15.5）。<b>整段不写 = 全不限</b>——
     * 老配置文件一个字都不用改，行为与没有本机制时完全一致（判据 S87）。
     */
    private static ContextBudget parseContext(JsonObject root) {
        JsonElement el = root.get("context");
        if (el == null || el.isJsonNull()) return ContextBudget.unlimited();
        if (!el.isJsonObject()) {
            AiLog.LOG.warn("[numen-econ] context 不是对象，按「不限」处理");
            return ContextBudget.unlimited();
        }
        JsonObject o = el.getAsJsonObject();
        for (String key : o.keySet()) {
            switch (key) {
                case "maxCandidates", "maxRows", "maxExpanded", "maxTokens", "maxChars",
                     "mandatoryReservedTokens" -> { }
                default -> AiLog.LOG.warn("[numen-econ] context 含未知字段 '{}'（拼错的字段会被当成「不限」！）", key);
            }
        }
        ContextBudget b = new ContextBudget(
                (int) Math.max(0, longOr(o, "maxCandidates", 0)),
                (int) Math.max(0, longOr(o, "maxRows", 0)),
                (int) Math.max(0, longOr(o, "maxExpanded", 0)),
                Math.max(0, longOr(o, "maxTokens", 0)),
                Math.max(0, longOr(o, "maxChars", 0)),
                Math.max(0, longOr(o, "mandatoryReservedTokens", 0)),
                TOKENIZER_VERSION);
        if (!b.isUnlimited()) {
            AiLog.LOG.info("[numen-econ] 上下文预算: {}", b.describe());
        }
        return b;
    }

    /** 记录 token 是哪个尺子算的（只记录，不计量；换尺子要改这里并同步批次卡）。 */
    public static final String TOKENIZER_VERSION = "compact-split-v1";

    // ---- G4c：预算专用估算校准 ----

    /**
     * 解析可选的 {@code estimateMultiplier}（G4c）。<b>不写 = 1.0 = 行为与今天完全一致。</b>
     *
     * <p>取值夹在 0.05~10：0 或负数会把估算压成「不占额」，那等于偷偷关掉限额，比不做校准更危险。
     */
    private static double parseEstimateMultiplier(JsonObject root) {
        JsonElement el = root.get("estimateMultiplier");
        if (el == null || el.isJsonNull()) return 1.0;
        double raw;
        try {
            raw = el.getAsDouble();
        } catch (RuntimeException ex) {
            AiLog.LOG.warn("[numen-econ] estimateMultiplier 不是数字，按 1.0 处理");
            return 1.0;
        }
        if (Double.isNaN(raw) || raw <= 0) {
            AiLog.LOG.warn("[numen-econ] estimateMultiplier={} 非法（必须 >0），按 1.0 处理", raw);
            return 1.0;
        }
        double clamped = Math.max(0.05, Math.min(10.0, raw));
        if (clamped != raw) {
            AiLog.LOG.warn("[numen-econ] estimateMultiplier={} 超出 [0.05,10]，按 {} 处理", raw, clamped);
        }
        AiLog.LOG.info("[numen-econ] 预算估算校准系数 = {}（占额 = 原始估算 × 这个数）", clamped);
        return clamped;
    }

    /** 预算专用的估算校准系数（默认 1.0）。 */
    public static double estimateMultiplier() {
        try {
            double m = policy.estimateMultiplier();
            return m > 0 ? m : 1.0;
        } catch (RuntimeException ex) {
            return 1.0;
        }
    }

    /**
     * 把「原始估算」换算成「预算占额用的估算」（乘校准系数）。
     *
     * <p>只作用于占额/判额，**不动** {@code CompactSplit} 的尺 —— 那把尺同时管 replay 窗口与压缩闸，
     * 改它等于改上下文策略；预算的偏差来自「系统提示+工具表整体计入」这种结构差异，
     * 该由预算侧单独校准。
     */
    public static long scaleEstimate(long rawTokens) {
        if (rawTokens <= 0) return 0;
        return Math.max(0L, Math.round(rawTokens * estimateMultiplier()));
    }

    /**
     * 该角色还剩多少毫秒「窗口才会让出额度」——即窗口里最早那笔调用滑出窗口的时刻。
     *
     * <p><b>为什么需要它</b>（G4c S102「窗口重置后自动恢复」）：现在被预算拦下后，只会在<b>下一个
     * 唤醒事件</b>时重查一次。如果那时没有新的唤醒事件（主人在挂机、身体又没在干活），目标就停在
     * 「等窗口重置」这句话上没人叫醒它 —— 那不是恢复，是等运气。有了这个数，调用点就能安排一次
     * 「几点几分自己再来」的定时唤醒，而不是靠人催。
     *
     * @return 毫秒；{@code 0} = 现在就有额度（窗口里没有调用）或该角色不限
     */
    public static long millisUntilWindowReset(String role) {
        ArrayDeque<Call> q = METER.get(role);
        if (q == null) return 0L;
        long now = clock.getAsLong();
        long windowMillis = policy.windowMinutes() * 60_000L;
        synchronized (q) {
            prune(q, now);
            if (q.isEmpty()) return 0L;
            long oldest = q.peekFirst().atMs();
            long resetAt = oldest + windowMillis;
            return Math.max(0L, resetAt - now);
        }
    }

    /**
     * {@link #millisUntilWindowReset(String)} 的秒数版（给事件/日志用，向上取整：
     * 报「还剩 0 秒」时其实还要等一下，会让人以为马上就好）。
     */
    public static long secondsUntilWindowReset(String phaseOrRole) {
        String role = phaseOrRole != null && METER.containsKey(phaseOrRole) ? phaseOrRole : roleOf(phaseOrRole);
        long millis = millisUntilWindowReset(role);
        if (millis <= 0) return 0L;
        return (millis + 999L) / 1000L;
    }

    /**
     * 该相位所属角色<b>是否配了限额</b>。只读、不建策、不记账。
     *
     * <p>调用点用它区分两件长得一样的事：「这个角色根本没限额」（什么都不用报）与
     * 「有额度、但这次没占到」（必须报，否则「为什么这轮没有预留」只能靠猜）。
     */
    public static boolean roleHasLimit(String phase) {
        refreshIfChanged();
        try {
            Limit l = policy.roles().get(roleOf(phase));
            return l != null && !l.isNone();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * 配置热重载（G4b）：<b>mtime 变了就重装，不必重启游戏。</b>
     *
     * <p>为什么非要有它：{@code budget-policy.json} 是给人手调的旋钮。原来只在
     * {@code EntityAgentLoop} 类加载时装一次 —— 改个数值要重启整个游戏，实际结果就是
     * 「这个旋钮没人会调」。实机验证时也逼着为每个数值重启一次（一次三分钟，还容易忘）。
     *
     * <p>代价：每次读策略多一次 {@code getLastModifiedTime}（每轮一次，微秒级）。
     * 失败一律吞掉：读不到就保持当前策略，观测/建议层不许影响请求。
     */
    public static void refreshIfChanged() {
        Path file = loadedFrom;
        if (file == null) return;
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            if (mtime != loadedMtimeMs) {
                install(file.getParent());
            }
        } catch (Exception ignored) {
            // 文件被删/被占用：退回「缺失 = 默认策略」的语义，而不是抱着旧值不放。
            try {
                install(file.getParent());
            } catch (Exception ignoredToo) {
                // 目录都没了：保持现状
            }
        }
    }

    /** 生效的上下文预算（调用点每轮取一次；不限额时 {@link ContextBudget#isUnlimited()} 为真）。 */
    public static ContextBudget contextBudget() {
        refreshIfChanged();
        try {
            Policy p = policy;
            return p.context() == null ? ContextBudget.unlimited() : p.context();
        } catch (RuntimeException ex) {
            AiLog.LOG.warn("[numen-econ] 读上下文预算失败(按不限处理): {}", ex.toString());
            return ContextBudget.unlimited();
        }
    }

    private static void writeExampleIfMissing(Path configDir) {
        try {
            Path example = configDir.resolve("budget-policy.example.json");
            if (Files.exists(example)) return;
            Files.createDirectories(configDir);
            Files.writeString(example, EXAMPLE_JSON, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            AiLog.LOG.warn("[numen-econ] 写示例文件失败（不影响运行）: {}", ex.toString());
        }
    }

    private static String describeLimits(Policy p) {
        if (p.roles().isEmpty()) return "（无）";
        StringBuilder sb = new StringBuilder();
        p.roles().forEach((role, lim) -> {
            if (sb.length() > 0) sb.append("; ");
            sb.append(role).append("(tokens=").append(lim.tokensPerWindow())
                    .append(",calls=").append(lim.callsPerWindow())
                    .append(",minutes=").append(lim.minutesPerWindow()).append(")");
        });
        return sb.toString();
    }

    private static long longOr(JsonObject o, String key, long def) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) return def;
        try {
            return el.getAsLong();
        } catch (RuntimeException ex) {
            AiLog.LOG.warn("[numen-econ] 字段 '{}' 不是数字，按默认 {} 处理", key, def);
            return def;
        }
    }

    private static String strOr(JsonObject o, String key, String def) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) return def;
        try {
            return el.getAsString();
        } catch (RuntimeException ex) {
            AiLog.LOG.warn("[numen-econ] 字段 '{}' 不是字符串，按默认 '{}' 处理", key, def);
            return def;
        }
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    // ---- 测试缝 ----

    /** 测试用：注入时钟。 */
    static void setClockForTest(LongSupplier c) {
        clock = c;
    }

    /** 测试用：清空计量、建议冷却、活跃预留与已加载策略。 */
    static void resetForTest() {
        METER.clear();
        LAST_ADVICE_MS.clear();
        RESERVATIONS.clear();
        RESERVATION_IDS.set(0L);
        POLICY_VERSION_MISMATCHES.set(0);
        DEGENERATE_CONFIGS.set(0);
        DEGENERATE_WARNED.clear();
        policy = DEFAULT_POLICY;
        loadedFrom = null;
        loadedMtimeMs = -1L;
        clock = System::currentTimeMillis;
    }

    /** 测试用：当前生效策略。 */
    static Policy policyForTest() {
        return policy;
    }

    static final String EXAMPLE_JSON = """
            {
              "version": 1,
              "mode": "shadow",
              "windowMinutes": 60,
              "adviceCooldownMinutes": 5,
              "roles": {
                "execution": { "tokensPerWindow": 0, "callsPerWindow": 0, "minutesPerWindow": 0 },
                "planning":  { "tokensPerWindow": 0, "callsPerWindow": 0, "minutesPerWindow": 0 },
                "learning":  { "tokensPerWindow": 0, "callsPerWindow": 0, "minutesPerWindow": 0 }
              },
              "context": {
                "maxTokens": 0, "maxChars": 0, "mandatoryReservedTokens": 0,
                "maxCandidates": 0, "maxRows": 0, "maxExpanded": 0
              },
              "_readme": [
                "0 = 不限（缺省即不限，不会给出任何建议、也不会拦截）。",
                "tokensPerWindow 计 totalTokens（输入+输出+缓存，按模型上报）；callsPerWindow 计调用次数；minutesPerWindow 计调用耗时（分钟）。",
                "mode: shadow = 只发 budget_advice 建议事件、不拦截任何调用；enforce = 超限拦截对应角色的新调用。",
                "enforce 拦截行为（绝不静默停）：执行=不开下一轮，状态行/聊天栏/事件给出明确原因，等窗口重置或主人唤醒再查；规划=回落占位链并记事件；学习=复盘推迟并恢复队列。",
                "enforce 不拦：一轮收尾的目标判定调用、已发出的重试（最后一轮成果必须判完）；0=不限的角色永不拦截。",
                "拦截通知（事件+聊天栏）受 adviceCooldownMinutes 限频；窗口是滑动窗口（最近 windowMinutes 分钟）。",
                "计量只在内存、重启清零，跨重启对账请看 monitor 的 llm_usage / budget_block 事件。",
                "角色按相位归并：execution=execution/execution_retry/goal_judging/compaction；planning=stage_a/stage_b/fallback；learning=review；其余=unknown（只计量不建策）。",
                "确定性生存反射不在此机制内（不等远程 LLM，属 E2 缺口）。",
                "context 段是请求侧上下文预算（§15.5）：先预留必知区，再压可选段落；必知区自身装不下就显式拒绝这一轮，绝不裁必知区。",
                "context 六维全部 0 = 不限（整段不写也一样）；字符上限是独立硬上限，不是 token 的替身。",
                "此文件是示例；要生效请复制为 budget-policy.json 再改（本示例不会被读取）。"
              ]
            }
            """;
}
