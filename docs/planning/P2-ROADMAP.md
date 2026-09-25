# RDD P2 路线图 — 资产语义与决策增强层

> 状态：进行中（2026-09-25 立项）
> 档位：**模块级(Bounded) + 工具级补充**（人工已批准；不改 TaskChain 核心状态机，不重写 REPLAN）
> 权威资料根：`E:\新建文件夹\rdd架构\`（README、工程规范）；本文件是 P2 的设计债账本。

---

## 0. 一句话目标

RDD 不再只是让 AI「完成任务」，而是让 AI 拥有类人老玩家的世界理解能力：
**知道自己有什么、为什么拥有、什么时候该用、失败后如何恢复。**

核心判断（GPT 外脑 + 人工）：
> RDD 现在有「资产存在性」，缺「资产用途和决策价值」。补的是**资产语义层**，不推翻架构。

---

## 1. 资产分三层（不做无限膨胀的大数据库）

| 层 | 职责 | 载体 | 更新频率 | 持久化 |
|---|---|---|---|---|
| **LiveAssetSnapshot（实时）** | 我现在有什么（背包/装备/生命/携带） | `countInventory` + `PlanningAssetSnapshot` | 高频（每 tick 扫） | **不持久化** |
| **WorldAsset（世界）** | 世界里有什么（村庄/基地/箱子/农场/机器/结构） | `AssetRegistry` 的 `world_*` + `RddWorldAssetObserver` | 低频（懒观测） | **持久化** |
| **AssetHistory（战略）** | 为什么拥有它（用途/价值/恢复线索） | `AssetHistory`（P2.1） | 事件驱动 | 持久化（待接线） |

**铁律：背包资产不直接持久化。** 昨天有钻石剑 ≠ 今天还有。背包永远以实时扫描为唯一真相。

---

## 2. 重规划方向改变

不再 `失败 → 重新生成任务`，而是：

```
事件发生 → 分析原因 → 判断是否可恢复 → 选择：恢复 / 补资源 / 重新规划
```

死亡示例：
```
死亡 → 检查资产 → 有没有备用装备？
  有 → 回基地拿装备继续
  无 → 重新规划装备生产
```

（现状已有 `FailureClassifier`/`RecoveryPolicy`/`ReplanContextBuilder`，P2 补的是「可恢复线索」与「资产用途」。）

---

## 3. 规划 AI 输入（Planner 不再只看目标）

```
目标 + 当前资产(Live) + 世界资产(World) + 历史经验(History) + 风险(Risk) + 失败记录(Failure)
```

---

## 4. 村庄策略升级

```
VillageNode { 位置, 是否探索, 资源摘要, 是否占领, 是否作为基地 }
```
进入村庄的优先顺序：食物 → 箱子 → 铁资源 → 书籍 → 床 → 建立恢复点。
扫描完成后记录「这个村庄已搜过」。

原则：**先做 VillageObservation（事实）→ 再让 Planner 评价价值 → 才形成策略**。不要先写规则让 LLM 自己悟。

---

## 5. 经验库方向

经验不只是提示词，而是「玩家常识」：前期村庄优先、高风险维度要备用装备、进地狱备抗火、不空手进矿洞、食物要库存……

落点：`config/numen/knowledge/builtin-experience.jsonl`（结构与现有 POLICY/WORLD_RELATION 条目一致）。

---

## 6. 工程优先级（人工最终倾向）

| 序 | 事项 | 说明 |
|---|---|---|
| **1** | 修自编译系统 | 后续自动修复能力的基础 |
| **2** | 资产语义层 | 重规划/RiskGate/经验系统都依赖它 |
| **3** | RiskGate | 高风险任务硬规则（进地狱必须检查装备/食物/备用/药水/恢复点） |
| **4** | 失败诊断 + 真正重规划 | 失败事件→诊断→恢复方案→重新生成 |

执行子序（P2-A..E，人工已批准）：

```
P2-A  资产判定bug修复（地基，先做）
P2-B  TaskQualityReport 只读上报事件（Numen→Supervisor 单向反馈）
P2-C  AssetRole + ResourceBudget（含资产派生/等价）
P2-D  VillageObservation（先事实）
P2-E  Cost/Risk 评分（依赖前四项）
```

---

## 7. P2 明细与状态

- [x] **P2.0** 已完成（历史能力，本账本建立前）
- [x] **P2.1 资产历史/恢复语义** `AssetHistory`（Lost≠Gone）— core `c668892b`；接线待做
- [x] **P2-A 资产判定 bug（地基）** — `c6d0cf33` + `ace04e4e`（实时扫描为唯一真相；背包不落盘为设计不变量）
- [x] **P2-C AssetRole + ResourceBudget + 资产派生** — `AssetPurposeStore`/`AssetRole`（既有）+ `AssetDerivation`(`5f839ea4`) + 接线 food 等价(`1019434c`)
- [x] **P2-D VillageNode** — `8569ce69`（先事实；Stage-A/B 注入 `known_villages`）
- [x] **P2-E TaskCostModel** — `7ed8cd0d` + 接线(`d611f0fe`)（时间刻度 + 省时优先序注入提示词）
- [x] **P2-B TaskQualityReport 只读上报** — `2e9f7121`（执行层→监督层单向事件，不做双向）
- [ ] **P2.1 接线**：`AssetHistory` 接进 `RddPlugin`（死亡 recordLost / 观测 recordCurrent / 落盘）+ 规划注入可恢复线索
- [ ] **P2.5 / 双向 Supervisor 对话**：需升档、人工正式批准，暂缓

> 验收：`rdd-core + plugins:rdd` **230 测 / 0 失败**；`plugins:selfcompile` 全绿（2026-09-25）。

---

## 8. P2-A 资产判定 bug（地基，先做）

### 现象
背包里小麦明明够，`condition {asset_key: minecraft:wheat, minimum: 12}` 却判定不通过；反复「body task ended without satisfying condition」。

### 已核实证据（2026-09-25）
持久化 `rdd-assets\<uuid>.json` = 128 条，全部 `world_*`，**`inventory_scan` 条目 = 0**。
→ 背包类资产从不落盘：`RddAssetStore.isWorldAsset()` 只放行 `world_` 前缀，`load/save` 丢弃 `inventory_scan`。

### 真实调用链
```
RddDetector.tickRuntime(每20tick)
 ├─ countInventory(ap)                      真身物品计数（唯一 truth）
 ├─ populateAssets(...)                     写 AssetRegistry inventory_scan（内存）
 ├─ conditionMatches → HardCodedEvaluator.matches   纯计数判定（逻辑无bug）
 └─ maybeRetryOrFail → "body task ended without satisfying condition"
依赖门/重规划/PlanGuard → RddPlugin.planningSnapshot → PlanningAssetSnapshot.from(lastInventory, assets)
```

### 决策（人工已定）
**背包不持久化**：`PlanningAssetSnapshot` 以「最近一次实时扫描」为唯一真相，注册表**只用于 `world_*`**。
不得再让 `inventory_scan` 参与规划真相来源。

### 验收（P2-A 完成标准）
- [ ] 规划/依赖门/PlanGuard 的「当前资产」只来自实时扫描，不再依赖 `inventory_scan` 落盘
- [ ] 单元：`countInventory` 数量正确（含堆叠）
- [ ] 单元：craft 后资产转化正确（wheat→bread，bread+、wheat−）
- [ ] 单元：consumed 后扣除正确
- [ ] 集成：**死亡失效后重新规划 → 快照仍反映真实持有资产**（防跨组件交互回归）
- [ ] world asset 与 inventory asset 不互相污染

---

## 9. 交接注意
- 不改 `TaskChain` 状态转移（satisfiedStages / CONFIRM / RECOVERING 硬约束）。
- 不重写 REPLAN；`FailureClassifier/RecoveryPolicy/ReplanContextBuilder` 保留并增强。
- 后续每次改动登记：`E:\新建文件夹\rdd架构\测试日志.md` + `自变异系统v3\08-更新日志.md`。
