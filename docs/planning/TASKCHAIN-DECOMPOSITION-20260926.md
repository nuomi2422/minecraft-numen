# 任务链复杂度拆解 · 边界图（2026-09-26）

> 目标：把"一坨"（尤其 `RddPlugin` 1008 行）按**职责边界**拆清，**降低管理复杂度**。
> 原则：**先列边界、后移动代码**；拆解不得破坏基准红线；优先低风险、可回滚。

---

## 一、复杂度热点（实测行数）

| 文件 | 行数 | 性质 |
|---|---|---|
| `plugins/rdd/RddPlugin.java` | **1008** | 门面 + 大量静态逻辑混一起（最大热点） |
| `plugins/rdd/RddDetector.java` | 647 | 每 tick 心跳（检测+派工+判定+协商+失败） |
| `plugins/rdd/RddDecomposer.java` | 464 | Stage-B 规划 + 单遍兜底 + LLM 调用 |
| `rdd-core/core/TaskChain.java` | 630 | 状态机核心（**红线，慎动**） |
| `plugins/rdd/RddStagePlanner.java` | 238 | Stage-A 规划 |

---

## 二、`RddPlugin`（1008 行）职责簇 → 拆解边界

按实际方法扫描，可切成 **6 个职责**：

| 簇 | 内容 | 建议归属 | 风险 |
|---|---|---|---|
| **A. 规划编排** | `beginPlanning` / `decomposeSinglePass` / `bindCurrent` / `requestReplan` | `RddPlanningFlow` | 中 |
| **B. 状态/上下文渲染** | `renderStateContext` / `withAssets` / `observationData` / `publishTaskSnapshot` | `RddStateContext` | 低 |
| **C. 死亡/生存处理** | `onCompanionDeath` / `recordDeathLostHistory` / `recordSelfBedAnchor` / `isStarvingDeath` / `bedStandPos` / 床边复活 SPAWN | `RddSurvivalHandler` | 低 |
| **D. 资产门面** | `assets` / `history` / `saveHistory` / `recordHistory*` / `recoverableContext` / `planningSnapshot` / `cacheInventory` / `lastInventory` | `RddAssetFacade` | 低 |
| **E. 事实门面** | `facts` / `recordStageFact` / `recordSubtaskFact` / `saveRuntimes` / `restoreRuntimes` | `RddFactFacade` | 低 |
| **F. 重规划预算** | `requestReplan`(预算部分) / `REPLAN_COUNTS` / `MAX_*` / `clearReplanCounts` | `RddReplanBudget` | 低 |
| **G. 协商接线** | `tickNegotiation` 相关（在 Detector） | `RddNegotiationInbox`(已有) | 低 |

> 保留 `RddPlugin` 作为**门面 + setup 注册 + 静态转调**（薄），把 A–F 逻辑搬出。

---

## 三、拆解顺序（低风险优先，每步可回滚 + 跑全测）

1. **D 资产门面**（最独立）→ `RddAssetFacade`
2. **E 事实门面** → `RddFactFacade`
3. **F 重规划预算** → `RddReplanBudget`
4. **C 死亡/生存** → `RddSurvivalHandler`
5. **B 状态渲染** → `RddStateContext`
6. **A 规划编排** → `RddPlanningFlow`（最后，最核心）

每步：**不破红线**（RL-1..7）、273 测全绿、独立 commit。

---

## 四、`RddDetector`（647 行）职责簇

| 簇 | 内容 | 建议 |
|---|---|---|
| 心跳主循环 | `tickRuntime` 骨架 | 保留 |
| 依赖门/风险门 | `riskGateAllows` / `hasBoundRespawn` | → `RddGate` |
| 判定/推进 | `conditionMatches` / `completeSubtask` / `applyHardCoded` | → `RddJudge` |
| 失败处理 | `handleSubtaskFailure` / `maybeRetryOrFail` | 已部分在 `fail/*` |
| 协商处理 | `tickNegotiation` | → `RddNegotiationInbox`(已有) |
| 资产 populate | `populateAssets` / `countInventory` | → `RddAssetFacade` |
| 熔炉/停车/卡死 | 已拆 `RddFurnaceWatch`/`RddParkedWatcher`/`RddStallWatcher` | ✅ 已完成 |

---

## 五、边界契约对应（对齐七核心宪法）

| 拆解产物 | 归属核心 |
|---|---|
| `RddPlanningFlow` / `RddStagePlanner` / `RddDecomposer` | TaskChain（规划侧） |
| `RddJudge` / `RddGate` / `RddReplanBudget` | TaskChain（执行判定侧） |
| `RddAssetFacade` | 资产（TaskChain 输入） |
| `RddSurvivalHandler` | 生存（Numen 身体事件） |
| `RddMonitor` / `RddInstrumentation` | 监测台 |
| `RddNegotiationInbox` / `RddConcernTool` | Numen ↔ Supervisor 协商 |

---

## 六、铁律
- **不破基准红线**（`BASELINE-REDLINE-1-20260925.md`）；任一步破 = 回滚。
- **不违架构宪法**（`ARCHITECTURE-CONSTITUTION-7CORE.md`）。
- **每步独立 commit + 全测 + 可回滚**。
- **TaskChain.java 核心状态机不在本轮拆**（红线，需专项+人工）。
