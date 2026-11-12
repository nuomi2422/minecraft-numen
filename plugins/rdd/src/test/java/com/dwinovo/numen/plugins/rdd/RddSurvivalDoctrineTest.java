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
    void executorOnlyGetsRelevantReasonsAndNeverCachesPreviousTask() throws Exception {
        installFakeGameDirWith("## 为什么这样规划\n"
                + "- **为什么先找有干草的村庄**：快速粮储；床房恢复。没有干草按距离与安全换村。\n"
                + "- **为什么建基地和基本交易所**：实际报价可用才交易，不中断食物。\n"
                + "- **为什么要塞图书馆有经济价值**：后期取书。\n"
                + "- **为什么先保护一、不急着保护四**：保护IV不是先决条件。\n");
        String home = RddSurvivalDoctrine.executionBlock("安家", "获取一张床");
        assertTrue(home.contains("快速粮储"));
        assertTrue(home.contains("没有干草按距离与安全换村"));
        assertTrue(home.contains("survival-doctrine.md"));
        assertFalse(home.contains("图书馆"));
        assertFalse(home.contains("保护IV"));
        assertEquals("", RddSurvivalDoctrine.executionBlock("清点圆石", "放工作台"));
        assertEquals("", RddSurvivalDoctrine.executionBlock(null, null));
        assertTrue(home.length() < 1600);
        var sections = List.of(
                new com.dwinovo.numen.agent.context.ContextBudget.Section("plugin:rdd:runtime", "hp=1 danger=3 current-task", true, 10, 100),
                new com.dwinovo.numen.agent.context.ContextBudget.Section("plugin:rdd:execution_reasons", home, false, 100, 6));
        var roomy = com.dwinovo.numen.agent.context.ContextBudget.allocate(
                com.dwinovo.numen.agent.context.ContextBudget.unlimited(), sections);
        assertTrue(roomy.keptNames().contains("plugin:rdd:execution_reasons"));
        var tight = com.dwinovo.numen.agent.context.ContextBudget.allocate(
                new com.dwinovo.numen.agent.context.ContextBudget(0, 0, 0, 0L, 30L, 0L, "test"), sections);
        assertFalse(tight.denied());
        assertEquals(List.of("plugin:rdd:runtime"), tight.keptNames());
        assertEquals(List.of("plugin:rdd:execution_reasons"), tight.droppedNames());
        assertEquals("hp=1 danger=3 current-task", tight.kept().getFirst().text());
        // One previously uncovered consumer: real provider JSON serialization AFTER budget.
        // No credential, transport, Minecraft loop, or external model is invoked here.
        var provider = new com.dwinovo.numen.agent.provider.OpenAIProvider();
        String keptText = roomy.kept().stream().map(s -> s.text()).collect(java.util.stream.Collectors.joining("\n"));
        var wire = provider.buildRequestBody("offline-fixture", "system",
                List.of(provider.buildUserMessage(keptText)), new com.google.gson.JsonArray());
        assertTrue(wire.toString().contains("快速粮储"));
        assertTrue(wire.toString().contains("没有干草按距离与安全换村"));
        assertFalse(wire.toString().contains("图书馆"));
        String tightText = tight.kept().stream().map(s -> s.text()).collect(java.util.stream.Collectors.joining("\n"));
        var tightWire = provider.buildRequestBody("offline-fixture", "system",
                List.of(provider.buildUserMessage(tightText)), new com.google.gson.JsonArray());
        assertTrue(tightWire.toString().contains("hp=1 danger=3 current-task"));
        assertFalse(tightWire.toString().contains("execution_reasons"));
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

    // 2026-11-12: freeze the real executor -> budget -> provider consumer, before selector changes.
    // The source remains one Markdown doctrine; these are reasons, not new Java world rules.
    private static final String VILLAGE_REASON = "- **为什么先找有干草的村庄**：快速粮储；床房恢复。没有干草按距离与安全换村。";
    private static final String TRADE_REASON = "- **为什么建基地和基本交易所**：按实际职业、等级、报价、库存换装备和补给；没有收购项不能算绿宝石，食物供应不能中断。";
    private static final String LIBRARY_REASON = "- **为什么要塞图书馆有经济价值**：末地传送门所在要塞的图书馆安全可达才取书，卖给实际收书的图书管理员换绿宝石，再换装备、附魔与补给；不是开局裸装找要塞，缺图书馆或收书报价不能把预计收入当已有资产。";
    private static final String ENCHANT_REASON = "- **为什么先保护一、不急着保护四**：先至少保护 I，按可用经验、附魔书和铁砧渐进升级；不把保护 IV 当所有行动先决条件。";

    private void installExecutionReasons() throws Exception {
        installFakeGameDirWith("## 为什么这样规划\n" + String.join("\n",
                VILLAGE_REASON, TRADE_REASON, LIBRARY_REASON, ENCHANT_REASON)
                + "\n## 阶段 9\n不应整篇注入的后期正文\n");
    }

    private com.google.gson.JsonObject executionRequest(String phase, String step, boolean tight) {
        String reasons = RddSurvivalDoctrine.executionBlock(phase, step);
        var sections = new java.util.ArrayList<com.dwinovo.numen.agent.context.ContextBudget.Section>();
        sections.add(new com.dwinovo.numen.agent.context.ContextBudget.Section(
                "plugin:rdd:runtime", "hp=1 danger=3 current-task", true, 10, 100));
        if (!reasons.isEmpty()) sections.add(new com.dwinovo.numen.agent.context.ContextBudget.Section(
                "plugin:rdd:execution_reasons", reasons, false, 100, 6));
        var budget = tight
                ? new com.dwinovo.numen.agent.context.ContextBudget(0, 0, 0, 0L, 30L, 0L, "test")
                : com.dwinovo.numen.agent.context.ContextBudget.unlimited();
        var allocation = com.dwinovo.numen.agent.context.ContextBudget.allocate(budget, sections);
        assertFalse(allocation.denied());
        if (tight && !reasons.isEmpty()) {
            assertEquals(List.of("plugin:rdd:execution_reasons"), allocation.droppedNames());
            assertEquals(List.of("plugin:rdd:runtime"), allocation.keptNames());
        }
        String text = allocation.kept().stream().map(s -> s.text())
                .collect(java.util.stream.Collectors.joining("\n"));
        var provider = new com.dwinovo.numen.agent.provider.OpenAIProvider();
        return provider.buildRequestBody("offline-fixture", "system",
                List.of(provider.buildUserMessage(text)), new com.google.gson.JsonArray());
    }

    private String assertSelectedReason(String phase, String step, String expected) {
        String wire = executionRequest(phase, step, false).toString();
        assertTrue(wire.contains(expected), "complete relevant reason must reach provider request: " + wire);
        for (String other : List.of(VILLAGE_REASON, TRADE_REASON, LIBRARY_REASON, ENCHANT_REASON)) {
            if (!other.equals(expected)) assertFalse(wire.contains(other), "unrelated reason: " + wire);
        }
        assertFalse(wire.contains("不应整篇注入的后期正文"));
        return wire;
    }

    @Test
    void libraryBookAndBookshelfTasksReceiveCompleteStrongholdEconomyReason() throws Exception {
        installExecutionReasons();
        for (String[] task : List.of(new String[]{"要塞图书馆", "查看安全入口"},
                new String[]{"推进主线", "安全取书"}, new String[]{"补给来源", "拆书架"})) {
            String wire = assertSelectedReason(task[0], task[1], LIBRARY_REASON);
            assertTrue(wire.contains("实际收书"));
            assertTrue(wire.contains("预计收入当已有资产"));
        }
    }

    @Test
    void tradeTasksReceiveActualOfferAndSupplyReason() throws Exception {
        installExecutionReasons();
        for (String[] task : List.of(new String[]{"村民交易", "查看报价"},
                new String[]{"稳定补给", "实际收书交易换绿宝石"})) {
            assertSelectedReason(task[0], task[1], TRADE_REASON);
        }
    }

    @Test
    void enchantingTasksReceiveProgressiveProtectionReason() throws Exception {
        installExecutionReasons();
        for (String[] task : List.of(new String[]{"附魔", "检查现有经验"},
                new String[]{"装备升级", "保护 I 起步"}, new String[]{"装备升级", "铁砧定向升级"})) {
            assertSelectedReason(task[0], task[1], ENCHANT_REASON);
        }
    }

    @Test
    void currentTaskSelectionDoesNotCarryLibraryTradeOrEnchantReasonsIntoHomeOrNewTask() throws Exception {
        installExecutionReasons();
        assertSelectedReason("要塞图书馆", "取书", LIBRARY_REASON);
        assertSelectedReason("村民交易", "确认报价", TRADE_REASON);
        assertSelectedReason("附魔", "保护 I", ENCHANT_REASON);
        String home = executionRequest("安家", "放床", false).toString();
        assertTrue(home.contains(VILLAGE_REASON)); assertTrue(home.contains(TRADE_REASON));
        assertFalse(home.contains(LIBRARY_REASON)); assertFalse(home.contains(ENCHANT_REASON));
        for (String[] task : List.of(new String[]{"清点圆石", "放工作台"}, new String[]{null, null})) {
            assertEquals("", RddSurvivalDoctrine.executionBlock(task[0], task[1]));
            String wire = executionRequest(task[0], task[1], false).toString();
            assertFalse(wire.contains("execution_reasons"));
            assertTrue(wire.contains("hp=1 danger=3 current-task"));
        }
    }

    @Test
    void laterStageReasonsAreDroppedWholeUnderBudgetWhileSafetySurvives() throws Exception {
        installExecutionReasons();
        for (String[] task : List.of(new String[]{"要塞图书馆", "取书"},
                new String[]{"村民交易", "确认报价"}, new String[]{"附魔", "保护 I"})) {
            assertFalse(RddSurvivalDoctrine.executionBlock(task[0], task[1]).isEmpty());
            String wire = executionRequest(task[0], task[1], true).toString();
            assertTrue(wire.contains("hp=1 danger=3 current-task"));
            assertFalse(wire.contains("execution_reasons"));
            for (String reason : List.of(VILLAGE_REASON, TRADE_REASON, LIBRARY_REASON, ENCHANT_REASON))
                assertFalse(wire.contains(reason));
        }
    }
}
