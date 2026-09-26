# 全链路巡检 · LINK-AUDIT（2026-09-26）

> 状态：TaskChain 复杂度拆解冻结（RddPlugin 1008→623，5 个独立门面/存储类）之后，进入**验证阶段**。
> 本文件：只读把完整数据链走一遍，逐节点标记验证状态，挑出未验证/高风险项。

**状态字典**：
- `[VERIFIED]` 已有真实 MC 证据
- `[TESTED]` 有单元/集成测试，但没真机
- `[CODE-ONLY]` 代码存在，没有测试证据
- `[UNCERTAIN]` 调用链存在，实际行为不确定
- `[DORMANT]` 目前没有生产调用路径

---

## 链路总览

```
/goal → RddPlugin.beginPlanning → StagePlanner → Decomposer/LLM → GoalSink → TaskChain
     → Detector.tickRuntime → 资产/风险门/依赖门/协商 → conditionMatches → completeSubtask → recordStageFact
     → Supervisor(nudge/publish) → Numen → Minecraft
     → 死亡: DEATH→onCompanionDeath→资产失效+LOST+BedAnchor → SPAWN→床边TP
     → 持久化: RddRuntimeStore save/restore → 重启恢复 RECOVERING
```

## 逐节点状态

| # | 链路节点 | 接线确认（文件:行） | 状态 | 证据 |
|---|---|---|---|---|
| 1 | `/goal` 接管 → `GoalSinks.register` → `beginPlanning` | `RddPlugin:83-90` | **[VERIFIED]** | V1/V2 /goal→进链实测 |
| 2 | `beginPlanning` → `StagePlanner.planStages` → `bindCurrent` → TaskChain | `RddPlugin:183-207` | **[VERIFIED]** | V1/V2 规划→绑定→推进 |
| 3 | Detector tick：无RT先缓存背包 → 有RT `tickRuntime` | `RddDetector:106-128` | **[VERIFIED]** | RL-1 规划看背包实测 |
| 4 | tickRuntime：背包扫→`riskGateAllows`→依赖门`activateCurrentFromSnapshot`→协商`tickNegotiation`→RECOVERING | `RddDetector:131-200` | 混合 | 依赖门/协商 **[VERIFIED]**；**风险门硬拦 [CODE-ONLY]** |
| 5 | 判定：`conditionMatches`→`completeSubtask`→`applyHardCoded`，累满→`applySupervisor`+`recordStageFact` | `RddDetector:519-553` | **[VERIFIED]** | V2 13×subtask_completed+6×dependency_met |
| 6 | Supervisor→Numen：`nudge` 注入 + `publishPlanningContext` | `RddDecomposer:64/101`, `RddPlugin:555+` | **[TESTED]** | 规划上下文物证在 VERIFY-CHECKLIST |
| 7 | 士兵上报：`report_task_concern`→`NegotiationInbox.accept`→`tickNegotiation` | `RddConcernTool:60`, `RddNegotiationInbox:31`, `RddDetector:407` | **[VERIFIED]** | V1 COUNTER→negotiation_handled |
| 8 | 身体执行：`RddBodyDispatcher.maybeSubmit` | `RddDetector:304`, `RddBodyDispatcher:36` | **[TESTED]** | 代码链通；真机由 Numen 原生驱动 |
| 9 | 死亡链路：DEATH→资产失效+埋点+LOST+`BedAnchor.record`→SPAWN→床边TP | `RddPlugin:103/110/376-410`, `RddBedAnchor:39/58` | **[VERIFIED]** | RL-6/RL-7 复活(9849.5,64,10164.5) |
| 10 | 持久化：`RddRuntimeStore.save/restore` + RECOVERING 恢复 | `RddDetector:108/128`, `RddRuntimeStore:43/68` | **[TESTED→真机待验]** | 原子写盘单测在 |
| 11 | 惰性展开：一级未展开→`GoalDriver.needExpansion` | `RddDetector:172`, `RddGoalDriver:51` | **[UNCERTAIN]** | 生产路径在，未逐段真机 |
| 12 | TaskChain 状态机（红线） | — | **[TESTED]** | 大规模单测 |

## 未验证项（按风险排序）

| 优先级 | 项 | 状态 | 验证场景 |
|---|---|---|---|
| 🔴 高 | **RL-8 风险门硬拦**（防死亡循环根因） | [CODE-ONLY] | NETHER/END 级 + 装备不足 → 期望 `primary_risk_gated` + 保持 WAITING 不派工 |
| 🟡 中 | **重启恢复闭环**（链路10） | [TESTED→真机待验] | 游戏内重启 → 链恢复 RECOVERING → 资产满足 early-achievement |
| 🟢 低 | **惰性展开边界**（链路11） | [UNCERTAIN] | 一级到达未展开 → GoalDriver 展开 → 继续 |

## 拆解冻结红线（本轮不动代码）
- `RddPlugin` 保持 623 行门面（B/A 簇有意保留，见 TASKCHAIN-DECOMPOSITION §三-b）
- TaskChain.java 不动（红线）
- 只做验证，不做美观性重构

---
*配 测试日志 090 · 提交链 08a8f279→258d8bd2*