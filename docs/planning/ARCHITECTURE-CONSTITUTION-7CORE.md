# RDD 七核心架构宪法（边界契约）

> **性质**：不是功能清单，是**边界契约**。定义"谁负责什么、谁不许越权"。
> **用途**：后续所有开发（含自变异）必须遵守；越界 = 架构违规，等同破坏基准红线。
> **记录**：2026-09-26 定稿（人工 + 外脑）。

---

## 总览

```
RDD
 ├── 任务链 TaskChain      「长期要做什么？做到哪了？」
 ├── 监测台 Monitor        「现实到底发生了什么？」
 └── 自变异 Self-Compile   「系统发现问题后怎么改变自己？」
        │
        └── Supervisor / Runtime
                ├── 第二批：FC 主钩携带器 / 记忆库 Memory / AC
                └── 执行侧：Numen → Minecraft 现实
```

**核心闭环（完整）**：
```
长期目标 → TaskChain → Supervisor → Numen → Minecraft现实
   → Monitor（成功/失败/事件/新经验）→ Memory → FC（当前场景经验浮现）
   → Numen（高频行为）→ AC（固化成稳定能力）
同时：系统性问题 → Self-Compile → 改系统 → 测试/回归/验证 → 新基准
```

---

## 1. 任务链 TaskChain — 「长期目标怎么推进」

**职责**：把大目标变成可追踪、可恢复、可动态调整的任务结构。
**负责**：Primary/Secondary、任务状态、完成事实(fact)、资产依赖(waitFor)、CONFIRM、Early Achievement、动态任务、局部 Repair、Supervisor 协商、Replan/Recovery。
**解决**："我们现在在做什么、做到哪了、下一步是什么？"——不让 Numen 每次从零猜。

### 代码落点
- `rdd-core/core/TaskChain.java`（状态机核心）
- `rdd-core/fact/*`（完成事实继承）、`rdd-core/replan/*`、`rdd-core/fail/*`
- `plugins/rdd/RddDetector/RddGoalDriver/RddStagePlanner/RddDecomposer`

### 边界（不许越权）
- **不做感知**（那是 Live/World 资产与 Monitor）。
- **不直接调 LLM 做战略决策**（交 Supervisor/Planner）。
- **不写记忆库**（交 Memory/FC）。

---

## 2. 监测台 Monitor — 「现实到底发生了什么」

**职责**：观察与报告。`Minecraft → 事件/状态/资产/失败 → Monitor → 事实/日志/告警/指标 → Supervisor/Runtime`。
**已有**：asset observation、task progress、failure/concern、instrumentation、loop detection、asset mismatch、death/recovery tracking、runtime observability。

### 代码落点
- `plugins/rdd/RddMonitor.java`（rdd.jsonl）、`RddInstrumentation.java`（instrumentation.jsonl）
- `rdd-monitor-publish-20260913/monitoring-station`（8776 面板）

### 边界（不许越权）★关键
- **监测台不是第二个 Supervisor**：发现问题，但**不擅自替系统做战略决策**。
- **只读观察**：不写任务主状态、不投 MCP 指令。

---

## 3. 自变异 Self-Compile — 「系统怎么自己改变」

**职责**：受控地改系统。`发现问题 → Mutation Proposal → 权限/Policy → 隔离修改 → 编译 → 测试 → 回归 → 部署/热加载 → 验证 → 失败回滚`。
**核心命题**：**AI 可以修改系统，但不能让系统失去可控性。**

### 代码落点
- `plugins/selfcompile/*`（MutationState/Pipeline/Permissions/HarnessWriteLocks/Proposal 流水线）
- `rdd-selfcompile/scripts/*`（外部流水线）

### 边界（不许越权）★关键
- **不许直接改核心**（TaskChain 状态机/基准红线）而不经回归。
- **护栏**：基准红线 + 文档 + 测试 + 回归 + 可回滚（`BASELINE-REDLINE-*.md`）。
- **失败必回滚**。

---

## 4. 主钩携带器 FC — 「经验什么时候应该浮现」★第二批

**职责**：在**正确的事件/工具/参数/环境节点**，把**相关经验携带**到当前上下文。
`挖矿 → 检测特殊环境 → Hook 命中 → 查询相关经验 → 携带一小段 → Numen 当前行为受影响`

### 边界（不许越权）★关键
- **FC ≠ 记忆库**：FC 是"**何时浮现**"（检索/携带）；Memory 是"**存什么**"。
- **不许全量塞**：只携带**相关的一小段**。
- 目前**未实现**（`fc_control` 是引擎内自动生存层，不是 FC 经验携带；FC 待建）。

---

## 5. AC — 「怎么把行为变成便宜、稳定的能力」

**职责**：**状态机 + 工具调用组织/合并 + 游戏内热加载能力**。
**解决**：把 `LLM 连续思考→工具A→B→C→D` 这种高成本/高延迟/易错的流程，**固化成可靠能力/状态机**。
**AC 的价值不是"让 AI 记住"，是"把已验证有效的行动方式固化下来，降成本、提稳定"。**

### 代码落点
- `plugins/ac/*`（BridgeResultMapper/NumenHostAdapter/NumenToolBridge 等）

### 边界（不许越权）
- **不承担记忆**（那是 Memory）。
- **不做战略决策**（那是 Supervisor）。
- **固化的是"已验证有效"的动作**，不是猜测。

---

## 6. 记忆库 Memory — 「经历过什么、学到了什么」★第二批

**职责**：经验的**独立存储与质量层**。
`成功 → 总结经验 → 质量控制 → 经验库` / `失败 → 分析总结 → 质量控制 → 经验库`

### 代码落点
- `plugins/experience/*`（ExperienceLearnTool/RecallTool/VerifyTool/ExperienceKnowledgeSource）
- `config/numen/knowledge/builtin-experience.jsonl`、`config/numen/experience-<uuid>.jsonl`

### 边界（不许越权）★关键
- **不直接决定当前行为**。
- **不每次把整个数据库塞给 Numen**（否则"Memory 越来越大，Agent 越来越不知道该看什么"）。
- **Memory = Experience Store；FC = Experience Retrieval/Carrier。** 两者**必须分开**。

---

## 7. 运行主体 Numen — 「真正执行」

**职责**：感知、工具调用、游戏操作、对当前任务判断、提出 **ACCEPT/REJECT/COUNTER**、接受 Supervisor 调整、把现实反馈传回。
**原则**：**RDD 不应把 Numen 变成傀儡。**
```
Supervisor ↕ Numen Harness ↕ Numen → Minecraft
（让 Numen 能反驳、能协商、能反馈现实）
```

### 代码落点
- `core/*`（假玩家/任务派发/路径/工具实现）、`api/*`
- `plugins/rdd/RddConcernTool`（士兵回执）↔ `RddNegotiationInbox`（指挥官收件）

### 边界（不许越权）
- **不做长期规划**（那是 TaskChain/Supervisor）。
- **不写记忆**（由 Monitor→Memory 链路负责）。

---

## 一句话职责表

| 部分 | 核心问题 |
|---|---|
| TaskChain | 我们长期要做什么？做到哪了？ |
| 监测台 | 现实到底发生了什么？ |
| 自变异 | 系统发现问题后怎么改变自己？ |
| FC 主钩携带器 | 过去什么经验应该在这一刻浮现？ |
| AC | 这个行为能不能固化成便宜、稳定的能力？ |
| 记忆库 | 我过去究竟学到了什么？ |
| Numen | 现在具体怎么行动？ |

---

## ★ 最值得保护的边界（第二批三关系）

> **Memory 存经验 → FC 携带经验 → AC 固化行为。**

**三者不互相越权，胜过"做一个超级 Memory"。**
- Memory 越权 = 变超级数据库，Agent 不知看啥。
- FC 越权 = 退化成"全量塞"。
- AC 越权 = 把未验证动作也固化。

---

## 与基准红线的关系
- 本宪法 = **架构级边界**；`BASELINE-REDLINE-*.md` = **行为级验证**。
- 两者并列：**改架构不得违宪；改行为不得破红线。**
- 自变异的 Proposal 必须同时过**宪法审查 + 红线回归**才准部署。
