package com.dwinovo.numen.plugins.rdd;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 2026-11-07（V15 断点）：farm_nearby/farm_cell 直接 add 入包不触发 ITEM_PICKED_UP，
 * 使 item_picked_up 任务条件永不计工具收割。这里统一：按“入包前-入包后”件数补记统计，
 * 只记真正进入背包的数量；背包满落地的残余等以后被玩家捡起再由原版计数（不重复计）。
 */
final class FarmHarvestSupport {
    private FarmHarvestSupport() {}

    /** 纯算术：真正被背包接收的件数（可为 0，不做 boolean 全有/全无）。 */
    static int accepted(int before, int after) {
        return Math.max(0, before - after);
    }

    /** 收割一件掉落：入包多少记多少；未入包部分落地。返回入包件数。 */
    static int give(ServerPlayer player, ItemStack drop) {
        if (player == null || drop == null || drop.isEmpty()) return 0;
        int before = drop.getCount();
        boolean all = player.getInventory().add(drop);
        int after = drop.getCount();
        int accepted = accepted(before, after);
        if (accepted > 0) {
            player.awardStat(Stats.ITEM_PICKED_UP.get(drop.getItem()), accepted);
        }
        if (!all && !drop.isEmpty()) {
            player.drop(drop, false);
        }
        return accepted;
    }
}
