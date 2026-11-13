package com.dwinovo.numen.api;

import com.dwinovo.numen.api.NumenPlugins.DeliveredContext;
import com.dwinovo.numen.api.NumenPlugins.DeliveredRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The existing request-delivery registration and dispatch path, without Minecraft bootstrap.
 * 2026-11-13: NumenPlugins.API eagerly creates Impl; loading that facade in a pure consumer
 * test pulls in MC payload classes. Keep its initialization unchanged and delegate here instead.
 * This owns the original two sink lists, not a second ledger or a parallel dispatch implementation.
 */
public final class DeliveredContextDispatch {
    private static final Logger LOG = LoggerFactory.getLogger(DeliveredContextDispatch.class);
    private static final List<Consumer<DeliveredContext>> CONTEXT_DELIVERED_SINKS = new CopyOnWriteArrayList<>();
    private static final List<java.util.function.BiConsumer<UUID, List<DeliveredRef>>> DELIVERED_SINKS
            = new CopyOnWriteArrayList<>();

    private DeliveredContextDispatch() {}

    public static void sinkRequestDeliveredWithContext(Consumer<DeliveredContext> sink) {
        if (sink != null) CONTEXT_DELIVERED_SINKS.add(sink);
    }

    public static void sinkRequestDelivered(java.util.function.BiConsumer<UUID, List<DeliveredRef>> sink) {
        if (sink != null) DELIVERED_SINKS.add(sink);
    }

    public static void deliveryDispatched(
            java.util.concurrent.atomic.AtomicReference<DeliveredContext> pending,
            String type, java.util.Map<String, ?> event) {
        if (pending == null || !"llm_request".equals(type) || event == null
                || !"dispatched".equals(event.get("status"))) return;
        DeliveredContext captured = pending.get();
        if (captured == null || captured.requestId().isBlank()
                || !captured.requestId().equals(event.get("requestId"))) return;
        if (pending.compareAndSet(captured, null)) requestDelivered(captured);
    }

    public static void requestDelivered(DeliveredContext context) {
        if (context == null || context.companion() == null || context.refs().isEmpty()) return;
        List<DeliveredRef> kept = context.refs().stream().filter(r -> !r.truncated()).toList();
        if (kept.isEmpty()) return;
        DeliveredContext delivered = new DeliveredContext(context.companion(), context.worldToken(),
                context.requestId(), context.phase(), context.role(), context.taskId(), context.taskToolCallId(), kept);
        for (java.util.function.BiConsumer<UUID, List<DeliveredRef>> sink : DELIVERED_SINKS) {
            try {
                sink.accept(delivered.companion(), kept);
            } catch (Throwable e) {
                LOG.error("[numen] request_delivered 落账失败（观测旁路，不挡请求）", e);
            }
        }
        for (Consumer<DeliveredContext> sink : CONTEXT_DELIVERED_SINKS) {
            try {
                sink.accept(delivered);
            } catch (Throwable e) {
                LOG.error("[numen] contextual delivery sink failed (request continues)", e);
            }
        }
    }

}
