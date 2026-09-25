# RDD 架构现状 · 数据流底稿（2026-09-25 固化）

> 用途：给人工画"数据流图"当**真实实现参照**（逐条对代码，不是理想图）。
> 结论口径：以下每个模块/边都是**代码里实际存在**的；"是否存在断线/假连接"需逐条验证（见文末清单）。

---

## 0. 顶层模块（代码实际）

```
minecraft-numen/
├── api/        (公开 API：NumenTool/CompanionEvent/NumenApi 等)
├── ai/         (LLM 接入)
├── ui/
├── core/       (NeoForge 引擎：假玩家、任务派发、路径、工具实现)
├── rdd-core/   (RDD 纯逻辑：无 Minecraft 依赖，可单测)
└── plugins/
    ├── rdd/          ← 当前主战场（任务链）
    ├── selfcompile/  自变异
    ├── experience/   经验层（← 疑似你说的"第二模块/学习"落点）
    ├── ac/ tlm/ ysm
```

## 1. rdd-core 包结构（纯 JVM，可单测）

| 包 | 内容 |
|---|---|
| `api` | Goal/PrimaryGoal/Subtask/Spec、Observation、Port（**SupervisorPort / TaskExecutorPort / ObservationPort** = 边界接口）、状态枚举 |
| `core` | `TaskChain`（状态机）、`RddRuntime`（运行时聚合）、`AssetRegistry`（世界资产事实）、`PlanningAssetSnapshot`（**规划唯一口径**）、`AssetHistory`（Lost≠Gone）、`AssetClaim`、`VillageNode`、`HardCodedEvaluator`、`InventoryGroups`、`WorldFactConditions`、`InstrumentationEvents`、`RddChainFactory` |
| `fact` | 完成事实继承：`CompletedFactStore` / `GoalLineage` / `StageFact` / `SubtaskFact` / `StageKeyNormalizer` |
| `fail` | 失败诊断与恢复：`FailureClassifier/Context/Event/Kind`、`RecoveryAction/Decision/Outcome/Plan/Policy`、`TaskNegotiation`（**双向协商**）、`TaskQualityReport`（只读上报） |
| `policy` | 决策：`RiskGate`/`RiskLevel`/`ResourceBudget`（**风险门**）、`AssetRole`/`AssetPurposeStore`/`AssetDerivation`、`TaskCostModel` |
| `replan` | 重规划：`ReplanContext` / `ReplanContextBuilder` |

## 2. plugins/rdd（接线层，Minecraft 依赖）

| 角色 | 文件 |
|---|---|
| **入口/门面** | `RddMod` → `RddPlugin`（setup/工具注册/事件订阅/状态贡献/静态门面） |
| **规划** | `RddStagePlanner`(Stage-A 一级)、`RddDecomposer`(Stage-B 二级/单遍兜底)、`RddPlanningKnowledge`(经验注入)、`RddPlanningPolicy`(策略块)、`RddRiskPlanning`(风险前置+waitFor)、`RddPlanGuard`(守门) |
| **驱动/检测** | `RddDetector`(每 tick 心跳：背包扫描→填缓存→依赖门→风险硬门→派工→判定→完成/失败/协商)、`RddGoalDriver`(懒展开)、`RddBodyDispatcher`/`RddBodyTools`(身体派发)、`RddStallWatcher`/`RddStallPolicy`(卡死)、`RddParkedWatcher`(停车守望)、`RddFurnaceWatch`(熔炉) |
| **资产** | `RddWorldAssetObserver`(世界资产观测)、`RddWorldFacts`(世界事实判定)、`RddAssetContext`(渲染)、`RddAssetStore`(世界资产落盘)、`RddHistoryStore`(资产历史落盘) |
| **协商/上报** | `RddConcernTool`(士兵回执工具)、`RddNegotiationInbox`(指挥官收件箱)、`RddTaskQualityReporter`(只读上报) |
| **工具** | `RddStatusTool`/`RddSubmitTool`/`RddSkipTool`/`RddAssetsTool`/`RddConcernTool` |
| **观测** | `RddMonitor`(rdd.jsonl)、`RddInstrumentation`(instrumentation.jsonl) |
| **其它** | `RddOptionalFood`、`RddSurplus`/`RddSurplusPolicy`、`RddTaskHandoff`、`RddCallbackGuard`、`RddKeys` |

---

## 3. 主数据流（代码实际路径）

```
/goal (玩家)
  │ GoalSinks.register → RddPlugin.beginPlanning
  ▼
Stage-A (RddStagePlanner.planStages)
  │  输入: planningSnapshot(实时背包[自刷新] + 注册表) + worldAssets + villageContext + recoverable + knowledge + riskPreHint
  │  输出: List<PrimarySpec>（一级主题 + waitFor）
  ▼
bindCurrent → TaskChain（一级清单；首个未展开）
  │
  ▼ (每 tick: RddDetector.tickRuntime)
背包实时扫描 → cacheInventory(无条件) → recordHistoryCurrent
  │
  ├─ 懒边界: 未展开 → RddGoalDriver.needExpansion → Stage-B
  │     Stage-B (RddDecomposer.decomposeSpecsWithHint) → List<SubtaskSpec> → expandCurrentPrimary
  ├─ 依赖门: activateCurrentFromSnapshot(planningSnapshot)   ← 实时背包口径
  ├─ 风险硬门: riskGateAllows(下界/末地 → RiskGate.checkWithRecovery)   ← 今日新增
  ├─ 派工: RddBodyDispatcher → 真实工具 onServerCall
  ├─ 判定: conditionMatches → HardCodedEvaluator / RddWorldFacts
  ├─ 完成: completeSubtask → recordStageFact → 下一级
  ├─ 失败: handleSubtaskFailure → FailureClassifier → RecoveryPolicy → (REPLAN|PARK)
  ├─ 卡死: RddStallWatcher
  └─ 协商: tickNegotiation ← RddConcernTool(士兵) → 改单/重规划
  ▼
观测: RddMonitor(rdd.jsonl) / RddInstrumentation / 监测台 8776
```

## 4. 三层资产（P2 定稿）

```
LiveAsset(实时)   : countInventory → lastInventory 缓存（不落盘）→ PlanningAssetSnapshot
WorldAsset(世界)  : RddWorldAssetObserver → AssetRegistry(world_*) → RddAssetStore 落盘
AssetHistory(战略): 死亡记 LOST / 观测记 CURRENT → RddHistoryStore 落盘 → recoverable 线索
```

*规划/依赖门唯一口径 = PlanningAssetSnapshot（实时扫描为持有真相；注册表只出 world_）。*

---

## 5. 你关心的"三模块"对照

| 模块 | 代码落点 | 状态 |
|---|---|---|
| **① 任务链（RDD）** | rdd-core + plugins/rdd | 主体已建；数据链修复中（本轮修了背包进规划/依赖门/风险硬门） |
| **② 学习/记忆/经验** | `plugins/experience` + `policy.AssetPurpose/AssetDerivation` + `knowledge/builtin-experience.jsonl` + `fail.TaskQualityReport` | **你说缺的"第二模块"**；expmem 语义检索**未接**（现仅词法） |
| **③ 自变异** | `plugins/selfcompile` + `config/selfcompile/mutations` | 你说"3模块已完成" |

## 6. 待逐条验证清单（画勾用）

任务链：
- [ ] /goal → Stage-A 一级清单生成
- [ ] 懒展开 → Stage-B 二级生成
- [ ] 背包进规划（held 块出现）
- [ ] 依赖门（实时背包口径）通过
- [ ] 风险硬门（下界/末地不达标被拦）
- [ ] 派工 → 真实工具执行
- [ ] 判定 → 完成推进
- [ ] 失败 → 分类 → 恢复/重规划
- [ ] 卡死监督
- [ ] 完成事实继承（跨重绑/重启）
- [ ] 死亡 → 资产失效 → 重扫
- [ ] 床边复活（绑床 → 死亡回床旁）
- [ ] 双向协商（士兵 REJECT/COUNTER → 指挥官改单）
- [ ] 重规划预算上限

监测台/观测：
- [ ] rdd.jsonl 事件齐全
- [ ] instrumentation.jsonl 埋点
- [ ] planning_input_verbatim 逐字落盘
- [ ] 8776 面板各页

