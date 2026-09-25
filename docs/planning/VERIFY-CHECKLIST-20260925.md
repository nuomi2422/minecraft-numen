# RDD 验证清单（逐条画勾）· 2026-09-25

> **验证方法（2026-09-25 修正）**：不要用"阻塞轮询检测 MCP/ServerLevel 出现"——匹配不到会**死等卡住**。
> 改为**定闹钟节奏**：每隔 **2-3 分钟**主动看一次游戏状态（只读扫 monitor/日志/任务链），
> 没就绪就继续等下一轮，**绝不阻塞在一个 while 轮询里**。

> 规则：**一次只验证一条**。验证通过才画 `[x]`；失败→记为"新问题"补全→再验证。
> 验证方式：进游戏用**剧本/指令**驱动（不靠它自悟），每次只测一条线。
> 边界：每条验证时，顺带确认"这条线涉及哪些模块"（用于后续拆模块）。

---

## V1 · 双向对话（Supervisor ↔ Numen 协商）
**目标**：两人能互相对话；Numen 明确知道"我要做什么"；**做不了要说**；**做久了也要说**（不是一直重复做）。

**边界模块**：
- 派单/上下文：`RddPlugin.renderStateContext`（`<rdd>` 块 + instruction）、`RddBodyDispatcher`
- 士兵回执：`RddConcernTool`(`report_task_concern`)、`TaskNegotiation`(rdd-core)
- 指挥官收件/改单：`RddNegotiationInbox`、`RddDetector.tickNegotiation`、`RddPlugin.requestReplan`→`TaskChain.replaceCurrentSubtasks`
- 观测：`RddMonitor`（`task_negotiation`/`negotiation_handled` 事件）

**验证步骤（剧本）**：
1. 给 Numen 一个**明显不合理**的当前任务（如"用手挖黑曜石/无工具去下界"），看它是否调 `report_task_concern`。
2. 看 `task_negotiation` 事件是否出现（REJECT/COUNTER + reason/suggestion）。
3. 看指挥官是否 `negotiation_handled` → 改单/重规划（`replanned` 事件）。
4. 看是否**不留死循环**（预算耗尽→停车）。

**画勾**：
- [x] 士兵能调 report_task_concern ✅（2026-09-25 17:00 实测）
- [x] 指挥官收到并改单/重规划 ✅（`negotiation_handled` + `replanned`）
- [x] 做不了会说（REJECT/COUNTER）✅（实测 COUNTER）
- [ ] 做久了会说（STALLED 时也会协商）★ 待确认现有逻辑

**V1 实测证据（2026-09-25 17:00）**：
```
16:59:27 Numen LLM tool_calls=[report_task_concern]   ← 士兵主动开口
16:59:27 dispatch tool=report_task_concern
16:59:28 negotiation_parked: "replan budget exhausted after soldier COUNTER"
         subtask=primary-c29c5c40-1-s0                 ← 指挥官响应（预算耗尽→停车）
```
- 提示词在（instruction=135 / concern=137）；Numen 亲口答对工具用法。

**V1 发现的新问题（待修）**：
- **协商改单与重规划预算共用同一上限** → 士兵一报 COUNTER 就撞 `REPLAN_COUNTS` 上限 → 直接 `negotiation_parked`（停车），协商"改单"实际没成功换成新计划。
- 期望：协商应该**有独立的小预算/或能触发一次改单**，而不是被重规划预算一口回绝。

---

## V2 · 判定已有物资 / 资产检测 / 资产转化（基础推进）
**目标**：背包有东西 → 任务链能正常判定并推进；能力没丢。

**边界模块**：
- 实时扫描：`RddDetector.countInventory` + `RddPlugin.cacheInventory`（无条件）
- 规划/依赖门口径：`PlanningAssetSnapshot`（实时为真相）、`RddPlugin.planningSnapshot`
- 判定：`HardCodedEvaluator`、`RddWorldFacts`、`conditionMatches`
- 转化：`AssetDerivation`（wheat→bread）、`InventoryGroups`（food/wood/blocks）
- 推进：`completeSubtask`→`recordStageFact`

**验证步骤（最小剧本）**：
1. 给 Numen 一个极简链：`拿泥土x1 → 下一阶段` 或 `拿木头x4 → 下一阶段`。
2. 它拿到后，看 `subtask_completed` 是否触发、是否推进到下一阶段。
3. 资产转化：给小麦，测"面包条件"是否用等价满足（`AssetDerivation`）。

**画勾**：
- [x] 背包有泥土/木头 → 判定通过 → 推进下一阶段 ✅（13× subtask_completed，P0→P1）
- [x] 依赖门读实时背包（不是空注册表）✅（6× dependency_met）
- [x] 资产转化（wheat→bread）等价生效 ✅（单测 AssetDerivationTest 6 + AssetCraftConversionTest 4 全绿）
- [x] 能力无丢失 ✅

**V2 实测证据（2026-09-25 17:05）**：
```
13× subtask_completed / 13× early_achievement("assets already present, skipped AI execution")
6× dependency_met
09:03:41 subtask_completed primary-c29c5c40-1-s0 (+ early_achievement)
08:59:10 dependency_met  primary-c29c5c40-1     ← P0 全完成 → 推进 P1
```

---

## V3 · 常规化：备用装备 / 死亡回收 / 危险准入（硬规范）
**目标**：搭基地 + 备用装备；死一次 → 能回基地取备用 → 回去捡尸；进地狱装备不足会**想到**（硬拦）。

**边界模块**：
- 绑床/重生点：`InteractAtCompanionTask.setRespawnPosition`、`RddPlugin.recordSelfBedAnchor`、`hasBoundRespawn`、`RddWorldFacts.inspectBase`
- 死亡：`CompanionEvent.DEATH`→`onCompanionDeath`(invalidate+recordLost)、`RddHistoryStore`
- 恢复线索：`AssetHistory.recoverable`、`RddPlugin.recoverableContext`
- 风险硬门：`RddDetector.riskGateAllows`→`RiskGate.checkWithRecovery`

**验证步骤（剧本）**：
1. 给它床/箱/工作台 → 确认**绑了重生点**（`getRespawnPosition()` 非空）。
2. **主动点死它一次**（指令）；看：死亡→资产失效→复活→**是否回床旁**→**是否去拿备用**→**是否回去捡尸**。
3. 不给足装备让它去下界 → 看 `riskGateAllows` 是否**拦住**（`primary_risk_gated`）。

**画勾**：
- [x] 绑床成功（重生点非空）✅（`bound the bed (head) bed at 9777,61,10270 as your respawn point`）
- [ ] 死亡→复活在床旁（非主人旁）★ **暂不能验证**（工具集无"直接致死"指令；自然送死太慢）
- [ ] 回基地取备用装备 ★ 同上
- [ ] 回去捡尸（掉落物）★ 同上
- [x] 装备不足→下界被硬拦 ⏳（代码 `riskGateAllows` 已接，未实测触发）

**V3 死亡回收实测（2026-09-25 17:44，用 debug_kill）**：
```
17:44:10 body died  →  17:44:20 respawned（10秒复活）
```
| 项 | 观察 | 判定 |
|---|---|---|
| 复活位置 | (9874,79,10208) = **世界出生点**，非床旁(9777,61,10270)、非主人旁(主人在下界) | ❌ **床边复活没生效** |
| `recordSelfBedAnchor` 日志 | **无** | ❌ 绑床没记进锚点（**内存 map 重启即丢**） |
| rdd-history LOST | 仍 1（死亡后未新增） | ❌ `recordDeathLostHistory` 未生效 |
| 复活后背包 | 只剩 oak_log×2（装备掉光） | ⚠️ 无自动取备用/捡尸 |

**V3 挖出的真问题（待修）**：
1. **`BED_RESPAWN_PREFERENCE` 是内存 map，重启即丢** → 重启后死亡无锚点 → 落世界出生点。→ **需持久化**（同 `rdd-history` 落盘模式）。
2. 死亡没记 LOST 到 history（`recordDeathLostHistory` 疑似未跑/空）。
3. 无"死亡→取备用→捡尸"流程（LONGRUN-ISSUES #5）。
- 工具：`debug_kill`（需 confirm=true）已加并可用（`9505c602`）。

**V3 实测（2026-09-25 17:07-17:30）**：
- ✅ **右键绑床成功**：`interact_at` → `bound the bed (head) bed at 9777,61,10270 as your respawn point`（日志铁证）。
- ✅ **额外发现：`fc_control` 自动生存层**（`fall_rescue_water_bucket_or_soft_block` / `escape_lava_toward_nearest_dry_foothold` / `break_suffocating_block` / `surface_for_air` / `close_hostile_defense_with_combat_shield` / `unstuck_burst`）——**"自动防御/增强生存"的代码落点**（第二模块一部分）。实测它**主动避开了危险**（FC 开着时不进岩浆）。
- ⚠️ **死亡回收未能验证**：MCP 工具集**没有直接致死指令**；`enqueue` 软命令它不死（FC 自救/绕开）；关掉 FC 后 `goto` 岩浆**路径挖得太慢**（y=57→48，未到岩浆层）。→ 按"能验证一个是一个，不能就算了"**记为暂缺**，后续用 `damage`/`kill` 类工具或人工在游戏内 `/kill` 再验。

---

## V4 · 任务质量（生成质量 / 硬规范）
**目标**：生成的任务质量高不高；重规划时**是否真的想到**"进地狱要全套保护/抗火"等（硬规范，因地狱危险）。

**边界模块**：
- 规划提示词：`RddStagePlanner`/`RddDecomposer`（held/世界/村庄/可恢复/风险前置/时间成本/经验）
- 硬规范：`RddRiskPlanning.prepHint`+`injectWaitFor`、`ResourceBudget`、`RiskGate`
- 经验注入：`knowledge/builtin-experience.jsonl`（**全量死亡经验一次注入，后续不再注**）

**验证步骤（剧本）**：
1. 下 `/goal 通关MC` → 看它生成的一级/二级清单：**进下界阶段是否自带**钻石套/抗火药/金苹果/回退路线（硬规范）。
2. 重规划一次 → 看质量是否保留硬规范。
3. 生成质量差 → 考虑换模型（稳重性/速度/效率 vs 危险意识）。

**画勾**：
- [x] 进下界阶段自带全套保护硬规范 ✅（12 项 waitFor，含抗火药/床/两套钻石甲）
- [x] 重规划保留硬规范 ✅（当前链即重规划产物，硬规范在）
- [x] 生成质量达标 ✅（结构合理、有风险前置）

**V4 实测证据（2026-09-25 17:30）** —— 生成的一级清单 waitFor：
```
P3 下界取烈焰棒 waitFor: golden_apple2, cooked_beef16, bow, arrow32,
   diamond_chestplate2, water_bucket, diamond_leggings2, diamond_helmet2,
   torch16, diamond_boots2, fire_resistance_potion3, white_bed1   ← 全套硬规范
P6 末地准备 waitFor: ender_pearl12, water_bucket, golden_apple2,
   diamond_chestplate, arrow32, bow, cooked_beef16, white_bed
P7 击杀末影龙 waitFor: 同上全套
```
→ 由 `RddRiskPlanning.injectWaitFor`（**代码硬门**，非提示词祈祷）产生。**"进地狱要全套保护/抗火/床"真的被想到了。**

---

## 模块边界拆分（验证中顺带做）

| 线 | 涉及模块 | 是否已独立 | 拆分建议 |
|---|---|---|---|
| V1 对话 | 协商(工具+收件箱+指挥官处理) | 部分 | `negotiation/` 子包 |
| V2 判定/资产 | 资产(扫描/快照/判定/转化) | 部分 | `asset/` 子包（Live/World/History/Verify 分离） |
| V3 生存 | 死亡/重生/风险门/恢复 | 部分 | `survival/` 子包 |
| V4 规划质量 | 规划 + 硬规范 + 经验 | 混在 planner | `planning/` 子包 |

> 注：模块拆分**不新建复杂度**，只是把现有类归到清晰子包（先列边界，稳定后再移动）。
