package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生存路线（doctrine）红线测试——RL-23/RL-24 的机核（2026-10-24 修复轮建立）。
 *
 * <p><b>为什么这组测试必须存在</b>：audit-sweep-20261024 的变异实验实证——把 doctrine 注入
 * 功能整体静默删除，测试套件 fail 反而从 22 降到 8（无任何新增失败）：该功能此前<b>零断言保护</b>。
 * 「主人要求的每次规划注入生存路线」是功能，不是注释；功能删了必须有测试红。
 *
 * <p><b>五个场景各锁什么</b>：
 * <ol>
 *   <li>文件存在 → block() 含标题与正文（功能正向锁，RL-23）；</li>
 *   <li>文件缺失 → 空串、不注入空标题（不污染 prompt）；</li>
 *   <li>超 MAX_CHARS → 截断标记（上限契约）；</li>
 *   <li>类加载失败（Error）→ 空串非崩溃（RL-24：安全降级承诺兜得住 Error）；</li>
 *   <li>withKnowledge 前置注入锁（RL-23：doctrine 非空时必须出现在最终请求最前面）。</li>
 * </ol>
 */
class RddSurvivalDoctrineTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void restoreProvider() {
        RddSurvivalDoctrine.setGameDirProvider(null);
    }

    private void installFakeGameDirWith(String content) throws Exception {
        Path numen = tempDir.resolve("config").resolve("numen");
        Files.createDirectories(numen);
        if (content != null) {
            Files.writeString(numen.resolve("survival-doctrine.md"), content);
        }
        RddSurvivalDoctrine.setGameDirProvider(() -> tempDir);
    }

    @Test
    void 文件存在时block含标题与正文() throws Exception {
        installFakeGameDirWith("先挖木头，做木镐，再去村庄。");
        String block = RddSurvivalDoctrine.block();
        assertTrue(block.contains("## 最高级经验"), "标题行必须存在");
        assertTrue(block.contains("先挖木头"), "正文必须进块");
    }

    @Test
    void 文件缺失时返回空串不注入空标题() throws Exception {
        installFakeGameDirWith(null);
        assertEquals("", RddSurvivalDoctrine.block(), "缺文件 = 空串，不许注入孤立标题");
    }

    @Test
    void 超上限时截断并留标记() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6500; i++) sb.append('字');
        installFakeGameDirWith(sb.toString());
        String block = RddSurvivalDoctrine.block();
        assertTrue(block.contains("…（指南过长已截断）"), "截断标记必须在");
        assertTrue(block.length() < 7000, "截断真的发生了");
    }

    @Test
    void 类加载失败降级为空串而非崩溃() {
        // 模拟单测 JVM 没有 NeoForge：provider 抛 NoClassDefFoundError（Error 子类）。
        // 修复前：FMLPaths 在 file() 里硬引用，类加载 Error 直接穿透 catch(RuntimeException|IOException)。
        RddSurvivalDoctrine.setGameDirProvider(() -> {
            throw new NoClassDefFoundError("net/neoforged/fml/loading/FMLPaths");
        });
        assertEquals("", RddSurvivalDoctrine.block(),
                "RL-24：类加载失败必须降级为空串，规划绝不因为一份指南挂掉");
    }

    @Test
    void withKnowledge前置注入生存路线() throws Exception {
        installFakeGameDirWith("阶段0：6 个石头 → 立刻出发。");
        String base = "基础规划提示";
        RddPlanningKnowledge.Provider stub = (c, o, s, f) -> "【经验】不要在夜里出门";
        String request = RddPlanningKnowledge.withKnowledge(
                stub, UUID.randomUUID(), base, "安身立命", "stage_a", List.of());
        int doctrinePos = request.indexOf("## 最高级经验");
        int basePos = request.indexOf("基础规划提示");
        assertTrue(doctrinePos >= 0, "RL-23：doctrine 必须真的进最终请求");
        assertTrue(basePos > doctrinePos, "doctrine 在最前面（目标锚点位置契约）");
        assertTrue(request.contains("【经验】不要在夜里出门"), "经验块同在");
    }

    @Test
    void 缓存按mtime失效主人改文件不用重启() throws Exception {
        installFakeGameDirWith("第一版内容");
        assertTrue(RddSurvivalDoctrine.block().contains("第一版内容"));
        // 主人改文件（mtime 变）→ 下一次 block() 必须拿到新内容，不用重启
        Path f = tempDir.resolve("config").resolve("numen").resolve("survival-doctrine.md");
        Files.writeString(f, "第二版内容");
        // 强制 mtime 变化（同秒写入可能同 mtime）
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() + 5000));
        String block = RddSurvivalDoctrine.block();
        assertTrue(block.contains("第二版内容"), "改完立即生效契约（mtime 缓存失效）");
        assertFalse(block.contains("第一版内容"), "旧内容不该残留");
    }
}
