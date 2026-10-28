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
    /** 主手是不是武器（剑/斧/弓/三叉戟/重锤）；空手不算。 */
    private static boolean isWeapon(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        var it = s.getItem();
        return it instanceof net.minecraft.world.item.SwordItem
                || it instanceof net.minecraft.world.item.AxeItem
                || it instanceof net.minecraft.world.item.BowItem
                || it instanceof net.minecraft.world.item.TridentItem
                || it instanceof net.minecraft.world.item.MaceItem;
    }

    /** 武器强弱（用来挑最好的一把）：钻石 4 / 铁 3 / 石 2 / 金 1 / 木 0；远程优先。 */
    private static int weaponScore(ItemStack s) {
        if (!isWeapon(s)) return -1;
        var it = s.getItem();
        if (it instanceof net.minecraft.world.item.BowItem) return 10;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it).getPath();
        int tier = id.startsWith("netherite_") ? 5
                : id.startsWith("diamond_") ? 4
                : id.startsWith("iron_") ? 3
                : id.startsWith("stone_") ? 2
                : id.startsWith("golden_") ? 1 : 0;
        return tier;
    }

    /**
     * 战斗装备提示（2026-10-28 实机 #235：她手上下界岩、包里没剑，却被打发去打烈焰人）。
     * 主手不是武器 → 包里有就更换、没有就现做一把。**只给事实，不替她换。**
     */
    private static String weaponBlock(NumenPlayer body) {
        ItemStack main = body.getMainHandItem();
        if (isWeapon(main)) return "";
        ItemStack best = ItemStack.EMPTY;
        int bestScore = -1;
        for (int i = 0; i < body.getInventory().getContainerSize(); i++) {
            ItemStack s = body.getInventory().getItem(i);
            int score = weaponScore(s);
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        String held = main.isEmpty() ? "空手" : "「" + main.getHoverName().getString() + "」";
        if (!best.isEmpty()) {
            return "<gear_hint>你现在是" + held + "，不是武器；背包里有「"
                    + best.getHoverName().getString() + "」。要打架/打怪之前先 rdd_equip 把它换到主手"
                    + "——空手或者拿方块打，伤害差好几倍。（护甲是另一回事，别混）</gear_hint>";
        }
        return "<gear_hint>你现在是" + held + "，不是武器，背包里也没有武器。"
                + "遇到要战斗的目标（烈焰人/末影人/猪灵/守卫）先弄一把剑（石剑→铁剑→钻石剑），"
                + "别空手贴上去——下界尤其亏（火球是远程的）。</gear_hint>";
    }
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
        // ★ 2026-10-28 实机（#235）：她**手里拿着一块下界岩**、背包里一把剑都没有，
        //   却被派去杀 7 只烈焰人。原来这里只比护甲、**从不看主手**——
        //   "装备没穿完"被我实现成了"护甲没穿满"，漏了武器。这里补一支（只提示，不替她换）。
        String weapon = weaponBlock(body);
        if (!weapon.isEmpty()) {
            return weapon;
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
