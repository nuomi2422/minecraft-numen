package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RddStagePlannerAcceptancePromptTest {
    @Test
    void finalAcceptanceIsNotAnEarlyCriticalWaitFor() throws Exception {
        Path cwd = Paths.get("").toAbsolutePath();
        Path source = null;
        for (Path cur = cwd; cur != null; cur = cur.getParent()) {
            Path candidate = cur.resolve("src/main/java/com/dwinovo/numen/plugins/rdd/RddStagePlanner.java");
            if (Files.isRegularFile(candidate)) {
                source = candidate;
                break;
            }
        }
        assertTrue(source != null, "RddStagePlanner source not found");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        assertTrue(text.contains("最终验收、长期储备或终点数量只是终点义务"));
        assertTrue(text.contains("不得把熟食64、两套备用等终点条件变成第一阶段CRITICAL前置"));
        assertTrue(text.contains("最终验收/长期储备不得写成早期wait_for或CRITICAL前置"));
    }
}
