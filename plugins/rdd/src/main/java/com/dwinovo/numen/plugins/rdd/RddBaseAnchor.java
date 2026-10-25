package com.dwinovo.numen.plugins.rdd;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「基地锚点」：这条链的建造类判定以**基地**为圆心，而不是以**会走动的同伴**为圆心。
 *
 * <h2>为什么要有它（2026-10-25 实机，主人当场指出）</h2>
 * 规划器给「把床放进村里大屋（基地）」「在基地大屋固定位置放下箱子」写的是**文字**，
 * 而机器条件只有 {@code {type:block_nearby, block:white_bed, radius:8}} —— **没有 anchor**。
 * 于是判定圆心 = 同伴当前位置 ⇒ 它在哪放下就算哪，**"回基地再办"这句话对判据完全无效**，
 * 箱子被就近放在离床很远的地方，链照样判 COMPLETED。
 *
 * <h2>规则（刻意简单、可解释）</h2>
 * <ol>
 *   <li>**第一次成功放下床**的位置 = 基地坐标（床是"安家"的物理标志）；</li>
 *   <li>之后所有没有显式 {@code anchor} 的建造类条件（{@code block_nearby} / {@code container_nearby}）
 *       自动以基地为圆心；</li>
 *   <li>没有基地坐标时**不动**（回落当前位置）——探索/采集类任务本来就不该被基地绑住。</li>
 * </ol>
 *
 * <p>不跨存档持久化：这是"这条链的基地"，重启后由新链重新确认（避免把旧档的坐标套到新档）。
 */
final class RddBaseAnchor {

    private static final Map<UUID, BlockPos> ANCHORS = new ConcurrentHashMap<>();

    private RddBaseAnchor() {}

    static void remember(UUID companionId, BlockPos pos) {
        if (companionId == null || pos == null) return;
        ANCHORS.put(companionId, pos.immutable());
    }

    static BlockPos get(UUID companionId) {
        return companionId == null ? null : ANCHORS.get(companionId);
    }

    static void clear(UUID companionId) {
        if (companionId != null) ANCHORS.remove(companionId);
    }

    static void clearAll() {
        ANCHORS.clear();
    }

    /** 测试/诊断用：当前有几个同伴记着基地。 */
    static int size() {
        return ANCHORS.size();
    }

    /** 条件是否需要基地锚点（建造类），以及它是否已经显式给了 anchor。 */
    static boolean needsAnchor(Map<String, Object> condition) {
        if (condition == null) return false;
        if (condition.get("anchor") instanceof java.util.List<?> list && list.size() == 3) return false;
        Object type = condition.get("type");
        return "block_nearby".equals(type) || "container_nearby".equals(type);
    }
}
