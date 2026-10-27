package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「你正在被打」——把敌怪与血量事实**主动推到执行上下文**（2026-10-27，D-01/D-02 的修法）。
 *
 * <h2>为什么要有它（证据，不是猜测）</h2>
 * `exp-session-stats` 扫最近 3 天 52 次死亡，死前一刻在做什么：
 * <pre>
 *   19:44 unknown  ← rdd_get_inventory
 *   19:44 骷髅     ← task_status
 *   19:46 骷髅     ← rdd_whereami
 *   19:43 骷髅     ← eat
 *   19:45 骷髅     ← eat
 * </pre>
 * 也就是说：**它被射的时候在查背包/查任务状态/吃东西**，而不是在打或跑。
 * 这与 #340「1783 次调用里 54% 是 rdd_get_inventory + task_status」互证——
 * 不是它不想跑，是**上下文里没有"你现在有危险"这条事实**，于是轮询比活命优先。
 *
 * <h2>设计纪律（与既有上下文块同口径）</h2>
 * <ul>
 *   <li><b>只读、只陈述</b>：不改判定、不派工、不建议具体动作（动作归执行 AI）；</li>
 *   <li><b>有界</b>：敌怪最多列 3 种、总长 &lt; 260 字；没有危险时返回空串（不占 token）；</li>
 *   <li><b>可测</b>：措辞与阈值在 {@link RddDangerText}（纯函数），本类只负责读世界；</li>
 *   <li><b>不制造假警报</b>：远处一只僵尸、满血 → 不弹块。</li>
 * </ul>
 */
final class RddDangerFacts {

    private RddDangerFacts() {}

    /** 从真实世界取事实；读不到就返回空串（不做假警报，也不编数）。 */
    static String blockFor(NumenPlayer body) {
        try {
            if (body == null || !(body.level() instanceof ServerLevel level)) {
                return "";
            }
            AABB box = body.getBoundingBox().inflate(RddDangerText.SCAN_RADIUS);
            Map<String, Integer> kinds = new LinkedHashMap<>();
            int hostiles = 0;
            int close = 0;
            for (Mob mob : level.getEntitiesOfClass(Mob.class, box)) {
                if (!(mob instanceof Monster) || !mob.isAlive()) continue;
                hostiles++;
                if (mob.distanceTo(body) <= RddDangerText.CLOSE_RADIUS) close++;
                String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(mob.getType()).getPath();
                kinds.merge(id, 1, Integer::sum);
            }
            if (hostiles == 0) {
                return "";
            }
            StringBuilder kindText = new StringBuilder();
            int shown = 0;
            for (Map.Entry<String, Integer> e : kinds.entrySet()) {
                if (shown == 3) break;
                if (kindText.length() > 0) kindText.append('、');
                kindText.append(e.getKey());
                if (e.getValue() > 1) kindText.append('×').append(e.getValue());
                shown++;
            }
            int pct = Math.round(body.getHealth() / Math.max(1f, body.getMaxHealth()) * 100f);
            return RddDangerText.block(hostiles, close, pct, kindText.toString());
        } catch (RuntimeException ex) {
            return "";   // 增强块，绝不能拖崩上下文构建
        }
    }
}
