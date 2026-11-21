package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.AcxEventSink;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotAtomicityTest {
    @Test void cancellationAndPositionCommitUseSameLockInBothOrders() throws Exception {
        Class<?> ctxClass = Class.forName("com.dwinovo.numen.acx.core.AcxRunner$Ctx");
        Constructor<?> ctor = ctxClass.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        Method save = AcxRunner.class.getDeclaredMethod("saveFramePosition", ctxClass, String.class, int.class, Map.class, Map.class);
        save.setAccessible(true);
        Field varsField = ctxClass.getDeclaredField("vars"); varsField.setAccessible(true);
        var pool = Executors.newSingleThreadExecutor();
        try {
            for (boolean cancelledFirst : List.of(true, false)) {
                var flag = new AtomicBoolean(false);
                Object ctx = ctor.newInstance("run", 0L, Long.MAX_VALUE, flag, AcxRuntimeLimits.defaults(), AcxEventSink.noop(), "test", 0, false);
                @SuppressWarnings("unchecked") Map<String,Object> vars = (Map<String,Object>) varsField.get(ctx);
                var ready = new CountDownLatch(1);
                Future<Boolean> result;
                synchronized (flag) {
                    result = pool.submit(() -> { ready.countDown(); return (Boolean) save.invoke(null, ctx, "_cursor_x/", 1, Map.of("values", Collections.nCopies(65, "v")), Map.of()); });
                    assertTrue(ready.await(5, TimeUnit.SECONDS));
                    if (cancelledFirst) flag.set(true);
                }
                assertEquals(!cancelledFirst, result.get(5, TimeUnit.SECONDS));
                if (!cancelledFirst) synchronized (flag) { flag.set(true); }
                assertEquals(!cancelledFirst, vars.containsKey("_cursor_x/"));
                assertEquals(!cancelledFirst, vars.containsKey("_snapshot_truncated__cursor_x/"));
            }
        } finally { pool.shutdownNow(); }
    }
}
