package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时钟同源的**源码级**守卫（审计实测过的那类错误：写入 getTickCount、读取 getGameTime）。
 *
 * <p>纯函数测试抓不到这个——isFresh 从来没错过，错的是喂给它的两个数。
 * 所以这里直接看源码：RddDangerFacts 只允许用 currentTick(...) 取 tick。
 */
class DangerClockSourceTest {

    private static Path source() {
        return Path.of("src/main/java/com/dwinovo/numen/plugins/rdd/RddDangerFacts.java");
    }

    @Test
    void dangerFactsNeverUsesGameTimeDirectly() throws Exception {
        String src = Files.readString(source(), StandardCharsets.UTF_8);
        assertFalse(src.contains("getGameTime("),
                "RddDangerFacts 里出现 getGameTime() —— 写入/读取会不同源，重启后快照永远不新鲜");
    }

    @Test
    void dangerFactsHasSingleTickSource() throws Exception {
        String src = Files.readString(source(), StandardCharsets.UTF_8);
        assertTrue(src.contains("static long currentTick(ServerLevel level)"),
                "必须保留唯一的 currentTick(level) 作为时钟源");
        int uses = 0;
        for (int i = src.indexOf("currentTick("); i >= 0; i = src.indexOf("currentTick(", i + 1)) uses++;
        assertTrue(uses >= 3, "currentTick 至少出现三次（定义处 + 写入 + 读取），实际=" + uses);
    }
}
