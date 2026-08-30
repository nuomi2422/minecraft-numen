# AC execution layer extraction boundary

第一阶段建立 `ac-api` 与 `ac-core` 两个独立 Java/JVM 模块。它们不依赖 NUMEN、Minecraft、Fabric 或 NeoForge，可由任意宿主注入工具实现与执行上下文。

## 保留在纯 JVM 层

- `ToolRegistry` / `AcTool`：工具注册契约与参数化同步调用。
- `AcDefinition` / `AcStep`：名称、顺序步骤、参数和基础构造校验。
- `AcExecutor`：顺序执行、未知工具失败、PAUSED 终态与从 `completedStepIndex` resume。
- `ExecutionRecord` / listener：每次执行的状态、断点、输出、时间和消息。
- `AcJson`：从 JSON 加载并触发基础结构校验。

## 明确不抽取

Minecraft 世界访问、实体/背包/区块操作、线程调度、日志桥接、LLM provider、旧 DD 的 ActionContext/ActionResult、控制块和宿主持久化。这些由后续 NUMEN 适配层负责，避免第一阶段把宿主耦合带入 API。

## Resume 语义

成功步骤的数量写入 `completedStepIndex`；PAUSED 步骤本身不计入完成数。`resume` 从该索引重新执行，因此暂停步骤可以在外部条件满足后重试。记录保存在 executor 实例内存中，持久化不属于本阶段。
