package com.dwinovo.numen.rdd.side.recovery;

import com.dwinovo.numen.rdd.side.SideTaskTrigger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 恢复支线的触发器：**由上层显式点名**（"这个同伴丢了东西，需要一条恢复支线"），
 * 不是按时间/天象自己冒出来的。
 *
 * <p>为什么不用周期判定：什么时候算"确认丢了"是重要度/需求那边的事（主人 2026-10-23 的说明），
 * 支线层只负责"被点名就开"。点名后由引擎在下一 tick 取走，避免重复开。
 */
public final class RecoverySideTaskTrigger implements SideTaskTrigger {

    /** companionId → 需要找回的物品（item / count）。取走即清除。 */
    private static final Map<UUID, Map<String, String>> PENDING = new ConcurrentHashMap<>();

    @Override
    public String typeId() {
        return RecoverySideTaskType.TYPE_ID;
    }

    /** 上层点名：这个同伴需要一条恢复支线，去把 {@code item} 取回 {@code count} 个。 */
    public static void expect(UUID companionId, String item, int count) {
        if (companionId == null) return;
        Map<String, String> data = new java.util.LinkedHashMap<>();
        data.put("item", item == null ? "" : item);
        data.put("count", Integer.toString(Math.max(1, count)));
        PENDING.put(companionId, data);
    }

    /** 取消点名（例如主线自己已经补回来了）。 */
    public static void withdraw(UUID companionId) {
        if (companionId != null) PENDING.remove(companionId);
    }

    /** 引擎每 tick 问一次；返回 true 时把负载带走（清除），由引擎 create 支线。 */
    @Override
    public boolean fires(Context ctx) {
        // 只**看**不取：真正的负载由 Type.create() 通过 take() 取走，
        // 否则引擎先问 fires 再 create，data 会在两步之间丢掉。
        return ctx != null && PENDING.containsKey(ctx.companionId());
    }

    /** 取走负载用：引擎 fires 之后调用，拿到 item/count。 */
    public static Map<String, String> take(UUID companionId) {
        Map<String, String> d = PENDING.remove(companionId);
        return d == null ? Map.of() : d;
    }
}
