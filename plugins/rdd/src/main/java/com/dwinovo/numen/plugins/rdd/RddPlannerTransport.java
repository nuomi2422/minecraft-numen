package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.llm.LlmEndpoint;
import java.util.UUID;
import java.util.function.Consumer;

/** Owner-selected planner transport lifecycle only. No world sampling, prompts, retries or budget state.
 * Endpoint lookup, client executor and existing admission predicate remain supplied by the caller. */
final class RddPlannerTransport {
    private RddPlannerTransport() {}

    /**
     * 规划层的"思考力度"（2026-10-07 用户裁决：生成太慢，思维过程不再需要）。
     *
     * <p>能表达"关"的站点（thinking 开关型 / enable-bool / budget / effort-none 方言）
     * 直接 {@code off}；effort 族站点没有"关"的线格式（off 会被静默丢掉 = 等于没改），
     * 退回 {@code low} 至少把思考量压下来。opencode-go 当前是 effort 族（今天发 low，
     * 实测明显变快）；等 api jar 带上 effort-none 方言后这里自动升级成真正的 off，
     * 不需要再改这段代码。
     */
    private static String plannerReasoningEffort(String provider) {
        String format = "";
        try {
            format = com.dwinovo.numen.agent.provider.ProviderRegistry
                    .thinkingFormat(provider);
        } catch (RuntimeException ignored) {
            // 读不到站点方言就按 effort 族兜底
        }
        if (format == null || format.isBlank()
                || com.dwinovo.numen.agent.provider.LlmProvider.THINKING_EFFORT.equals(format)
                || com.dwinovo.numen.agent.provider.LlmProvider.THINKING_EFFORT_NESTED.equals(format)) {
            return "low";
        }
        return "off";
    }

    static <T> void withPlannerEndpoint(UUID companionId, String stage,
            java.util.function.Function<UUID, java.util.concurrent.CompletableFuture<LlmEndpoint>> lookup,
            java.util.concurrent.Executor client, java.util.function.LongSupplier generation,
            java.util.function.BiPredicate<String, Long> budget,
            java.util.function.Function<LlmEndpoint, java.util.concurrent.CompletableFuture<T>> send,
            Consumer<T> onResult, Runnable onFail) {
        long originalGeneration = generation.getAsLong();
        java.util.concurrent.CompletableFuture<LlmEndpoint> pending;
        try { pending = lookup.apply(companionId); }
        catch (RuntimeException unavailable) { pending = java.util.concurrent.CompletableFuture.failedFuture(unavailable); }
        if (pending == null) pending = java.util.concurrent.CompletableFuture.completedFuture(null);
        pending.whenComplete((selected, error) -> dispatchPlanner(client, () -> {
            if (error != null || selected == null || !selected.hasApiKey()
                    || generation.getAsLong() != originalGeneration) {
                onFail.run();
                return;
            }
            LlmEndpoint endpoint;
            boolean allowed;
            try {
                endpoint = new LlmEndpoint(selected.provider(), selected.model(), selected.apiKey(),
                        selected.baseUrl(), selected.proxy(), plannerReasoningEffort(selected.provider()));
                allowed = budget.test(stage, 0L) && generation.getAsLong() == originalGeneration;
            } catch (RuntimeException unavailable) {
                onFail.run();
                return;
            }
            if (!allowed) { onFail.run(); return; }
            java.util.concurrent.CompletableFuture<T> response;
            try { response = java.util.Objects.requireNonNull(send.apply(endpoint)); }
            catch (RuntimeException failed) { onFail.run(); return; }
            response.whenComplete((result, failed) -> dispatchPlanner(client, () -> {
                if (failed != null) onFail.run();
                else onResult.accept(result);
            }, onFail));
        }, onFail));
    }

    private static void dispatchPlanner(java.util.concurrent.Executor client, Runnable task, Runnable onFail) {
        var started = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            client.execute(() -> { started.set(true); task.run(); });
        } catch (RuntimeException rejected) {
            // An inline caller callback throwing is not a scheduling rejection; never fail it twice.
            if (started.get()) throw rejected;
            onFail.run();
        }
    }
}
