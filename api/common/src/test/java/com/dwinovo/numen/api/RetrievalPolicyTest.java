package com.dwinovo.numen.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * G5d 判据 S2（模式开关）的离线证据：{@link RetrievalPolicy} 的默认值、
 * 解析口径与「改完不重启就生效」。
 *
 * <p>全部用临时文件，不读真实 {@code config/numen/}，不起游戏。
 */
class RetrievalPolicyTest {

    @TempDir
    Path dir;

    private Path policy(String json) throws Exception {
        Path file = dir.resolve(RetrievalPolicy.FILE_NAME);
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void 文件不存在_默认legacy() {
        assertEquals(RetrievalPolicy.Mode.LEGACY,
                RetrievalPolicy.mode(dir.resolve("nope.json")));
    }

    @Test
    void 路径为null_退legacy() {
        assertEquals(RetrievalPolicy.Mode.LEGACY, RetrievalPolicy.mode(null));
    }

    @Test
    void unified显式配置_读到unified() throws Exception {
        assertEquals(RetrievalPolicy.Mode.UNIFIED,
                RetrievalPolicy.mode(policy("{\"experienceRetrievalMode\":\"unified\"}")));
    }

    @Test
    void 大小写与空白不算错() throws Exception {
        assertEquals(RetrievalPolicy.Mode.UNIFIED,
                RetrievalPolicy.mode(policy("{\"experienceRetrievalMode\":\"  UNIFIED  \"}")));
    }

    @Test
    void legacy显式配置_读到legacy() throws Exception {
        assertEquals(RetrievalPolicy.Mode.LEGACY,
                RetrievalPolicy.mode(policy("{\"experienceRetrievalMode\":\"legacy\"}")));
    }

    @Test
    void 坏JSON_退legacy不抛异常() throws Exception {
        assertEquals(RetrievalPolicy.Mode.LEGACY,
                RetrievalPolicy.mode(policy("{不是 json")));
    }

    @Test
    void 未知值_退legacy不猜() throws Exception {
        assertEquals(RetrievalPolicy.Mode.LEGACY,
                RetrievalPolicy.mode(policy("{\"experienceRetrievalMode\":\"halfway\"}")));
    }

    @Test
    void 缺键_退legacy() throws Exception {
        assertEquals(RetrievalPolicy.Mode.LEGACY,
                RetrievalPolicy.mode(policy("{\"other\":\"unified\"}")));
    }

    @Test
    void 改文件不重启_下一读就生效() throws Exception {
        Path file = policy("{\"experienceRetrievalMode\":\"legacy\"}");
        assertEquals(RetrievalPolicy.Mode.LEGACY, RetrievalPolicy.mode(file));

        // 重写内容并把 mtime 往后推，模拟「人改了配置」——不重建对象、不重启
        Files.writeString(file, "{\"experienceRetrievalMode\":\"unified\"}", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        assertEquals(RetrievalPolicy.Mode.UNIFIED, RetrievalPolicy.mode(file));

        // 再改回去也要立刻生效（S5 的回退判据靠这个）
        Files.writeString(file, "{\"experienceRetrievalMode\":\"legacy\"}", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        assertEquals(RetrievalPolicy.Mode.LEGACY, RetrievalPolicy.mode(file));
    }
}
