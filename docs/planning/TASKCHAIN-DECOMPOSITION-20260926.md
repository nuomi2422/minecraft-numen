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

1. **D 资产门面**（最独立）→ `RddAssetFacade` ✅ `9aa007cf`
2. **E 事实门面** → `RddFactFacade` ✅ `f39a0882`
3. **F 重规划预算** → `RddReplanBudget` ✅ `e22113ce`
4. **C 死亡/生存**（床边复活锚点部分）→ `RddBedAnchor` ✅ `08a8f279`
5. **副簇：任务链持久化** → `RddRuntimeStore` ✅ `63d38b8c`
6. **B 状态渲染** / **A 规划编排** 🛑 **评估后保留在 RddPlugin**（见下方"保留理由"）

每步：**不破红线**（RL-1..7）、273 测全绿、独立 commit。

---

## 三-b、执行结果与保留理由

### 实际行数变化
- `RddPlugin`：**1008 → 623 行**（-385）
- 新增独立类：`RddAssetFacade`(262)、`RddFactFacade`(73)、`RddReplanBudget`(83)、`RddRuntimeStore`(75)

### B/A 簇保留理由（不是漏拆，是正确边界）
- **A 规划编排**（beginPlanning/decomposeSinglePass/bind/bindCurrent/requestReplan/remove/removeCurrent）：全部直接操作 `RUNTIMES`/`DECOMPOSING`/`CALLBACKS`/`BODY` 与链状态，**跨文件调用者在 Detector/GoalDriver/SkipTool/ConcernTool**，是核心状态机操作面。强拆 = 把一坨状态引用搬进新类，**收益 < 风险**（尤其 TaskChain 是红线不可动）。
- **B 状态/上下文渲染**（renderStateContext/withAssets/observationData/escape）：无跨文件调用者，纯内部；与 RUNTIMES/snapshot 紧耦合，强拆无收益。
- **C 剩余编排**（onCompanionDeath/isStarvingDeath/currentTaskId）：死亡事件编排骨架，已委托 Asset/BedAnchor，剩下的 51 行无独立数据。
- **原则**：拆"数据持有者"（本次 D/E/F/持久化），保留"状态编排者"在门面内——避免产生"传递 RUNTIMES 的伪门面"式更坏耦合。

### 文档 E 簇修正
原把 `saveRuntimes/restoreRuntimes` 归入 E 事实门面——**修正**：那是"任务链持久化"（rdd-tasks），与完成事实（rdd-facts）无关，已按独立副簇拆成 `RddRuntimeStore`。

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

---

## 七、维护说明（下一步接手的人/AI 必读）

### 本轮改动文件与职责
| 类 | 职责 | 关键静态方法 | 谁调它 |
|---|---|---|---|
| `RddAssetFacade`(新) | 资产门面：世界资产/背包缓存/历史/规划快照/落盘 | `assets/history/planningSnapshot/lastInventory/cacheInventory/saveAssets/recordHistory*` | RddPlugin/RddDetector/RddDecomposer/RddStagePlanner/RddAssetsTool |
| `RddFactFacade`(新) | 事实门面：完成阶段事实 | `facts/recordStageFact/recordSubtaskFact` | RddPlugin/RddDetector |
| `RddReplanBudget`(新) | 重规划预算：失败/协商独立上限 | `tryConsume/clearReplanCounts/clearWorldState` | RddPlugin.requestReplan |
| `RddRuntimeStore`(新) | 任务链持久化：rdd-tasks 原子写盘/重启恢复 | `save/restore`（传 live RUNTIMES 引用 + tasksDir） | RddPlugin(委托, 签名不变) |
| `RddBedAnchor`(早前) | 床边复活锚点 | `record/applyOnSpawn` | RddPlugin |

### 入口与依赖
- 所有门面在 `RddPlugin.setup` 里 `RddPlugin.xxx.init(configDir)` 初始化目录。
- `RddAssetFacade` / `RddFactFacade` / `RddReplanBudget` 都提供 `clearWorldState()`（ServerStopped 清内存）与 `remove(uuid)`（REMOVE 清单同伴），由 `RddPlugin.clearWorldState`/REMOVE 回调统一调用。
- `RddRuntimeStore.save/restore` 需要**live RUNTIMES Map 引用**（不复制），从 RddPlugin 传 `RUNTIMES`。

### 行为保证与红线
- 全部为**行为逐字不变**的重构（先搬后跑 273→314 测全绿）；红线 RL-1/2/6(资产)、RL-5(协商预算) 由对应门面承载。
- **不要**把 B/A 状态编排簇强行搬出（见 §三-b），会产生"传 RUNTIMES 的伪门面"更坏耦合。
- TaskChain.java 仍是红线，动它需专项+人工。
