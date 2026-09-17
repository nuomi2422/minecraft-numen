package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.tool.ServerToolTransport;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server-to-client payload: result of a previously requested tool execution.
 * Server drains task outboxes each tick and ships completed results back to
 * the owning player; the player's {@code EntityAgentLoop} feeds them into
 * the LLM conversation as {@code role:tool} messages, then triggers the
 * next turn when all pending results are in.
 *
 * <h2>Pairing</h2>
 * {@link #toolCallId} matches the one in the originating {@link ExecuteToolPayload}
 * and, transitively, the LLM's tool_call.id — this is the field that
 * threads request→execution→reply through the network boundary.
 *
 * <h2>Result body</h2>
 * Pre-serialised JSON string ({@link com.dwinovo.numen.task.TaskResult#toJson}).
 * Server-side decisions about field shape live in {@code TaskResult}; the
 * network layer just shuttles bytes.
 */
public record TaskResultPayload(UUID entityUuid,
                                 String toolCallId,
                                 String resultJson) implements CustomPacketPayload {

    public static final int MAX_TOOL_CALL_ID_LENGTH = 128;
    public static final int MAX_RESULT_JSON_LENGTH = 16 * 1024;

    /**
     * 出口硬闸：{@code ByteBufCodecs.stringUtf8(MAX_RESULT_JSON_LENGTH)} 在编码超长字符串时抛
     * {@code EncoderException: String too big}，直接打断客户端连接。
     *
     * <p>真机实测：{@code rdd_assets} 返回全量世界资产（105 条 → 52,702 字符），把主人踢下线，
     * 单人服务端因玩家登出而停止，整场自主跑死在这里，而日志里只剩一串 netty 编码栈。
     *
     * <p>网络层只负责运字节，不该指望每个工具自己记得"别超 16 KB"，所以在构造处统一钳位。
     * 下游（{@code ServerToolTransport.deliver → ToolDispatcher.complete}）只把结果当文本转发，
     * 不解析 JSON，因此截断加标记是安全的；标记会明确告诉模型"结果被截断、请缩小查询"。
     */
    public TaskResultPayload {
        resultJson = clamp(resultJson);
    }

    private static String clamp(String json) {
        if (json == null) return "";
        if (json.length() <= MAX_RESULT_JSON_LENGTH) return json;
        String marker = "...[truncated: result was " + json.length()
                + " chars, over the " + MAX_RESULT_JSON_LENGTH
                + "-char packet limit; re-run with a narrower query]";
        int keep = MAX_RESULT_JSON_LENGTH - marker.length() - 1;
        return json.substring(0, Math.max(0, keep)) + marker;
    }

    public static final Type<TaskResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "task_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TaskResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, TaskResultPayload::entityUuid,
                    ByteBufCodecs.stringUtf8(MAX_TOOL_CALL_ID_LENGTH), TaskResultPayload::toolCallId,
                    ByteBufCodecs.stringUtf8(MAX_RESULT_JSON_LENGTH), TaskResultPayload::resultJson,
                    TaskResultPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on client main thread (network layer arranges that). */
    public static void handle(TaskResultPayload p) {
        Constants.LOG.debug("[numen-net] task_result entity={} tool_call_id={} → {}",
                p.entityUuid(), p.toolCallId(), truncate(p.resultJson(), 200));
        ServerToolTransport.deliver(p.toolCallId(), p.resultJson());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        // 原来是 substring(0, max) + "..."，实际长度 max+3，仍然超限；这里让结果落在 max 之内。
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 3)) + "...";
    }
}
