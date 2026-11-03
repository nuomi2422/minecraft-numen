package com.dwinovo.numen.plugins.rdd;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H3（108 号）：**运行时**的重复查询感知。
 *
 * <p>为什么需要它：{@code exp-loop-r3} 里 {@code rdd_get_inventory} 被调 42 次 / 10 分钟
 * （占全部工具调用 64%），而 A3 的 {@code redundant_query} 是 **harness 归档后处理**
 * （{@code realtime:false}）—— 跑的时候不告警，所以同伴不会自我纠正。
 *
 * <p>做法：旁路计数器（不拦调用、不改回执），同一 {@code tool + args_chars} 在窗口内
 * 第 N 次（N≥{@link #MIN_COUNT}）时通过 {@link RddPlugin#nudge} 给一次「你刚问过，答案没变」的提醒。
 *
 * <p><b>可测性</b>：计数与判定是纯逻辑（{@link #observe}），副作用（nudge / 事件）走
 * {@link Notifier} 注入 —— 单测传空实现就不会碰 Minecraft 类。
 *
 * <p>开关：{@code config/numen/redundant-query-off.flag} 存在即完全静默（沿用现有 flag 习惯）。
 */
final class RddRepeatQueryWatcher {

    /** 同一 key 至少出现这么多次才算「重复查询」。 */
    static final int MIN_COUNT = 3;
    /** 窗口：600 tick = 30 秒（工具调用串行，30 秒足够覆盖「连着查同一件事」）。 */
    static final int WINDOW_TICKS = 600;
    /** 提醒节流：同一 key 至少隔这么久才再提醒一次（避免刷屏）。 */
    static final int RENOTIFY_TICKS = 1200;

    private static final Map<String, State> SEEN = new ConcurrentHashMap<>();
    private static volatile Path offFlag;
    private static volatile Notifier notifier;

    private record State(int count, long firstTick, long lastNotifyTick) {}

    /** 副作用出口：发提醒 + 发事件。生产实现见 {@link #install}。 */
    interface Notifier {
        void notifyRepeat(UUID companionId, String tool, int argsChars, int count);
    }

    private RddRepeatQueryWatcher() {}

    /**
     * 2026-11-03（外部学习者 P0）：逐点扫描型工具不能用「答案没变」去劝。
     *
     * <p>实机（13:00–13:28）：{@code inspect_block} 被调 56 次 / 46 个不同坐标、单轮最多
     * 10 个并行 —— 它是在给「放床」找位置，**每次查的是不同方块，答案当然不同**。
     * 而本 watcher 按 {@code tool + args_chars} 计数，不同坐标只要参数长度一样就被算成
     * 「同一个查询」，于是发出「你在 30 秒内第 N 次查同一个东西，答案没变」——
     * 这句是**错的**，模型不会理，调用继续。最近三个 rdd 归档里各 6 条 redundant 事件即此。
     *
     * <p>改成对逐点工具说清「逐格枚举贵、改用面扫描」，才是模型能照做的指令。
     */
    static final java.util.Set<String> POINT_SCAN_TOOLS = java.util.Set.of("inspect_block");

    /** 提醒正文（纯函数，便于单测钉住文案指向的工具）。 */
    static String nudgeText(String tool, int count) {
        int windowSeconds = WINDOW_TICKS / 20;
        if (tool != null && POINT_SCAN_TOOLS.contains(tool)) {
            return "[重复查询] 你在 " + windowSeconds + " 秒内第 " + count + " 次逐点查方块（" + tool + "）。"
                    + "\n逐格枚举很贵：要确认一片区域时改用 scan_blocks（一次扫半径内的方块，block_ids 里带上你关心的种类），"
                    + "或先把范围缩到真正要确认的那一两格。"
                    + "\n每次 inspect_block 都是一次完整往返，10 次逐点查等于 10 次预算。";
        }
        return "[重复查询] 你在 " + windowSeconds + " 秒内第 " + count + " 次查同一个东西（" + tool + "），**答案没变**。"
                + "\n刚查到的结果就在你上一条回执里，直接用；要换就换个参数或换个工具。"
                + "\n不要为了确认而重复查——那会吃掉你的时间预算。";
    }

    static void install(Path configDir) {
        offFlag = configDir == null ? null : configDir.resolve("redundant-query-off.flag");
        notifier = (companionId, tool, argsChars, count) -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("companionId", companionId.toString());
            data.put("tool", tool);
            data.put("argsChars", argsChars);
            data.put("count", count);
            data.put("windowTicks", WINDOW_TICKS);
            RddMonitor.publish("redundant_query_runtime", data);
            RddPlugin.nudge(companionId, nudgeText(tool, count));
        };
        SEEN.clear();
    }

    /** 测试用：替换副作用出口。 */
    static void installNotifier(Notifier n) {
        notifier = n;
    }

    private static boolean disabled() {
        try {
            return offFlag != null && Files.exists(offFlag);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 记一次工具调用，必要时提醒。
     *
     * @return true = 本次该提醒（已交给 {@link Notifier}）
     */
    static boolean observe(UUID companionId, String tool, int argsChars, long gameTick) {
        if (companionId == null || tool == null || tool.isBlank() || disabled()) {
            return false;
        }
        String key = companionId + "|" + tool + "|" + argsChars;
        State prev = SEEN.get(key);
        if (prev == null || gameTick - prev.firstTick() > WINDOW_TICKS) {
            SEEN.put(key, new State(1, gameTick, 0L));
            return false;
        }
        int count = prev.count() + 1;
        boolean due = count >= MIN_COUNT && (prev.lastNotifyTick() == 0L
                || gameTick - prev.lastNotifyTick() >= RENOTIFY_TICKS);
        SEEN.put(key, new State(count, prev.firstTick(), due ? gameTick : prev.lastNotifyTick()));
        if (!due) {
            return false;
        }
        Notifier n = notifier;
        if (n != null) {
            try {
                n.notifyRepeat(companionId, tool, argsChars, count);
            } catch (RuntimeException ignored) {
                // 提醒失败不影响工具调用
            }
        }
        return true;
    }

    static void clear(UUID companionId) {
        if (companionId == null) {
            SEEN.clear();
            return;
        }
        String prefix = companionId + "|";
        SEEN.keySet().removeIf((k) -> k.startsWith(prefix));
    }
}
