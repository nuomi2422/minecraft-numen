package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.network.payload.CancelTasksPayload;
import com.dwinovo.numen.network.payload.ExecuteToolPayload;
import com.dwinovo.numen.platform.Services;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * numen-core's client-side tool transport — how a body-bound tool actually reaches
 * the server and comes back, entirely core's own packets. The engine scheduler
 * hands a {@link ToolCall} to a tool's {@code invoke}; a body-bound tool calls
 * {@link #ship}, and {@link #deliver} completes the original call.
 *
 * <p>WHY (2026-11-12): model IDs can repeat after cancellation or across companions.
 * Reusing them as wire keys lets an old result consume a new ToolCall. Each send
 * therefore gets its own bounded wire ID (41 characters built-in, 45 external);
 * Actuator mcp- calls retain that namespace for existing server routing. The parked
 * ToolCall retains its original model or actuator ID. This is not independent caller
 * authentication. No payload schema or server admission changes.
 * Unlike a generation platform or stale-ID cache, the existing in-flight map suffices.
 * This isolates replies; it does not revoke ExecuteToolPayload packets already sent.
 */
public final class ServerToolTransport {

    private static final Map<String, ToolCall> IN_FLIGHT = new ConcurrentHashMap<>();
    private record TicketWait(UUID entity, java.util.function.Consumer<String> reply, long expiresAt) {}
    private static final Map<String, TicketWait> TICKET_WAIT = new ConcurrentHashMap<>();
    private static final int MAX_TICKET_WAITS = 256;
    private static final long TICKET_WAIT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    private static final String TICKET_PREFIX = "yield-ticket-";

    private ServerToolTransport() {}

    /** Ship a body-bound tool under a fresh wire key while retaining its model call. */
    public static void ship(ToolCall call) {
        UUID entity = call.ctx().entityUuid();
        String wireArgs = call.rawArgs();
        if ("rdd_report_yield".equals(call.toolName())) {
            try {
                var args = com.google.gson.JsonParser.parseString(wireArgs).getAsJsonObject();
                args.remove("_yield_ticket");
                if (call.frozenYieldTicket() == null || call.frozenYieldTicket().isBlank()) {
                    call.complete(TaskResult.fail("yield ticket unavailable; refresh the current task and retry").toJson());
                    return;
                }
                args.addProperty("_yield_ticket", call.frozenYieldTicket());
                wireArgs = args.toString();
            } catch (RuntimeException invalid) {
                call.complete(TaskResult.fail("invalid yield arguments JSON").toJson());
                return;
            }
        }
        // Preserve the existing external routing namespace; this is not authentication.
        String prefix = call.id() != null && call.id().startsWith("mcp-") ? "mcp-wire-" : "wire-";
        String wireId;
        do {
            wireId = prefix + UUID.randomUUID();
        } while (IN_FLIGHT.putIfAbsent(wireId, call) != null);
        // Client association only: neither server acceptance nor a durable-log acknowledgement.
        LocalToolCallLog.publishTransportBinding(entity, call.id(), wireId, call.toolName());
        Services.NETWORK.sendToServer(
                new ExecuteToolPayload(entity, wireId, call.toolName(), wireArgs));
    }

    /** Start one internal ticket request; its response never enters the model tool queue. */
    public static String requestYieldTicket(UUID entity, java.util.function.Consumer<String> reply) {
        String id = TICKET_PREFIX + UUID.randomUUID();
        synchronized (TICKET_WAIT) {
            if (entity == null || reply == null || TICKET_WAIT.size() >= MAX_TICKET_WAITS) {
                if (reply != null) reply.accept(null);
                return id;
            }
            TICKET_WAIT.put(id, new TicketWait(entity, reply, System.nanoTime() + TICKET_WAIT_NANOS));
        }
        try { Services.NETWORK.sendToServer(new ExecuteToolPayload(entity, id, "_rdd_yield_ticket", "{}")); }
        catch (RuntimeException ex) { TICKET_WAIT.remove(id); reply.accept(null); }
        return id;
    }

    /** Suppress all internal-ticket IDs, including late/unknown replies, before logging. */
    public static boolean deliverInternalTicket(UUID entity, String id, String json) {
        if (id == null || !id.startsWith(TICKET_PREFIX)) return false;
        TicketWait wait = TICKET_WAIT.get(id);
        if (wait != null && wait.entity().equals(entity) && TICKET_WAIT.remove(id, wait)) {
            wait.reply().accept(System.nanoTime() - wait.expiresAt() >= 0 ? null : json);
        }
        return true;
    }

    public static void cancelYieldTicket(String id) { if (id != null) TICKET_WAIT.remove(id); }

    /** Called by the existing client tick: timeout is failure, never a replay. */
    public static void expireYieldTickets() { expireYieldTickets(System.nanoTime()); }
    static void expireYieldTickets(long now) {
        TICKET_WAIT.forEach((id, wait) -> {
            if (now - wait.expiresAt() >= 0 && TICKET_WAIT.remove(id, wait)) wait.reply().accept(null);
        });
    }

    /** A result echoes the wire key; only its original parked call can complete. */
    public static void deliver(String toolCallId, String resultJson) {
        ToolCall call = IN_FLIGHT.remove(toolCallId);
        if (call != null) call.complete(resultJson);
    }

    /** 主人按停止:忘掉停在这里的调用,并告诉身体住手。 */
    public static void abort(UUID companionUuid) {
        forget(companionUuid);
        Services.NETWORK.sendToServer(new CancelTasksPayload(companionUuid));
    }

    /**
     * 只忘掉停在这里的调用，<b>不动身体</b> —— 断线登出走这条。
     *
     * <p>那些调用属于一个已经结束的会话，结果再也回不来了；不忘就是一直长的账本。
     * 但身体不能叫停：她还在服务器里 tick，任务照样该跑完，收尾进离线出箱等主人回来。
     */
    public static void forget(UUID companionUuid) {
        IN_FLIGHT.values().removeIf(c -> companionUuid.equals(c.ctx().entityUuid()));
        TICKET_WAIT.entrySet().removeIf(e -> companionUuid.equals(e.getValue().entity()));
    }
}
