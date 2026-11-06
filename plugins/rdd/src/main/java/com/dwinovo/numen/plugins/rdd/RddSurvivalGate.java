package com.dwinovo.numen.plugins.rdd;

/**
 * 生存闸（2026-11-06）：内容层判定，供 planner 决定第一子任务。纯 JVM。
 *
 * <p>为什么不把“背包满”并进进食：hunger 19、hp 满时只是包满了，插进食等于每轮白吃一次；
 * 真正需要缓冲区的场景是“要捡/要合成/要给食物”，所以满包只在有动作时升级为 MAKE_ROOM_FIRST。
 *
 * <p>★ 2026-11-06 21:44 实机补：`EAT_FIRST` 原先不看“包里到底有没有能吃的东西”。实测她 hunger 6、
 * 包里只有 1 小麦 + 13 种子（没有可直接吃的），闸每分钟命中一次、每次都插“去吃”，
 * 结果**永远做不完**——harness 日志 `生存抢占 EAT_FIRST hunger=6 hp=20.0 free=11` 连续刷。
 * 所以补 {#SEEK_FOOD_FIRST}：没吃的就不是“去吃”，而是“先弄到吃的”（收割成熟作物→3麦合成面包→
 * 否则打猎/钓鱼），否则闸会变成空转源。
 */
final class RddSurvivalGate {

    private RddSurvivalGate() {}

    enum Decision { EAT_FIRST, SEEK_FOOD_FIRST, MAKE_ROOM_FIRST, NONE }

    /** 兼容旧调用：不知道有没有吃的时，按“有吃的”处理（老的 EAT_FIRST 语义）。 */
    static Decision evaluate(int hunger, double hp, int freeSlots, boolean needsSlotAction) {
        return evaluate(hunger, hp, freeSlots, needsSlotAction, true);
    }

    /**
     * @param hunger          饱食度 0..20
     * @param hp              当前血量
     * @param freeSlots       背包空槽数
     * @param needsSlotAction 当前是否有待捡/待合成/待给食物的动作（决定满包是否要腾格）
     * @param hasEdibleFood   背包里有没有**可直接吃**的食物（生肉/熟肉/面包/作物/鱼等）
     */
    /** 可直接吃的物品白名单（生肉在 MC 里也能吃，所以生熟都收）。 */
    static final java.util.Set<String> EDIBLE = java.util.Set.of(
            "minecraft:bread", "minecraft:baked_potato", "minecraft:potato", "minecraft:carrot",
            "minecraft:beef", "minecraft:cooked_beef", "minecraft:porkchop", "minecraft:cooked_porkchop",
            "minecraft:chicken", "minecraft:cooked_chicken", "minecraft:mutton", "minecraft:cooked_mutton",
            "minecraft:cod", "minecraft:cooked_cod", "minecraft:salmon", "minecraft:cooked_salmon",
            "minecraft:apple", "minecraft:golden_apple", "minecraft:melon_slice", "minecraft:sweet_berries",
            "minecraft:cookie", "minecraft:rabbit", "minecraft:cooked_rabbit", "minecraft:beetroot",
            "minecraft:beetroot_soup", "minecraft:mushroom_stew", "minecraft:rabbit_stew");

    /** 背包计数里有没有任何一种可直接吃的食物。 */
    static boolean hasEdibleFood(java.util.Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) return false;
        for (String id : EDIBLE) {
            Integer n = counts.get(id);
            if (n != null && n > 0) return true;
        }
        return false;
    }

    static Decision evaluate(int hunger, double hp, int freeSlots, boolean needsSlotAction,
                             boolean hasEdibleFood) {
        if (hunger < 10 || hp < 8.0) {
            return hasEdibleFood ? Decision.EAT_FIRST : Decision.SEEK_FOOD_FIRST;
        }
        if (freeSlots <= 0 && needsSlotAction) return Decision.MAKE_ROOM_FIRST;
        return Decision.NONE;
    }
}
