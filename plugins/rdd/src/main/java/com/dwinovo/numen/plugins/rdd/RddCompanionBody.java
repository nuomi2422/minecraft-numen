package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「这只同伴的身体」缓存：拿 NumenPlayer 实例的唯一可靠来源。
 *
 * <h2>为什么不能只靠玩家列表（2026-10-25 实机踩到）</h2>
 * 我原来用 {@code server.getPlayerList().getPlayer(uuid)} 取身体来扫箱子，
 * 结果 {@code level.getBlockEntity()} 一直返回 null —— 而同坐标下 MCP 的
 * {@code inspect_block_storage} 能正常读出箱内物品。
 * 两者唯一的区别是**取到的是不是同一个实体/同一个 level 实例**。
 * 检测器每 tick 拿到的是真身体（{@code matches(ap, ...)} 那个 ap），
 * 这里把它记下来，需要"读世界"的地方一律用这个实例。
 */
final class RddCompanionBody {

    private static final Map<UUID, NumenPlayer> BODIES = new ConcurrentHashMap<>();

    private RddCompanionBody() {}

    static void remember(NumenPlayer body) {
        if (body == null) return;
        BODIES.put(body.getUUID(), body);
    }

    static NumenPlayer get(UUID uuid) {
        return uuid == null ? null : BODIES.get(uuid);
    }

    static void clear(UUID uuid) {
        if (uuid != null) BODIES.remove(uuid);
    }

    static void clearAll() {
        BODIES.clear();
    }
}