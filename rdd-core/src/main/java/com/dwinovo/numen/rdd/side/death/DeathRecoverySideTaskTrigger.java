package com.dwinovo.numen.rdd.side.death;

import com.dwinovo.numen.rdd.side.SideTaskTrigger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 死亡恢复支线的显式点名入口。
 *
 * <p>死亡事实先落台账，再由宿主把这一批掉落点名为真正的 SideTask。触发器不按墙钟
 * 自动确认损失：它只把事实负载交给支线类型，让执行 AI 判断「取备用后回收、直接回收、还是放弃」。
 */
public final class DeathRecoverySideTaskTrigger implements SideTaskTrigger {

    public static final String TYPE_ID = "death_recovery";
    private static final Map<UUID, Map<String, String>> PENDING = new ConcurrentHashMap<>();

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    /** 死亡支线是唯一允许在旧死亡占用标志存在时启动的支线。 */
    @Override
    public boolean allowedWhileDeathOccupying() {
        return true;
    }

    /** 点名一笔未闭合死亡批次；重复点名覆盖为最新事实，避免并行开两条驾驶支线。 */
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
        PENDING.put(companionId, data);
    }

    @Override
    public boolean fires(Context ctx) {
        return ctx != null && PENDING.containsKey(ctx.companionId());
    }

    public static Map<String, String> take(UUID companionId) {
        Map<String, String> data = PENDING.remove(companionId);
        return data == null ? Map.of() : data;
    }
}
