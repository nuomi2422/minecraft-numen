# AC Execution Layer — 边界与改造记录

> 日期：2026-08-31。AC 是 RDD 五模之一，但核心是独立纯 JVM 插件：脱离 RDD/NUMEN/Minecraft 仍能注册工具、执行 AC、暂停/续跑、自管记录。本文件记录抽取边界、五个施工批次、NUMEN 适配语义与验证证据。

## 0. 一句话定位

**AC = 宿主无关的原子工具编排执行层。** 它接收宿主注册的原子工具，允许主 AI / 编码 AI 创建、修改、版本化 AC，负责流程调度、PAUSED 后真实 resume、执行事实记录，并把结构化事件旁路给任务链 / 监测台 / 经验库；不生成工具、不做任务规划、不替 Supervisor 语义判断、不依赖 RDD 或 NUMEN。

## 1. 模块结构

```text
ac-api/    纯契约层(零第三方依赖)：AcDefinition/AcStep、AcTool/ToolRegistry/ToolSchema、
           StepResult/ExecutionContext/ExecutionRecord/ResumeContext、
           AcEvent/AcEventListener/ExecutionListener、AcVersionStore
ac-core/   纯 JVM 实现(仅 Gson)：AcExecutor、AcFingerprint、DefaultToolRegistry、
           AcParamValidator、AcJson、InMemoryAcVersionStore、AcAuthoringService
rdd-core/  纯 JVM 实现(零第三方)：RDD 资产任务链核心(Goal/PrimaryGoal/Subtask/
           TaskChain/AssetRegistry/RddRuntime/HardCodedEvaluator) — 2026-08-31
           从 ac-core 迁出, 消除与 plugins/ac 同装的重复包
plugins/ac Numen 宿主适配插件(独立部署)：NumenHostAdapter/NumenToolBridge/BridgeResultMapper/
           NumenSchemaAdapter + ac_execute/ac_status/ac_resume 门面工具；惰性桥接
plugins/rdd RDD 宿主适配插件(独立部署)：依赖并内嵌 rdd-core, 不再内嵌 ac-core
```

`ac-api`、`ac-core`、`rdd-core` 不依赖 NUMEN/Minecraft/RDD；`plugins/ac` 是 AC 的 NUMEN 宿主面，也只走公开 `NumenPlugins.register`。

## 2. 五个施工批次（提交可单独回退）

| 批次 | 提交 | 内容 |
|---|---|---|
| A | f23af8a6 | AC 身份契约：`name+version+canonical fingerprint`；ExecutionRecord 携带 ResumeContext；resume 仅同一身份 PAUSED 可续、重试暂停步、保留 input/output、拒绝版本/内容变化 |
| B | 251f2c98 | 执行事件流：AcEvent(execution/step 级) + AcEventListener；ExecutionRecord 增加稳定 executionId；有界历史(maxRecords)+recordsByExecutionId；监听器异常隔离 |
| C | 7347601b | 工具目录/schema/JSON：ToolSchema 有限参数契约(类型/required/枚举/allowUnknown)；稳定目录；AcParamValidator 执行前校验；AcJson 严格解析+数字归一 |
| D | 3efc666b | AI AC authoring：AcVersionStore+InMemoryAcVersionStore+AcAuthoringService(validate/propose/publish/listVersions/load)；发布原子、非法不破坏当前版本 |
| E | 69e4a274 | Numen 工具桥接插件 plugins/ac：HostTool 纯 JVM 桥 + NumenHostAdapter + 门面三工具 |

## 3. Numen 适配语义（水土不服的补丁）

AC 步骤工具 `AcTool.execute` 是同步的；Numen 工具是异步 `ToolCall.complete`。桥接关键判据：

- `timed_out` / `interrupted` → AC **PAUSED**（未终态，可续）
- `success=true` 但 `data.async=true` → **setTask 长任务“受理回执”，不是步骤完成 → PAUSED**，等 `task_finished` / `task_status` 轮询后 resume
- 其余 `success=true` → **SUCCESS**；`success=false` → **FAILED**
- 工具声称成功 ≠ 世界已验证：AC 只记录工具事实，观察证据由任务链 / 监测台 / 宿主适配器旁路提供
- 门面工具（`ac_*`）不注册为 AC 步骤工具，防递归；Numen ToolRegistry 与 AC ToolRegistry 语义分离，只经 adapter 转换

## 4. 与 RDD 其他模块的接口关系

- **资产任务链**（plugins:rdd）：拥有任务节点/资产/推进主权。AC 只提供执行事实与事件（`ac_execute` 受理、PAUSED 断点、SUCCESS/FAILED），不反写任务完成；目标拆解、Supervisor 确认、资产判定全在任务链侧。
- **监测台**：旁路观察 AC 的 `AcEvent`（step/execution 生命周期）与 ExecutionRecord，用于工具自述与观察证据并置；不拥有 AC 状态主权。
- **自编译**（plugins:selfcompile）：生成/修改原子工具 → JAR 部署 → 宿主重启 → Numen ToolRegistry 注册 → `plugins/ac` 启动时桥接进 AC registry。AC 不参与编译，不做运行时热加载。
- **经验记忆库**（experience-core）：可订阅 AC 事件/记录归纳经验；AC 不依赖它才能执行。

## 5. 验证证据

- `:ac-core:test`：A~D 全部用例（resume 契约、事件顺序、schema 校验、JSON、authoring、fingerprint、有界历史）全绿。
- `:plugins:ac:test`：桥的 success / timeout / async受理 / 失败 / 非JSON / 超时 6 例全绿；编译通过（含 NumenTool/ToolCall/ToolAnchor 瘦 api 依赖）。
- 测试命令使用本地 Gradle（`E:\restart developing doer\gradle-9.2.0`）+ JDK 21，避开 wrapper 下载超时。

## 6. 已知限制与待办

- 真机集成完整链路 `ac_execute → PAUSED → ac_status → ac_resume → 完成` 已在 2026-08-31 验证核心闭环（提交→后台执行→状态如实反映）；断点续跑全链待游戏环境常驻后复验。
- `plugins/ac` 门面当前无 `ac_cancel`；长任务释放依赖 Numen `task_stop` / `task_status` 轮询，由桥映射为 PAUSED 续跑。
- `AcJson` 参数数字归一为 Long/Double；`AcFingerprint` 对整数 1/1.0 归一，跨来源稳定。
- Numen schema → AC ToolSchema 是宽松映射，enum/min/max/嵌套 object 暂不完整表达（宁 ANY 放行不误拒）。
- 惰性桥接在 ac_execute 时同步工具目录；运行时新增的 Numen 工具（如自编译产出后重启）会在下次 ac_execute 时自动补桥接。

## 7. 真机验证 3 缺口修复（2026-08-31）

验证 AI 发现并已修复（提交 `2c3f004e` / `ebf8e8ed`）：

1. **setup 时序**：AcPlugin.setup 早于 NumenCore 全量工具注册 → 构造期只桥接少数工具。
   修复：**惰性桥接**，ac_execute 前 `ensureBridged()` 幂等同步（跳过 ac_ 门面、只补未注册工具）。
2. **裸 JSON 结果**：非身体工具直接 complete 自定义 JSON（如 selfcompile_status 的
   `{"module":...,"state":...}`，无 success 字段）被误判 FAILED。
   修复：`BridgeResultMapper` 无 `success` 字段 → 视为工具成功产出的结构化数据 → SUCCESS(data)。
3. **重复包冲突**：plugins/ac 与 plugins/rdd 都内嵌 ac-core（含并行 AI 放的 rdd 包）
   → 同装 JPMS ResolutionException。
   修复：rdd 包机械迁出 ac-core → 独立 `rdd-core` 模块（git R100 rename，零逻辑改动）；
   plugins/rdd 依赖并内嵌 rdd-core；ac-core 恢复纯 AC 边界。
   验证：clean 重建后 ac jar 含 rdd 包 = 0，rdd jar 含 ac 包 = 0，隔离成立。
