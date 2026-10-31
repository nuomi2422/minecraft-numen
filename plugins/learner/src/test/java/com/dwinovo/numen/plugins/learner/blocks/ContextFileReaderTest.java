package com.dwinovo.numen.plugins.learner.blocks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 尾部读：真的跳过、真的有界、读不到与没有分开。
 *
 * <p>「跳过没生效」是静默故障：读到的还是合法 JSON，只是全是旧的 ——
 * 所以必须有一条用例专门盯它。
 */
class ContextFileReaderTest {

    private static Path file(Path dir) throws IOException {
        Path f = dir.resolve(ContextFileReader.RELATIVE_PATH);
        Files.createDirectories(f.getParent());
        return f;
    }

    @Test
    void 缺文件是没读到不是没内容(@TempDir Path dir) {
        ContextFileReader.Read r = ContextFileReader.read(dir);
        assertFalse(r.ok(), "第一次跑就是没有这份日志，但必须与读失败区分");
        assertTrue(r.error().contains("no context.jsonl"));
        assertTrue(r.lines().isEmpty());
    }

    @Test
    void 只读尾部而不是从文件头读(@TempDir Path dir) throws IOException {
        Path f = file(dir);
        Files.writeString(f, "first-line\nsecond-line\nthird-line\n", StandardCharsets.UTF_8);

        // 尾部只给 12 字节：只要 skip 真的生效，就绝不会看到 first-line
        ContextFileReader.Read r = ContextFileReader.read(dir, 12, 100);

        assertTrue(r.ok());
        assertFalse(r.lines().contains("first-line"), "从文件头读起来会静默拿到旧数据");
        assertTrue(r.lines().stream().anyMatch(l -> l.contains("third-line")), "尾部必须在");
    }

    @Test
    void 行数有上限且保留最近的(@TempDir Path dir) throws IOException {
        Path f = file(dir);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("line-").append(i).append('\n');
        }
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);

        ContextFileReader.Read r = ContextFileReader.read(dir, 1_000_000, 5);
        assertTrue(r.ok());
        assertEquals(5, r.lines().size());
        assertTrue(r.lines().get(4).contains("line-49"), "留最近的行，不是最早的");
    }

    @Test
    void 空行被丢掉(@TempDir Path dir) throws IOException {
        Path f = file(dir);
        Files.writeString(f, "a\n\n\nb\n", StandardCharsets.UTF_8);
        ContextFileReader.Read r = ContextFileReader.read(dir, 1_000_000, 100);
        assertEquals(2, r.lines().size());
    }

    @Test
    void 读不动时降级不抛(@TempDir Path dir) throws IOException {
        // 把路径占成目录 → Files.size / newInputStream 必失败
        Files.createDirectories(dir.resolve(ContextFileReader.RELATIVE_PATH));
        ContextFileReader.Read r = ContextFileReader.read(dir);
        assertFalse(r.ok());
        assertFalse(r.error().isBlank());
    }

    @Test
    void 空目录参数安全() {
        assertFalse(ContextFileReader.read(null).ok());
    }
}
