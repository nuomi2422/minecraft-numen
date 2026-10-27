package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

/**
 * 读世界那一半：{@link RddNeedsText} 的事实来源。
 *
 * <p>只做两件事：把护甲槽/背包里的护甲比较一遍；把饱食度与可吃食物数数出来。
 * 任何异常都返回空串——增强块绝不能拖崩上下文构建。
 */
final class RddNeedsFacts {

    /** 护甲等级：皮革 &lt; 锁链 &lt; 铁 &lt; 金 &lt; 钻石 &lt; 下界合金。数值只用来比较。 */
    private static int tierOf(ItemStack s) {
        if (s == null || s.isEmpty() || !(s.getItem() instanceof ArmorItem armor)) return -1;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(s.getItem()).getPath();
        if (id.startsWith("netherite_")) return 6;
        if (id.startsWith("diamond_")) return 5;
        if (id.startsWith("golden_")) return 4;
        if (id.startsWith("iron_")) return 3;
        if (id.startsWith("chainmail_")) return 2;
        if (id.startsWith("leather_")) return 1;
        return armor.getDefense() > 0 ? 1 : -1;
    }

    private static String zhOf(ItemStack s) {
        return s.isEmpty() ? "" : s.getHoverName().getString();
    }

    private static String slotZh(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "头盔";
            case CHEST -> "胸甲";
            case LEGS -> "护腿";
            case FEET -> "靴子";
            default -> "护甲";
        };
    }

    static String blockFor(NumenPlayer body) {
        if (body == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(gearBlock(body));
        } catch (RuntimeException ignored) {
            // 增强块，跳过
        }
        try {
            sb.append(RddNeedsText.food(body.getFoodData().getFoodLevel(), countFood(body)));
        } catch (RuntimeException ignored) {
            // 增强块，跳过
        }
        return sb.toString();
    }

    /** 四个护甲槽逐个比：槽空或槽内更差、而背包里有更好的 → 只报**最该换的那一处**（避免刷屏）。 */
    private static String gearBlock(NumenPlayer body) {
        EquipmentSlot worst = null;
        int worstGain = 0;
        ItemStack worstItem = ItemStack.EMPTY;
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack worn = body.getItemBySlot(slot);
            int wornTier = tierOf(worn);
            ItemStack best = ItemStack.EMPTY;
            int bestTier = wornTier;
            for (int i = 0; i < body.getInventory().getContainerSize(); i++) {
                ItemStack s = body.getInventory().getItem(i);
                if (s.isEmpty() || !(s.getItem() instanceof ArmorItem armor)) continue;
                if (armor.getEquipmentSlot() != slot) continue;
                int t = tierOf(s);
                if (t > bestTier) {
                    bestTier = t;
                    best = s;
                }
            }
            if (!best.isEmpty() && bestTier > wornTier && bestTier - wornTier > worstGain) {
                worstGain = bestTier - wornTier;
                worst = slot;
                worstItem = best;
            }
        }
        if (worst == null) return "";
        ItemStack worn = body.getItemBySlot(worst);
        int count = 0;
        for (int i = 0; i < body.getInventory().getContainerSize(); i++) {
            ItemStack s = body.getInventory().getItem(i);
            if (!s.isEmpty() && s.getItem() == worstItem.getItem()) count++;
        }
        return RddNeedsText.gear(slotZh(worst), zhOf(worn), zhOf(worstItem), count);
    }

    /** 背包里"能直接吃"的件数（饿的时候只关心能不能立刻吃）。 */
    private static int countFood(NumenPlayer body) {
        int n = 0;
        for (int i = 0; i < body.getInventory().getContainerSize(); i++) {
            ItemStack s = body.getInventory().getItem(i);
            // 1.21：食物是数据组件，不是 Item 上的方法（getFoodProperties 需要 stack+player 两个参数）
            if (!s.isEmpty() && s.has(net.minecraft.core.component.DataComponents.FOOD)) {
                n += s.getCount();
            }
        }
        return n;
    }
}
