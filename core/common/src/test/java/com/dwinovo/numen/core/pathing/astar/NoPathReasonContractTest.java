package com.dwinovo.numen.core.pathing.astar;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NO_PATH 回执把真实搜索原因传出来:非失败不得携带原因、人话不冒充预算。 */
class NoPathReasonContractTest {

    private static String source(String relative) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("core/common/src/main/java"))) {
            root = root.getParent();
        }
        assertTrue(root != null, "找不到 core/common/src/main/java");
        return Files.readString(root.resolve("core/common/src/main/java/com/dwinovo/numen/core").resolve(relative));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    @Test
    void noPathAutopsyUsesOneLocalNameAndClosesTheParenOnce() throws Exception {
        String src = source("pathing/execute/PlayerNav.java");
        int start = src.indexOf("private String noPathAutopsy(");
        assertTrue(start > 0, "noPathAutopsy 必须存在");
        int end = src.indexOf("\n    }", start);
        String body = src.substring(start, end);
        assertTrue(body.contains("PathCalcResult.StopReason searchReason"),
                "局部必须叫 searchReason，不能与后面 String reason 撞名");
        assertFalse(body.contains("StopReason reason ="),
                "不得再有 StopReason reason 局部");
        assertTrue(body.contains("searchReason.describe()"), "原因必须由 describe() 输出");
        assertTrue(body.contains("(%s; %s"), "拼人话时不能提前闭合括号");
    }

    @Test
    void pathingCoreResetsReasonOnTickAndOnNewSearch() throws Exception {
        String src = source("pathing/execute/PathingCore.java");
        assertTrue(count(src, "lastFailedStopReason = PathCalcResult.StopReason.UNKNOWN;") >= 2,
                "每 tick 与每次新搜索都要重置 UNKNOWN");
        assertTrue(src.contains("lastFailedStopReason = result.getStopReason();"),
                "失败结果必须写入真实原因");
    }

    @Test
    void nonFailureResultsCannotCarryARealStopReason() {
        assertThrows(IllegalArgumentException.class,
                () -> new PathCalcResult(PathCalcResult.Type.CANCELLATION, null, PathCalcResult.StopReason.TIMEOUT));
        assertThrows(IllegalArgumentException.class,
                () -> new PathCalcResult(PathCalcResult.Type.EXCEPTION, null, PathCalcResult.StopReason.NODE_CAP));
        assertDoesNotThrow(() -> new PathCalcResult(PathCalcResult.Type.FAILURE, null, PathCalcResult.StopReason.TIMEOUT));
        assertEquals(PathCalcResult.StopReason.UNKNOWN,
                new PathCalcResult(PathCalcResult.Type.CANCELLATION).getStopReason());
    }

    @Test
    void stopReasonTextIsHonest() {
        String unknown = PathCalcResult.StopReason.UNKNOWN.describe().toLowerCase();
        assertFalse(unknown.contains("burned"));
        assertTrue(unknown.contains("unknown"));
        assertTrue(PathCalcResult.StopReason.TIMEOUT.describe().toLowerCase().contains("time"));
        assertTrue(PathCalcResult.StopReason.EXHAUSTED.describe().toLowerCase().contains("exhausted"));
    }
}
