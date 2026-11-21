package com.dwinovo.numen.plugins.learner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-22（主人止损）：内置学习者总开关默认关，只有显式 true 才开；坏文件/缺键不许悄悄打开。
 * 主人原话：「内部学习者和外置学习者重复，平时白烧钱；回归测试时才开」。
 */
class LearnerSwitchTest {

    @Test void defaultsOffAndCreatesDisabledFile(@TempDir Path dir) throws Exception {
        LearnerSwitch.reload(dir);
        assertFalse(LearnerSwitch.enabled(), "缺文件必须默认关，不能悄悄花钱");
        String raw = Files.readString(dir.resolve(LearnerSwitch.FILE_NAME), StandardCharsets.UTF_8);
        assertTrue(raw.contains("\"enabled\": false"), "首次运行应写一份默认关闭的开关文件: " + raw);
    }

    @Test void explicitTrueTurnsOnAndFalseTurnsOff(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(LearnerSwitch.FILE_NAME), "{\"enabled\": true}", StandardCharsets.UTF_8);
        LearnerSwitch.reload(dir);
        assertTrue(LearnerSwitch.enabled());
        // 关回来也必须生效（不许缓存死值，测试时开、平时关）
        Files.writeString(dir.resolve(LearnerSwitch.FILE_NAME), "{\"enabled\": false}", StandardCharsets.UTF_8);
        LearnerSwitch.reload(dir);
        assertFalse(LearnerSwitch.enabled());
    }

    @Test void malformedOrMissingKeyStaysOff(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(LearnerSwitch.FILE_NAME), "{not json", StandardCharsets.UTF_8);
        LearnerSwitch.reload(dir);
        assertFalse(LearnerSwitch.enabled());
        Files.writeString(dir.resolve(LearnerSwitch.FILE_NAME), "{\"other\": true}", StandardCharsets.UTF_8);
        LearnerSwitch.reload(dir);
        assertFalse(LearnerSwitch.enabled());
    }

    @Test void nullDirIsOff() {
        LearnerSwitch.reload(null);
        assertFalse(LearnerSwitch.enabled());
    }

    @Test void disabledMessageNamesToolAndConfig() {
        String msg = LearnerSwitch.disabledMessage("learner_review");
        assertTrue(msg.contains("learner_review"));
        assertTrue(msg.contains("learner.json"));
    }
}
