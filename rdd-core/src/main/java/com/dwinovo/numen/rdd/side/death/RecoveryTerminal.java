package com.dwinovo.numen.rdd.side.death;

import java.util.Map;

/**
 * 读取死亡恢复支线的终止原因（兼容两种键名）。
 *
 * <h2>为什么需要这个类</h2>
 * 2026-10-31 实机：连续两次 death_recovery_settled 的 terminalReason 都是空串，
 * 于是 ledgerState 恒为 UNVERIFIED、replanRequested 恒为 true ——
 * 即使掉落其实已经全部捡回，也会被当成『不确定』并触发重规划。
 *
 * <p>根因是键名不一致：产出侧写 terminal_reason（下划线），
 * 消费侧读 deathRecoveryTerminalReason（驼峰），两者对不上。
 * 本类把兼容收在一处，避免两边继续各写各的。
 */
public final class RecoveryTerminal {

    /** 产出侧键名（snake_case）。 */
    public static final String KEY_SNAKE = "terminal_reason";

    /** 消费侧历史键名（camelCase）。 */
    public static final String KEY_CAMEL = "deathRecoveryTerminalReason";

    /** 掉落已全部观测回背包。 */
    public static final String RECOVERED = "drops_recovered";

    /** 有世界证据表明掉落实体不可回收。 */
    public static final String CONFIRMED_ABSENT = "drops_absent_with_world_evidence";

    private RecoveryTerminal() {
    }

    /**
     * 读终止原因：驼峰键优先（老数据），其次下划线键（当前产出）。
     *
     * @return 终止原因；都没有时返回空串（不是 null —— 调用方用一个哨兵好过两个）
     */
    public static String parse(Map<String, ?> data) {
        if (data == null) {
            return "";
        }
        Object camel = data.get(KEY_CAMEL);
        if (camel != null && !String.valueOf(camel).isBlank()) {
            return String.valueOf(camel).trim();
        }
        Object snake = data.get(KEY_SNAKE);
        if (snake != null && !String.valueOf(snake).isBlank()) {
            return String.valueOf(snake).trim();
        }
        return "";
    }

    /** 是否已回收。 */
    public static boolean isRecovered(String terminal) {
        return RECOVERED.equals(terminal);
    }

    /** 是否有世界证据的确认丢失。 */
    public static boolean isConfirmedAbsent(String terminal) {
        return CONFIRMED_ABSENT.equals(terminal);
    }
}
