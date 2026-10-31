package com.dwinovo.numen.plugins.learner.blocks;

/**
 * 环境块的种类（158 §4 的十一类）。
 *
 * <h2>为什么要枚举，而不是让块自己带一个字符串 kind</h2>
 * 「学习者到底能读取哪些类别的环境事实」是一个可审计的清单。
 * 字符串会让清单漂移（{@code inventory} / {@code Inventory} / {@code inv} 各建一类），
 * 而漂移之后「这个样本覆盖了哪些块」就永远统计不准。
 * 枚举让「缺哪一类块」变成编译期能看见的事。
 *
 * <h2>为什么 ORDER 的顺序是固定的</h2>
 * 目录要能稳定排序：两次打开同一个样本，块的出现顺序必须一样，
 * 否则「翻页」和「前后对比」都失去意义。
 *
 * <p>纯 JVM，无 MC/NUMEN 依赖。
 */
public enum BlockKind {

    /** 样本摘要：这次事故的一句话概览（158 里唯一没有现成数据源的一类）。 */
    SAMPLE_SUMMARY("样本摘要"),

    /** 任务链镜像：目标、节点、尝试、基线。 */
    TASK_STATE("任务状态"),

    /** 世界时间：白天/夜晚、游戏刻。 */
    WORLD_TIME("世界时间"),

    /** 位置：维度、坐标、脚下/眼高。 */
    LOCATION("位置"),

    /** 威胁：附近敌对实体与危险源。 */
    ENTITY_THREAT("威胁"),

    /** 身体状态：血量、饥饿、效果、装备。 */
    BODY_STATE("身体状态"),

    /** 背包与容器。 */
    INVENTORY("背包"),

    /** 动作轨迹：最近若干条事件，带时间序。 */
    ACTION_TRACE("动作轨迹"),

    /** 决策上下文：<b>执行者当时真正收到的那段内容</b>（不是事后重建的日志）。 */
    DECISION_CONTEXT("决策上下文"),

    /** 检索轨迹：这一轮召回了什么、裁掉了什么。 */
    RETRIEVAL_TRACE("检索轨迹"),

    /** 结果：世界前后变化、任务终态。 */
    OUTCOME("结果");

    private final String zh;

    BlockKind(String zh) {
        this.zh = zh;
    }

    /** 中文名（面板/提示词用）。 */
    public String zh() {
        return zh;
    }
}
