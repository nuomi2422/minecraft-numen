package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Wiring-only capability acceptance; does not fire the bus or drive Minecraft. */
class RddCompletedMoveWiringTest {
    @Test void rddRegistersCompletedTaskConsumer() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddPlugin.java"))) root = root.getParent();
        assertNotNull(root, "SETUP: RddPlugin source missing");
        String source = Files.readString(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddPlugin.java"));
        assertTrue(source.contains("numen.on(CompanionEvent.TASK_FINISHED"),
                "RDD must subscribe to task terminal events before it can project completed_move; this is wiring only");
    }
}
