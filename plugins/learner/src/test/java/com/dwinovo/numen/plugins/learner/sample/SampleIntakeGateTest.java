package com.dwinovo.numen.plugins.learner.sample;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 样本层开关：默认关、缺文件不建、坏配置往关倒。
 *
 * <p>这些不是"顺手写死"的默认值 —— 每条都在 {@link SampleIntakeGate} 类注释里给了理由，
 * 这里只负责把它们钉住，防止以后有人为了让测试好写而把默认改成开。
 */
class SampleIntakeGateTest {

    @Test
    void 明确true才算开() {
        assertTrue(SampleIntakeGate.parse("{\"enabled\":true}").enabled());
    }

    @Test
    void 字符串true和数字1都不算开() {
        // 宽松解析会让「配置写错了」变成「悄悄开了」，而它的代价是制造没消费者的数据
        assertFalse(SampleIntakeGate.parse("{\"enabled\":\"true\"}").enabled());
        assertFalse(SampleIntakeGate.parse("{\"enabled\":1}").enabled());
        assertFalse(SampleIntakeGate.parse("{\"enabled\":\"yes\"}").enabled());
    }

    @Test
    void 显式false是关并说明原因() {
        SampleIntakeGate.State s = SampleIntakeGate.parse("{\"enabled\":false}");
        assertFalse(s.enabled());
        assertEquals("disabled_by_file", s.reason());
    }

    @Test
    void 坏JSON与缺字段都往关倒() {
        assertEquals("malformed", SampleIntakeGate.parse("{oops").reason());
        assertEquals("malformed", SampleIntakeGate.parse("{}").reason());
        assertEquals("malformed", SampleIntakeGate.parse("").reason());
        assertEquals("malformed", SampleIntakeGate.parse("[]").reason());
        assertFalse(SampleIntakeGate.parse("{oops").enabled());
    }

    @Test
    void 缺文件就是关且不自动建文件(@TempDir Path dir) throws IOException {
        SampleIntakeGate.State s = SampleIntakeGate.read(dir);
        assertFalse(s.enabled());
        assertEquals("no_file", s.reason());
        assertFalse(Files.exists(dir.resolve(SampleIntakeGate.FILE_NAME)),
                "自动建文件会让「默认关」看起来像被人特意配置过");
    }

    @Test
    void 有文件时按文件内容(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(SampleIntakeGate.FILE_NAME), "{\"enabled\":true}");
        assertTrue(SampleIntakeGate.read(dir).enabled());
        assertEquals("enabled_file", SampleIntakeGate.read(dir).reason());
    }

    @Test
    void 目录名对不上就是关(@TempDir Path dir) {
        assertFalse(SampleIntakeGate.read(dir.resolve("nope")).enabled());
        assertFalse(SampleIntakeGate.read(null).enabled());
    }
}
