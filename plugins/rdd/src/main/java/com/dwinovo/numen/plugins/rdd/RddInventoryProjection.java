package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import java.util.HashMap;
import java.util.Map;

/** Current inventory projections only; no cache, registry writes, or task progression. */
final class RddInventoryProjection {
    private RddInventoryProjection() {}

    /** 统计背包里每种物品的数量，用命名空间 ID（minecraft:oak_log）作 key。 */
    /**
     * 药水栈的预算键（非药水返回空串）。
     *
     * <p>映射规则收在纯函数 {@link com.dwinovo.numen.rdd.policy.AssetKeys#potionKey(String)} 里，
     * 便于离线单测；这里只负责从 ItemStack 取出效果 id。
     */
    private static String potionAssetKeyOf(net.minecraft.world.item.ItemStack stack) {
        try {
            if (!(stack.is(net.minecraft.world.item.Items.POTION)
                    || stack.is(net.minecraft.world.item.Items.SPLASH_POTION)
                    || stack.is(net.minecraft.world.item.Items.LINGERING_POTION))) {
                return "";
            }
            var contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
            if (contents == null) {
                return "";
            }
            var potion = contents.potion();
            if (potion == null || potion.isEmpty()) {
                return "";
            }
            String path = potion.get().unwrapKey()
                    .map(k -> k.location().getPath())
                    .orElse("");
            return com.dwinovo.numen.rdd.policy.AssetKeys.potionKey(path);
        } catch (RuntimeException ex) {
            // 取值失败不许影响整次盘点（少算一瓶药比让门崩掉好）
            return "";
        }
    }

    static Map<String, Integer> countInventory(NumenPlayer ap) {
        Map<String, Integer> counts = new HashMap<>();
        var inv = ap.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.merge(key, stack.getCount(), Integer::sum);
            // ★ 2026-10-31（166 B3）：药水的真实 id 是 minecraft:potion，
            //   而 ResourceBudget 要求的键是 minecraft:<effect>_potion。
            //   不补这一行，下界门要求的 fire_resistance_potion 永远计不到 → 高风险阶段永久拦停。
            String potionKey = potionAssetKeyOf(stack);
            if (!potionKey.isEmpty()) {
                counts.merge(potionKey, stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /**
     * 主背包区（可被拾取填充的那部分）剩余空格数。
     *
     * <p>只数前 {@link #MAIN_INV_SLOTS} 格：盔甲/副手槽（41-36）拾取塞不进去，
     * 把它们算成"空格"会高估可用空间，于是提醒永远不触发。
     */
    static int freeMainSlots(NumenPlayer ap) {
        var inv = ap.getInventory();
        int free = 0;
        int main = Math.min(MAIN_INV_SLOTS, inv.getContainerSize());
        for (int i = 0; i < main; i++) {
            if (inv.getItem(i).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /** 主背包区格数（36 = 9 快捷栏 + 27 主格）。 */
    static final int MAIN_INV_SLOTS = 36;
}
