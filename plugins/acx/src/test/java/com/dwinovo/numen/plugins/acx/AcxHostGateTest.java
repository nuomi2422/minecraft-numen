package com.dwinovo.numen.plugins.acx;

import com.dwinovo.numen.acx.api.AcxStepOutcome;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 037/T6 · ASYNC-01：超时/取消之后不许补跑，也不许把"已经开跑"说成"撤销成功"。
 *
 * <p>用可手动推进的假宿主线程，不睡等时序。宿主线程用 {@link AcxHostGate.HostThread}，
 * 真实服务端不在场。
 */
class AcxHostGateTest {

    /** 假宿主线程：把任务攒起来，由测试决定什么时候跑。 */
    private static final class ManualHost implements AcxHostGate.HostThread {
        final Deque<Runnable> queue = new ArrayDeque<>();
        int executed;

        @Override public boolean onServerThread() { return false; }

        @Override public void execute(Runnable task) { queue.add(task); }

        /** 服务端线程"之后"把这些任务跑掉。 */
        int drain() {
            int n = 0;
            while (!queue.isEmpty()) {
                queue.poll().run();
                executed++;
                n++;
            }
            return n;
        }
    }

    @Test
    void expiredBeforeStartNeverExecutesTheStep() throws Exception {
        ManualHost host = new ManualHost();
        AcxHostGate gate = new AcxHostGate(60, () -> host);
        AtomicInteger calls = new AtomicInteger();

        TimeoutException ex = assertThrows(TimeoutException.class,
                () -> gate.call(() -> { calls.incrementAndGet(); return AcxStepOutcome.success(java.util.Map.of()); }));
        assertTrue(ex.getMessage().contains("will not run later"), ex.getMessage());

        assertEquals(1, host.drain(), "排队的闭包应该还在，被叫醒一次");
        assertEquals(0, calls.get(), "已经放弃的调用绝不能补跑（ASYNC-01）");
    }

    @Test
    void startedStepIsReportedAsUnknownNotCancelled() throws Exception {
        // 任务开跑后卡住：超时方只能承认"不知道结果"，不能声称撤销。
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        AcxHostGate.HostThread inlineHost = new AcxHostGate.HostThread() {
            @Override public boolean onServerThread() { return false; }
            @Override public void execute(Runnable task) {
                Thread t = new Thread(() -> { task.run(); }, "fake-server");
                t.setDaemon(true);
                t.start();
            }
        };
        AcxHostGate gate = new AcxHostGate(80, () -> inlineHost);

        TimeoutException ex = assertThrows(TimeoutException.class, () -> gate.call(() -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return AcxStepOutcome.success(java.util.Map.of());
        }));
        assertTrue(ex.getMessage().contains("already started"), ex.getMessage());
        assertTrue(ex.getMessage().contains("outcome unknown"), ex.getMessage());
        assertEquals(1, calls.get(), "已经开跑的步骤确实跑过一次（不许说成被撤销）");
        release.countDown();
    }

    @Test
    void manualHostRunsQueuedStepNormally() throws Exception {
        ManualHost host = new ManualHost();
        AcxHostGate gate = new AcxHostGate(2_000, () -> host);
        Thread runner = new Thread(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            host.drain();
        });
        runner.start();
        AcxStepOutcome outcome = gate.call(() -> AcxStepOutcome.success(java.util.Map.of("ok", true)));
        runner.join();
        assertTrue(outcome.isSuccess(), outcome.toString());
        assertEquals(1, host.executed, "drain 应恰好执行过一次");
    }

    @Test
    void sameThreadBypassesTheQueue() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        AcxHostGate.HostThread host = new AcxHostGate.HostThread() {
            @Override public boolean onServerThread() { return true; }
            @Override public void execute(Runnable task) { throw new AssertionError("同线程不该排队"); }
        };
        AcxHostGate gate = new AcxHostGate(50, () -> host);
        AcxStepOutcome outcome = gate.call(() -> {
            executed.incrementAndGet();
            return AcxStepOutcome.success(java.util.Map.of());
        });
        assertTrue(outcome.isSuccess());
        assertEquals(1, executed.get());
    }

    @Test
    void noServerRunsInline() throws Exception {
        AcxHostGate gate = new AcxHostGate(50, () -> null);
        assertTrue(gate.call(() -> AcxStepOutcome.success(java.util.Map.of())).isSuccess());
    }
}
