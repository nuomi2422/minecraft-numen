package com.dwinovo.numen.rdd.side.death;

import com.dwinovo.numen.rdd.side.SideTaskTrigger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 死亡恢复支线的显式点名入口。
 *
 * <p>死亡事实先落台账，再由宿主把这一批掉落点名为真正的 SideTask。触发器不按墙钟
 * 自动确认损失：它只把事实负载交给支线类型，让执行 AI 判断「取备用后回收、直接回收、还是放弃」。
 *
 * <h2>为什么是队列而不是单槽（2026-10-30，V-20261030-13）</h2>
 *
 * <p>单槽时，第二具尸体在第一条还没被取走前就 {@code expect()} 会<b>覆盖</b>第一条：
 * 实机 seq3 的事实被 seq4 顶掉，seq3 永远没有自己的恢复支线。死亡是每批一条事实，
 * 所以这里按到达顺序排队，{@code take()} 一次消费一批。
 *
 * <h2>为什么不去重到"每个游戏日一次"</h2>
 *
 * <p>一天死两次就是两批不同的掉落；按日去重会把第二次静默吞掉（同一次实测 seq3/seq5 无支线）。
 * 死亡恢复的去重单位是 {@code deathSeq}，见 {@link #oncePerGameDay()}。
 */
public final class DeathRecoverySideTaskTrigger implements SideTaskTrigger {

    public static final String TYPE_ID = "death_recovery";

    /** 同一同伴最多排几批未取走的死亡事实：够用且不会无限涨（正常一拍照样取走）。 */
    private static final int MAX_PENDING = 8;

    private static final Map<UUID, Deque<Map<String, String>>> PENDING = new ConcurrentHashMap<>();

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    /** 死亡支线是唯一允许在旧死亡占用标志存在时启动的支线。 */
    @Override
    public boolean allowedWhileDeathOccupying() {
        return true;
    }

    /**
     * 死亡恢复不按游戏日去重：去重单位是 deathSeq（每批掉落一次）。
     * 按日去重会让同一天的第二、三次死亡永远拿不到恢复支线。
     */
    @Override
    public boolean oncePerGameDay() {
        return false;
    }

    /** 点名一笔未闭合死亡批次；同批重复点名覆盖同一 seq，不同 seq 排队。 */
    public static void expect(UUID companionId, int deathSeq, String dimension,
                              String deathAt, Map<String, Integer> lostItems) {
        if (companionId == null) return;
        Map<String, String> data = new LinkedHashMap<>();
        data.put("death_seq", Integer.toString(deathSeq));
        data.put("dimension", dimension == null ? "" : dimension);
        data.put("death_at", deathAt == null ? "" : deathAt);
        if (lostItems != null && !lostItems.isEmpty()) {
            StringBuilder items = new StringBuilder();
            for (Map.Entry<String, Integer> e : lostItems.entrySet()) {
                if (items.length() > 0) items.append(',');
                items.append(e.getKey()).append('=').append(Math.max(0, e.getValue()));
            }
            data.put("lost_items", items.toString());
        }
        PENDING.compute(companionId, (id, queue) -> {
            Deque<Map<String, String>> q = queue == null ? new ArrayDeque<>() : queue;
            // 同一 seq 重复点名（死亡链可能被调用两次）只保留最新事实，不排两条。
            q.removeIf(row -> data.get("death_seq").equals(row.get("death_seq")));
            q.addLast(data);
            while (q.size() > MAX_PENDING) {
                q.removeFirst();
            }
            return q;
        });
    }

    @Override
    public boolean fires(Context ctx) {
        if (ctx == null) return false;
        Deque<Map<String, String>> q = PENDING.get(ctx.companionId());
        return q != null && !q.isEmpty();
    }

    /** 取走最早一批未消费的死亡事实（一次一批；取空则清掉这个同伴的槽）。 */
    public static Map<String, String> take(UUID companionId) {
        if (companionId == null) return Map.of();
        Deque<Map<String, String>> q = PENDING.get(companionId);
        if (q == null) return Map.of();
        Map<String, String> head;
        synchronized (q) {
            head = q.pollFirst();
            if (q.isEmpty()) {
                PENDING.remove(companionId);
            }
        }
        return head == null ? Map.of() : head;
    }

    /** 还有几批没被消费（观测/测试用）。 */
    public static int pendingCount(UUID companionId) {
        Deque<Map<String, String>> q = PENDING.get(companionId);
        return q == null ? 0 : q.size();
    }

    /** 测试用：清空排队。 */
    public static void resetForTest() {
        PENDING.clear();
    }
}
