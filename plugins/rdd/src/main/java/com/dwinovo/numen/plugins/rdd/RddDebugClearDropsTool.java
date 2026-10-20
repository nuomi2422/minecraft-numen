package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code debug_clear_drops}：<b>验证专用</b>——清掉同伴附近地上的掉落物。
 *
 * <p><b>为什么需要它（用户 2026-10-20 明确批准加钩子）</b>：C1 的「死亡支线超时结账」
 * 分支（{@code repair_task_timed_out} + 台账 {@code CONFIRMED_LOST}）在真实游戏里
 * 几乎构造不出来 —— 同伴 42 秒就把掉落物捡回来了（V-20261020-17 实测两次都是成功路径），
 * 而没有传送/虚空工具能把她与掉落物分开。没有这个钩子，那条分支永远只有单测背书。
 *
 * <p><b>它做什么 / 不做什么</b>：
 * <ul>
 *   <li>只删除 {@code ItemEntity}（地上的掉落物），不碰方块、不碰背包、不碰台账；</li>
 *   <li>半径默认 16 格、上限 64；必须 {@code confirm=true}（与 {@code debug_kill} 同纪律，防误用）；</li>
 *   <li>只用于验证；正常游戏不要用。删除数量如实回报，不撒谎。</li>
 * </ul>
 *
 * <p>典型剧本：{@code debug_kill confirm=true} → 立刻 {@code debug_clear_drops confirm=true radius=24}
 * → 等 5 分钟 → 查 {@code repair_task_timed_out} 与 {@code rdd_death_ledger} 的 CONFIRMED_LOST。
 */
final class RddDebugClearDropsTool implements NumenTool {

    @Override public String name() { return "debug_clear_drops"; }

    @Override public String description() {
        return "Verification-only: delete dropped item entities on the ground within a radius of the companion "
                + "(default 16, max 64). Use it to construct the 'drops unreachable / destroyed' scenario for the "
                + "death-branch timeout path (repair_task_timed_out + ledger CONFIRMED_LOST), which cannot be "
                + "reproduced naturally because the companion retrieves its drops within about a minute. "
                + "It does NOT touch blocks, the companion's inventory, the death ledger or any task; it only "
                + "removes ItemEntity instances and reports exactly how many were removed. Requires confirm=true. "
                + "Never use it during normal play.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("confirm", "Must be true to execute (same guard as debug_kill).")
                .optionalInteger("radius", "Radius in blocks around the companion (1-64, default 16).", 1, 64)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            boolean confirm = args != null && args.has("confirm")
                    && "true".equalsIgnoreCase(args.get("confirm").getAsString());
            if (!confirm) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "debug_clear_drops refused: confirm=true required").toJson());
                return;
            }
            int radius = 16;
            if (args != null && args.has("radius") && !args.get("radius").isJsonNull()) {
                radius = Math.max(1, Math.min(64, args.get("radius").getAsInt()));
            }
            if (!(companion.level() instanceof ServerLevel level)) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "debug_clear_drops requires a server level").toJson());
                return;
            }
            AABB box = companion.getBoundingBox().inflate(radius);
            List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, box);
            int removed = 0;
            int stacks = 0;
            for (ItemEntity drop : drops) {
                stacks += drop.getItem().getCount();
                drop.discard();
                removed++;
            }
            reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                    "debug_clear_drops executed: " + removed + " item entities removed",
                    Map.of("entitiesRemoved", removed,
                            "itemTotal", stacks,
                            "radius", radius,
                            "position", companion.blockPosition().toShortString(),
                            "note", "verification hook; ledger and tasks untouched")).toJson());
        } catch (RuntimeException ex) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                    "debug_clear_drops failed: " + ex.getMessage()).toJson());
        }
    }
}
