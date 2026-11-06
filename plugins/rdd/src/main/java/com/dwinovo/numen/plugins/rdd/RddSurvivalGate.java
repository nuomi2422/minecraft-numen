package com.dwinovo.numen.plugins.rdd;

/**
 * 生存闸（2026-11-06）：内容层三态判定，供 planner 决定第一子任务。纯 JVM。
 *
 * <p>为什么不把“背包满”并进进食：hunger 19、hp 满时只是包满了，插进食等于每轮白吃一次；
 * 真正需要缓冲区的场景是“要捡/要合成/要给食物”，所以满包只在有动作时升级为 MAKE_ROOM_FIRST。
 */
final class RddSurvivalGate {

    private RddSurvivalGate() {}

    enum Decision { EAT_FIRST, MAKE_ROOM_FIRST, NONE }

    /**
     * @param hunger         饱食度 0..20
     * @param hp             当前血量
     * @param freeSlots      背包空槽数
     * @param needsSlotAction 当前是否有待捡/待合成/待给食物的动作（决定满包是否要腾格）
     */
    static Decision evaluate(int hunger, double hp, int freeSlots, boolean needsSlotAction) {
        if (hunger < 10 || hp < 8.0) return Decision.EAT_FIRST;
        if (freeSlots <= 0 && needsSlotAction) return Decision.MAKE_ROOM_FIRST;
        return Decision.NONE;
    }
}
