package com.dwinovo.numen.agent.retrieval;

import java.util.List;

/**
 * G5a（75 号 §15.5 / §15.8-B4，2026-10-21 owner 指令「先做离线逻辑」）最小统一检索契约。
 *
 * <p>这是**纯新增契约**：不向 `NumenApi` 加无默认实现的抽象方法，不碰旧 planning/patch
 * 贡献路径；全部 record 纯 JVM 可离线单测（owner 直接点名）。实机/部署/接 host 全在后续批
 * （G5b/G5c、或一只「接 supply 单独一卡」）——本批不做。
 *
 * <p>画像：PLANNER / EXECUTOR / LEARNER 三种视图不同（IC08）。
 */
public enum RetrievalRole { PLANNER, EXECUTOR, LEARNER }
