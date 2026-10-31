package com.dwinovo.numen.plugins.learner.sample;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** TTL 配置：缺文件/坏文件回退默认，分钟档只用于验证。 */
class SampleTtlConfigTest {

    private static final long DAY = 24 * 60 * 60 * 1000L;

    private static void write(Path dir, String json) throws IOException {
        Files.writeString(dir.resolve(SampleTtlConfig.FILE), json, StandardCharsets.UTF_8);
    }

    @Test
    void 缺文件回退七天(@TempDir Path dir) {
        assertEquals(7 * DAY, SampleTtlConfig.ttlMillis(dir));
        assertEquals(7 * DAY, SampleTtlConfig.ttlMillis(null));
    }

    @Test
    void 天数生效(@TempDir Path dir) throws IOException {
        write(dir, "{\"days\": 2}");
        assertEquals(2 * DAY, SampleTtlConfig.ttlMillis(dir));
    }

    @Test
    void 分钟档只用于验证(@TempDir Path dir) throws IOException {
        write(dir, "{\"minutes\": 1}");
        assertEquals(60_000L, SampleTtlConfig.ttlMillis(dir));
        write(dir, "{\"minutes\": 0}");
        assertEquals(60_000L, SampleTtlConfig.ttlMillis(dir), "分钟档最小 1 分钟，防止 0 变成永不归档");
    }

    @Test
    void 天数被夹在合法范围(@TempDir Path dir) throws IOException {
        write(dir, "{\"days\": 0}");
        assertEquals(DAY, SampleTtlConfig.ttlMillis(dir), "0 天等于立刻归档，显然不是本意");
        write(dir, "{\"days\": 9999}");
        assertEquals(SampleTtlConfig.MAX_DAYS * DAY, SampleTtlConfig.ttlMillis(dir));
    }

    @Test
    void 坏文件回退默认而不是停掉归档(@TempDir Path dir) throws IOException {
        write(dir, "{not json");
        assertEquals(7 * DAY, SampleTtlConfig.ttlMillis(dir));
        write(dir, "{}");
        assertEquals(7 * DAY, SampleTtlConfig.ttlMillis(dir));
    }
}
