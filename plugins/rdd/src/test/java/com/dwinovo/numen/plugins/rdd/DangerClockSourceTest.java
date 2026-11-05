package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tick 来源的**结构守卫**（审计实测过的事故：写入 getTickCount、读取 getGameTime）。
 *
 * <p>纯函数测试抓不到它；而且守卫只看一个文件也不够——事故恰恰是**两个文件用了两个时钟**。
 * 所以这里扫两个文件，并且**先剥注释**（注释里提到方法名不该算数）。
 */
class DangerClockSourceTest {

    private static final String ROOT = "src/main/java/com/dwinovo/numen/plugins/rdd/";

    private static String code(String file) throws Exception {
        String raw = Files.readString(Path.of(ROOT + file), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    @Test
    void noFileCallsTickCountDirectly() throws Exception {
        for (String f : List.of("RddDangerFacts.java", "RddDetector.java")) {
            assertFalse(code(f).contains("getServer().getTickCount("),
                    f + " 直接调了 getServer().getTickCount() —— 必须走 RddClock.current(level)");
        }
    }

    @Test
    void dangerFactsNeverUsesGameTime() throws Exception {
        // 危险快照的写/读都基于 tick；detector 里的 getGameTime 是给"暂停计时"用的（需要跨重启），
        // 两者用途不同，所以这条只约束 RddDangerFacts。
        assertFalse(code("RddDangerFacts.java").contains("getGameTime("),
                "RddDangerFacts 用了 getGameTime() —— 与 tick 写入侧不同源，重启后快照永远不新鲜");
    }

    @Test
    void singleClockSourceExists() throws Exception {
        String clock = Files.readString(Path.of(ROOT + "RddClock.java"), StandardCharsets.UTF_8);
        assertTrue(clock.contains("getServer().getTickCount()"),
                "RddClock 是非服务端 tick 的唯一入口，应该只有它调 getTickCount");
        assertTrue(code("RddDetector.java").contains("RddClock.current("), "detector 必须走唯一入口");
        assertTrue(code("RddDangerFacts.java").contains("RddClock.current(")
                        || code("RddDangerFacts.java").contains("currentTick("),
                "危险快照必须走唯一入口");
    }

    @Test
    void containerThrottleParamIsNamedTick() throws Exception {
        String src = code("RddContainerMemory.java");
        assertFalse(src.contains("long gameTime"), "参数名 base 叫 tick，别再叫 gameTime（名字会骗人）");
    }
}
