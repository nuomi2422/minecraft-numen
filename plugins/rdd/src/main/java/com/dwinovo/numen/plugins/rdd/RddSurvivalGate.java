package com.dwinovo.numen.plugins.rdd;

/** 生存闸（2026-11-06）：内容层规则，供 planner 判断第一子任务是否必须是进食。纯 JVM。 */
final class RddSurvivalGate {

    private RddSurvivalGate() {}

    /**
     * @param hunger    饱食度 0..20（小于 10 触发）
     * @param hp        当前血量（小于 8 触发）
     * @param freeSlots 背包空槽数（小于等于 0 触发：先腾格再吃）
     */
    static boolean mustEatFirst(int hunger, double hp, int freeSlots) {
        return hunger < 10 || hp < 8.0 || freeSlots <= 0;
    }
}
