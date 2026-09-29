# Numen 数据流审查

> 审查日期：2026-09-19 ｜ 范围：`minecraft-numen` 本体运行时数据流 ｜ 目标：MC 1.21.1，version 0.1.3-dev
> 结论一句话：**客户端大脑 / 服务器身体分署**，一切世界动作跨一次网络往返，靠 LLM `tool_call.id` 穿线。

## 1. 总览

```
主人说话 / 世界事件
      │
      ▼
EventQueue（收件箱，每同伴一个，push 落盘、消费即清）
      │ drainInbox：三态路由决定何时开轮
      ▼
EntityAgentLoop（客户端大脑，api/common/src/client/.../client/agent/EntityAgentLoop.java:65）
      │ 每 turn：组装静态框架+历史+C/D 输入 → llm.chatStreaming (SSE)
      ▼
ai/ NumenLlmClient  →  HttpLlmTransport (JDK HttpClient) → LLM Provider（OpenAI/Anthropic/DeepSeek/Moonshot）
      │ 模型返回 tool_calls
      ▼
NumenTool.invoke（ToolRegistry 46 工具；感知类本地解析，世界动作进 ServerToolTransport）
      │ ExecuteToolPayload  (C→S，信任链：目标 NumenPlayer + 发送者=持有者 UUID + 工具可解析 + args 过 toTaskRecord)
      ▼
TaskDispatch 三选道（不占身体→原地 | 有界短→runSync | 无界→setTask）
      ▼
CompanionBrain/TaskSlot（服务端身体，api/common/.../task/CompanionBrain.java:32）
      ▼ outbox 每 tick 收编终态 → TaskResultPayload (S→C，toolCallId 配对 LLM tool_call.id)
      ▼
ServerToolTransport.deliver(id,json) → 挂起 ToolCall 完成 → role:tool 消息 → 下一 turn
```

身体控制竞价：`TaskSelector.java:32` 固定四层，无竞价分——反射链(BrainChains 注册序) → sync 槽(0/1) → current 槽(0/1) → 闲时姿态。反射可随时抢走身体，LLM 是最低优先。

## 2. 收件箱三态路由（api/common/.../event/EventQueue.java:41）

- 回合进行中 → 进箱躺着，工具批结算边界倒空
- 后台任务执行中 → 立刻开轮（任务期间的事是军情）
- 完全空闲 → 进箱躺着，等搭车
- 唯一例外 principal（活人说话：主人/弹幕/QQ）→ 闲时空闲也开轮

事件为 XML：`<event kind=... day=... t=...>`；主人话语 `role:user` 经 submitCommand 进箱。`NumenEvents.Kind`（`event/NumenEvents.java:44`）：TASK_FINISHED / BODY_LOG / DIMENSION_CHANGE / DEATH / TIMER——唯一世界事件入口，XML 组装/转义/游戏内时间戳在此收口。离线安全经 `EventOutbox`（SavedData），主人上线回放（`entity/Companions.java:189/200-213`）。

## 3. LLM 大脑（ai/ + 客户端循环）

- 传输层只用一个缝：`NumenLlmClient.chatStreaming`；`HttpLlmTransport` POST + SSE，`lr-N` 请求 id，统一错误模型；默认 `stream:true` + `include_usage`。
- `LlmProvider` 是 Provider 族适配器，讲 `AssistantTurn` / `LlmToolCall`。
- `IToolSpec` = name/description/parameterSchema 只读，随请求进 provider；OpenAI 每次重建工具表（反射总览便坐在这条缝上）。
- 上下文组装在 `EntityAgentLoop`：静态框架（系统提示词+工具表）→ 历史（ConvoLog 回放/压缩摘要 CompactSplit）→ 本 turn（用户消息+收件箱事件）→ 工具结果回填。`ESTIMATED_FIXED_OVERHEAD_TOKENS=8000`。
- 失败路径：插入 `("(连接中断)")` 后重试整轮；`gen != turnGeneration` 陈旧结果丢弃。

## 4. 身体执行（core/common/）

- 初始化（`NumenCore.init`）：46 工具（goto/attack/locate/collect/fish/follow/auto_mine/equip/build/blueprint/interact/sleep/eat/gui/craft/scan/self_status…）+ 15 任务 Runner + 6 反射链（order 10/12/15/20/30/50）+ 反射名册。
- 反射登记处 `ReflexRegistry`：静态同步 map，幂等；`overview()` 生成"你的身体有这些本能…"挂在 `get_self_status` 工具 description 上（api 无系统提示词缝）。
- 代表性追踪 `goto`：MoveToTool → `TaskDispatch.setTask` → `MoveToCompanionTask`（BLOCK/COLUMN/YLEVEL/FIND，地形新鲜度租约 600 tick，CHECK_IN_CAP 6000，近成功重试阶梯），`resultData()` 回传 final_x/y/z + ground_y，`successMessage()` 教意图。
- 异步：长活受理即回执 `{task_id, async:true}`，收尾走 `task_finished` 事件（按三态路由落"立刻开轮"档），绝不轮询；一具身体一件活，替换式受理。

## 5. 结果回流与硬闸

- `TaskResultPayload`（network/payload/TaskResultPayload.java:36）：`MAX_RESULT_JSON_LENGTH = 16*1024`，超长截断加 marker。**问题见 §7-1**。
- `CurrentTaskPayload`（服务端推，槽一变就发）保证客户端不自猜 current_task；死亡复活/重启重放由此补齐。

## 6. 持久化与外部面

| 数据 | 落点 | 说明 |
|---|---|---|
| 对话 | `config/numen/conversations/<uuid>.jsonl` | ConvoLog v2 追加式，header `{v:2}` |
| 同伴家目录 | `config/numen/companions/<uuid>/` | binding/chat/stats/inbox/blocks/world；目录在↔数据在 |
| 任务记录 | TaskPersistence | 回放原 tool call（toolName+args，合成 id `restored`），不保留进度 |
| 定时器 | TimerRegistry（SavedData） | 主世界 gameTime 绝对值，每同伴 ≤8 |
| RDD 链/资产 | `config/numen/rdd-tasks/` + `rdd-assets/<uuid>.json` | TaskChain.toJson 原子 tmp+move；跨进程/重启存活 |
| 经验 | `config/numen/experience-<uuid>.jsonl` | stable-key 合并防刷屏；成熟度只升不降 OBSERVED→…→GENERALIZED(=3) |
| 监测 | `config/numen/monitor/<category>.jsonl` | MonitoringJournal，2048 队列单写线程，16 MB/文件，统一信封 schema_version/event_id/timestamp/source/category/type/data |

外部面：
- **MCP server**（loopback IDK HttpServer `/mcp`，JSON-RPC 2.0，手写无 SDK）：initialize/ping/tools/list/tools/call；6 管理工具 + 全部 ToolRegistry 工具（注入 `companion` arg）；`get_events` 长轮询 2s；驱动模式下内部 agent loop 停转。
- **RDD 长目标**：`GoalSinks.java:19` 单 sink。`/goal` → RddPlugin.beginPlanning → Stage-A RDD 规划 → `RddRuntime(TaskChain, assets)`；Detector 1/s 心跳，只读世界真身，绝不调 LLM；nudge 经 `numenApi.enqueue` 且受监督开关+flag 文件门控；monitor 事件含 taskchain_snapshot/asset_snapshot/numen_context/supervisor_input|output…（rdd.jsonl）。
- **经验**：ExperiencePlugin 贡献 `<experience>` 状态 + planning knowledge（≤2000 字符，三重回退）。
- **AC 执行层**：ac-api（AcDefinition/AcStep/Document 模型，host 无关）→ ac-core 验证/发布/断点恢复，`AcFingerprint` SHA-256 保身份，内容变了拒绝续跑。

## 7. 发现的问题清单

1. **结果 16 KB 硬截断会丢数据（真 bug）**：`TaskResultPayload.java:36` 截断后只留 marker，客户端拿到的是残缺 JSON；已见 52 KB rdd_assets 场景导致模型掉线。建议：改走侧信道（如分片/落盘+事件引用），或至少日志告警。
2. **过期文档引用**：`BrainChains` javadoc 引用不存在的 `LlmTaskChain`；`Reflex` 接口注释的 enabled 开关与 `ReflexRegistry`"名册即全部"自相矛盾——按注册现实修文档。
3. **`TaskResult` 字段形状没有单一清单**：服务器端 `toJson` 手写字段，网络层只当好搬运工，字段漂移只能靠运行时发现。建议补契约测试。
4. **`toTaskRecord` 无主人距离校验（by design）**：`ExecuteToolPayload` 信任链明确不做 owner-distance 检查——任何维度、任意 NumenPlayer 目标均可，第三方要自己的网络面负责。
5. **监测台只读派生状态**：`taskchain_snapshot` 只带资产计数（288 MB 教训），普通女仆聊天不泄露内部控制文本——需维持该边界。

## 8. 数据流健康检查结论

主线（收件箱→大脑→工具→选道→身体→回流）设计自洽：无轮询、事件驱动、结果必经 toolCallId 配对、离线安全。风险集中在**大结果通道（16 KB 闸口）**与**文档漂移**；外部三系统（MCP/RDD/经验）都走明确的缝（GoalSinks/NumenEvents/NumenPlugins），不直接改控制面。