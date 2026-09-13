# RDD / Numen 请求观测

用途：核对最新一次实际交给 HTTP 客户端的提示词、任务量、工具定义和对应可见输出。
观测不负责批准任务、判断世界成功或改变模型的请求。

## 两个 AI 的入口

| AI | 调用点 | 日志 | phase |
| --- | --- | --- | --- |
| RDD 规划 AI（Supervisor） | `RddDecomposer.llmAsk` / `decompose` | `config/numen/monitor/rdd.jsonl` | `stage_a`、`stage_b`、`fallback` |
| Numen 执行 AI | `EntityAgentLoop` | `config/numen/monitor/context.jsonl` | `execution`、`execution_retry`、`compaction`、`goal_judging` |

设置页的连接测试 `ping` 不属于以上两种任务请求。历史 `supervisor_context` 等局部准备事件保留，但不能作为发送成功证据。

## 事件契约

所有事件的 `data` 都包含 `actor`（`supervisor` / `numen`）、`companionId`、`requestId`、`phase`、`model`、`observationVersion: 1`。

- `llm_request`：`status: dispatched`、`attempt`（从 1 起）、`transportRequestId`、`provider`、`request`（经过 provider 方言转换、生成参数和 streaming 参数处理后的最终 JSON body）。它表示 `sendAsync` 已接收派发；不保证上游收到或接受。HTTP 自动重试沿用逻辑 `requestId`，每次产生新的传输 ID 和递增 attempt。
- `llm_response`：`status: completed`、`responseStage: accumulated_visible_stream`、`response: {content, finish_reason, tool_calls: [{id, name, arguments}]}`、`promptTokens`、`totalTokens`。参数取自原始可见流的累计值，因此无效 JSON 不会被执行层的 `{}` 容错掩盖。收到回复不等于工具执行成功。
- `llm_failure`：`status: failed`、`errorType`；HTTP 拒绝时有 `httpStatus`。不输出上游原始错误正文或含凭据的 URL。

前端必须同时匹配 `actor + companionId + requestId` 才能配对输入输出，不能把旧回复配给新请求。全局最新时间不能代替各 AI、各请求的时间。

请求正文完整保留可见 system/messages/tools，不截成预览摘要。副本排除凭据和 provider 内部推理内容（`excluded: [credentials, hidden_reasoning]`）；思考模式的配置仍可见。不会改动实际发出的正文或既有会话回传机制。观测回调/序列化出错不能使原请求失败。

## 当前数据流的实际边界

目前 `RddStagePlanner` / `RddDecomposer` 主要发送静态规划规则、目标、背包、已完成阶段和重试提示。它们没有自动接入外部经验资料库；不能因为资料库文件存在就显示“知识已注入”。应从该次 `request` 查验内容。

Numen 的 system 包含人设、操作规则、技能、本能和延迟工具目录。`request.tools` 是本次实际暴露的工具定义，可能仅包含常驻工具；目录中的延迟工具不等于本次已完整暴露。历史/任务/世界状态是否到位，以 `request.messages` 为准。

日志仍属于尽力观测：Numen 沿用有界异步 `MonitoringJournal`，RDD 沿用插件日志出口，并序列化文件写入；两个出口不并发写同一个分区。日志缺失需要显示为缺证据，不能补造事实。

## 验证与部署

`gradle :ai:test :api:common:compileClientJava :plugins:rdd:compileJava` 验证纯 JVM 观测及接线。
`LlmObservationTest` 用本地 HTTP 服务核对实际收到的 body、503 重试关联、坏工具参数原文、脱敏、非标准 type 字段、观测失败隔离和失败配对，不调用付费模型。

发行构建：`gradle :api:neoforge:jar :core:neoforge:jar :plugins:rdd:jar`。AI 类在 API jar 中，Core 还内嵌 API，RDD 是独立插件；隔离 test 实例应同步这些构件，避免新插件调用旧 AI 类导致链接错误。构建和本地测试通过，不代表游戏内已产生新的真实规划请求。
