package com.dwinovo.numen.plugins.acx;

import com.dwinovo.numen.acx.api.AcxGameThreadGate;
import com.dwinovo.numen.acx.api.AcxStepOutcome;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * {@link AcxGameThreadGate} 的宿主实现（契约 ACX-G1）。
 *
 * <p>已经在服务端线程 → 直接执行；在 AC 执行器线程 → 提交到服务端线程并等结果。
 * 服务端不在跑时（关服/单测）退化为直接执行 —— 此时世界读写本来也不成立。</p>
 *
 * <p><b>2026-11-04（037/T6 · ASYNC-01）</b>：超时不再只是"等不到"，而是要和"会不会补跑"绑在一起。
 * 改前：提交到服务端线程后 {@code future.get(timeout)} 超时，排队的 lambda 仍留在队列里，
 * 之后服务端线程照常执行 —— 调用方已经按 TIMEOUT 收场，副作用却晚到，这就是"超时后迟到执行"。
 * 现在排队闭包开头先用原子状态争用执行权：调用方放弃（超时/取消）时把状态 CAS 到 ABANDONED，
 * 闭包看到自己没抢到就<b>绝不调用 task.call()</b>。反过来，如果闭包已经开跑，
 * 超时方不得声称"撤销成功"，异常消息会明确写 {@code already started; outcome unknown}。</p>
 */
public final class AcxHostGate implements AcxGameThreadGate {

    public static final long DEFAULT_TIMEOUT_MS = 30_000;

    /** 排队中（还没抢到执行权）。 */
    private static final int QUEUED = 0;
    /** 已经开跑，副作用可能已发生。 */
    private static final int RUNNING = 1;
    /** 调用方已放弃，闭包不许再执行。 */
    private static final int ABANDONED = 2;

    /** 宿主线程句柄：是否在服务端线程、把任务排到服务端线程。 */
    interface HostThread {
        boolean onServerThread();

        void execute(Runnable task);
    }

    private final long timeoutMs;
    private final Supplier<HostThread> host;

    public AcxHostGate() {
        this(DEFAULT_TIMEOUT_MS);
    }

    public AcxHostGate(long timeoutMs) {
        this(timeoutMs, AcxHostGate::currentServer);
    }

    /** 测试入口：自己给宿主线程，不需要真服务端。 */
    AcxHostGate(long timeoutMs, Supplier<HostThread> host) {
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        this.host = host;
    }

    private static HostThread currentServer() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        return new HostThread() {
            @Override
            public boolean onServerThread() {
                return server.isSameThread();
            }

            @Override
            public void execute(Runnable task) {
                server.execute(task);
            }
        };
    }

    @Override
    public AcxStepOutcome call(Callable<AcxStepOutcome> task) throws Exception {
        HostThread thread = host.get();
        if (thread == null || thread.onServerThread()) {
            return task.call();
        }
        // 1.21.1 的 MinecraftServer 只有 execute(Runnable)，没有 submit(Callable)；
        // 用 CompletableFuture 手动把结果带回来。
        AtomicInteger state = new AtomicInteger(QUEUED);
        CompletableFuture<AcxStepOutcome> future = new CompletableFuture<>();
        thread.execute(() -> {
            if (!state.compareAndSet(QUEUED, RUNNING)) {
                return;   // 调用方已经放弃：绝不补跑（ASYNC-01）
            }
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            if (state.compareAndSet(QUEUED, ABANDONED)) {
                throw new TimeoutException("acx gate timed out after " + timeoutMs
                        + "ms before the step started; it will not run later");
            }
            throw new TimeoutException("acx gate timed out after " + timeoutMs
                    + "ms but the step had already started; outcome unknown, do not treat as cancelled");
        }
    }
}
