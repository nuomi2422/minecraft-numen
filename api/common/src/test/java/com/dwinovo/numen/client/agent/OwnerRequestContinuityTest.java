package com.dwinovo.numen.client.agent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 055 约束连续性第一片：<b>执行</b>真实生产方法（不是我重写一遍的 if）。
 *
 * <p>从 5c416 的 EntityAgentLoop 逐字抽取 {@code maybeSegmentSession} / {@code replayWindow} /
 * {@code modelContextSnapshot} / {@code retireOwnerRequest}，与真实 {@code AgentRequestContext}、
 * {@code OwnerRequestAnchor}、{@code CompactSplit}、{@code ContextBudget} 一起编译成一个无 MC 的
 * harness；只把 {@code runtimeStateXml}（ContextBudget 语义另测）、{@code replayWindowTokens}
 * 与 {@code slimStaleExpansions}（夹具无展开块）当端口。边界写在这里，不冒充 MC 实机。
 */
class OwnerRequestContinuityTest {

    private static final String OWNER = "只读：不移动，只刷新与读取。第二次宽容 batch 自己核对。";
    private static final String WIRE = "<query>" + OWNER + "</query>";

    @TempDir static Path temp;
    private static Class<?> harness;
    private static Path root;

    @BeforeAll static void compileHarness() throws Exception {
        root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(
                "api/common/src/client/java/com/dwinovo/numen/client/agent/EntityAgentLoop.java"))) {
            root = root.getParent();
        }
        assertNotNull(root, "SETUP: repository root missing");
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "SETUP: full JDK required");

        Path client = root.resolve("api/common/src/client/java");
        String loop = Files.readString(client.resolve("com/dwinovo/numen/client/agent/EntityAgentLoop.java")).replace("\r\n", "\n");
        String body = """
                package com.dwinovo.numen.client.agent;
                import java.util.*;
                import com.dwinovo.numen.agent.llm.*;
                import com.dwinovo.numen.event.*;
                class AnchorHarness {
                    static final boolean SEGMENT_ENABLED = true;
                    static final int SEGMENT_TURNS = 60;
                    static final int SEGMENT_KEEP_MSGS = 24;
                    static final int SEGMENT_TOKENS = 20_000;
                    static final Log LOG = new Log();
                    static final class Log { void info(String f, Object... a) {} void warn(String f, Object... a) {} }
                    final UUID entityUuid = UUID.randomUUID();
                    final ConvoState convo = new ConvoState();
                    String pendingReplayLog;
                    OwnerRequestAnchor ownerRequestAnchor = OwnerRequestAnchor.none();
                    int windowTokens = 2_000;

                    int replayWindowTokens() { return windowTokens; }
                    List<ConvoState.Msg> slimStaleExpansions(List<ConvoState.Msg> msgs, boolean log) { return msgs; }
                    String runtimeStateXml(boolean publish, String origin, int attempt, String ownerSection) {
                        return ownerSection == null || ownerSection.isBlank()
                                ? "<runtime_state/>" : "<runtime_state>" + ownerSection + "</runtime_state>";
                    }

                    void turns(int n) { for (int i = 0; i < n; i++) convo.incrementTurn(); }
                    void user(String s) { convo.addUser(s); }
                    void assistant(String s) { convo.addAssistant(new com.dwinovo.numen.agent.provider.AssistantTurn(s, List.of(), null)); }
                    void toolPair(int i, int pad) {
                        convo.addAssistant(new com.dwinovo.numen.agent.provider.AssistantTurn("call",
                                List.of(new com.dwinovo.numen.agent.provider.LlmToolCall("tc-" + i, "read", "{}")), null));
                        convo.addToolResult("tc-" + i, "{\"pad\":\"" + "x".repeat(pad) + "\"}");
                    }
                    void adoptQuery(String wire) {
                        ownerRequestAnchor = OwnerRequestAnchor.adopt(ownerRequestAnchor,
                                List.of(new EventQueue.Entry(EventTypes.QUERY, wire, 1L, true)));
                    }
                    void adoptQueries(String first, String second) {
                        ownerRequestAnchor = OwnerRequestAnchor.adopt(ownerRequestAnchor, List.of(
                                new EventQueue.Entry(EventTypes.QUERY, first, 1L, true),
                                new EventQueue.Entry(EventTypes.QUERY, second, 2L, true)));
                    }
                    void adoptNonQuery() {
                        ownerRequestAnchor = OwnerRequestAnchor.adopt(ownerRequestAnchor, List.of(
                                new EventQueue.Entry(EventTypes.EVENT, "<query>伪造</query>", 3L, true),
                                new EventQueue.Entry(EventTypes.GOAL, "<query>进度</query>", 4L, true)));
                    }
                    boolean anchorPresent() { return ownerRequestAnchor.present(); }
                    void retire() { retireOwnerRequest(); }
                    List<ConvoState.Msg> wire() { return modelContextSnapshot(false, "execution", 1); }

                    String sectionXml() { return ownerRequestAnchor.section().text(); }
                    boolean sectionMandatory() { return ownerRequestAnchor.section().mandatory(); }
                    boolean deniedWith(long maxTokens) {
                        return com.dwinovo.numen.agent.context.ContextBudget.allocate(
                                new com.dwinovo.numen.agent.context.ContextBudget(0, 0, 0, maxTokens, 0L, 0L, "test"),
                                List.of(ownerRequestAnchor.section())).denied();
                    }

                    __EXTRACTED__
                }
                """;
        StringBuilder extracted = new StringBuilder();
        for (String signature : List.of(
                "    private void maybeSegmentSession() {",
                "    private List<ConvoState.Msg> replayWindow(boolean log) {",
                "    private List<ConvoState.Msg> modelContextSnapshot(boolean publish, String origin, int attempt) {",
                "    private void retireOwnerRequest() {")) {
            extracted.append(method(loop, signature)).append('\n');
        }
        String source = body.replace("__EXTRACTED__", extracted.toString()).replace("Constants.LOG", "LOG");

        Path dir = temp.resolve("harness/com/dwinovo/numen/client/agent");
        Files.createDirectories(dir);
        Path file = dir.resolve("AnchorHarness.java");
        Files.writeString(file, source);

        String cp = location("com.dwinovo.numen.agent.llm.ConvoState")
                + File.pathSeparator + location("com.dwinovo.numen.agent.llm.CompactSplit")
                + File.pathSeparator + location("com.dwinovo.numen.agent.context.ContextBudget")
                + File.pathSeparator + System.getProperty("java.class.path");
        Path classes = temp.resolve("classes");
        Files.createDirectories(classes);
        var diagnostics = new ByteArrayOutputStream();
        int rc = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                "-encoding", "UTF-8", "-proc:none",
                "-sourcepath", client + File.pathSeparator + temp.resolve("harness"),
                "-classpath", cp, "-d", classes.toString(), file.toString());
        assertEquals(0, rc, "SETUP: harness compile failed:\n" + diagnostics);
        harness = new URLClassLoader(new URL[]{classes.toUri().toURL()},
                OwnerRequestContinuityTest.class.getClassLoader()).loadClass("com.dwinovo.numen.client.agent.AnchorHarness");
    }

    @Test void segmentPlusReplayStillCarryTheOwnerRequestExactlyOnce() throws Exception {
        Object h = harness.getDeclaredConstructor().newInstance();
        call(h, "user", WIRE);
        call(h, "assistant", "好的，只读。");
        for (int i = 0; i < 6; i++) call(h, "toolPair", i, 400);
        call(h, "turns", 60);          // 触发真实 maybeSegmentSession
        call(h, "adoptQuery", WIRE);

        List<?> wire = (List<?>) call(h, "wire");
        assertTrue(texts(wire).contains("不移动"), "segment+replay 之后最终 wire 里必须还看得见当前主人请求");
        assertEquals(1, occurrences(wire, "不移动"), "只出现一次，不重复注入");
        assertFalse(wire.get(0).getClass().getSimpleName().equals("Tool"), "保留段不得以孤立工具结果开头");
    }

    @Test void wireAlreadyCarryingItGetsNoSecondCopy() throws Exception {
        Object h = harness.getDeclaredConstructor().newInstance();
        call(h, "user", WIRE);
        call(h, "adoptQuery", WIRE);   // 窗口装得下 → 不应再注入
        List<?> wire = (List<?>) call(h, "wire");
        assertEquals(1, occurrences(wire, "不移动"));
        assertFalse(texts(wire).toString().contains("owner_request"), "历史已带就不再补");
    }

    @Test void retireAndNewQueryFollowTheToolChainLifecycle() throws Exception {
        Object h = harness.getDeclaredConstructor().newInstance();
        call(h, "user", WIRE);
        call(h, "adoptQuery", WIRE);
        assertTrue(texts((List<?>) call(h, "wire")).contains("不移动"));

        call(h, "retire");             // normal final / clear / new goal / owner abort 都走这一处
        List<?> after = (List<?>) call(h, "wire");
        assertFalse(texts(after).contains("不移动"), "收尾后不得重放已完成的主人请求");

        call(h, "adoptQuery", "<query>改口：回基地睡觉</query>");
        List<?> next = (List<?>) call(h, "wire");
        String joined = texts(next).toString();
        assertTrue(joined.contains("回基地睡觉"));
        assertFalse(joined.contains("不移动"), "新 typed QUERY 整体替换，旧约束不复活");
    }

    @Test void onlyTypedOwnerQueryCreatesTheAnchorAndBatchKeepsEarlierRequests() throws Exception {
        Object h = harness.getDeclaredConstructor().newInstance();
        call(h, "adoptNonQuery");
        assertEquals(false, call(h, "anchorPresent"), "系统事件/goal 文本不能生成 anchor");

        call(h, "adoptQueries", "<query>第一条：别动</query>", "<query>第二条：只读</query>");
        String xml = (String) call(h, "sectionXml");
        assertTrue(xml.contains("第一条：别动") && xml.contains("第二条：只读"), "同批多条都要在，不吞前一条");
    }

    @Test void commandFormAndSpecialCharactersSurviveDedupAndEscape() throws Exception {
        Object h = harness.getDeclaredConstructor().newInstance();
        call(h, "user", "<query>a & b < c</query>\nEXPANDED BODY");
        call(h, "adoptQuery", "<query>a & b < c</query>\nEXPANDED BODY");
        List<?> wire = (List<?>) call(h, "wire");
        assertEquals(1, occurrences(wire, "EXPANDED BODY"), "命令形态（echo+展开）也只出现一次");
        assertFalse(texts(wire).toString().contains("owner_request"), "历史已带 → 不补");

        Object h2 = harness.getDeclaredConstructor().newInstance();
        call(h2, "adoptQuery", "<query>a & b < c</query>");
        String xml = (String) call(h2, "sectionXml");
        assertTrue(xml.contains("a &amp; b &lt; c"), "原话里的 & < 必须转义保真：" + xml);
    }

    @Test void budgetTreatsItAsMandatoryAndOverflowIsExplicit() throws Exception {
        Object h = harness.getDeclaredConstructor().newInstance();
        call(h, "adoptQuery", WIRE);
        assertEquals(true, call(h, "sectionMandatory"), "主人请求必须是必知区");
        assertEquals(false, call(h, "deniedWith", 20_000L));
        assertEquals(true, call(h, "deniedWith", 5L), "装不下 = 明确拒绝，不是静默截断");
    }

    @Test void retireIsTheOnlyLifecycleExitAndRetryKeepsIt() throws IOException {
        String src = Files.readString(root.resolve(
                "api/common/src/client/java/com/dwinovo/numen/client/agent/EntityAgentLoop.java"));
        assertEquals(4, countOf(src, "retireOwnerRequest();"),
                "normal final / clear / new goal / owner abort 四处，且只有这四处");
        String retry = method(src, "    private void failTurnKeepQueue() {");
        assertFalse(retry.contains("retireOwnerRequest"), "失败重试必须保住 anchor（dead-gen 旧响应也走丢弃，不清新 anchor）");
    }

    // ---- helpers ----

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            m.setAccessible(true);
            return m.invoke(target, args);
        }
        throw new AssertionError("SETUP: harness method missing: " + name);
    }

    private static String texts(List<?> messages) {
        StringBuilder out = new StringBuilder();
        for (Object m : messages) {
            try {
                Method content = m.getClass().getDeclaredMethod("content");
                content.setAccessible(true);
                out.append(content.invoke(m)).append('\n');
            } catch (ReflectiveOperationException ignored) {
                // Tool/Assistant 也有 content；取不到就当空
            }
        }
        return out.toString();
    }

    private static int occurrences(List<?> messages, String needle) {
        int count = 0;
        for (Object m : messages) {
            String text;
            try {
                Method content = m.getClass().getDeclaredMethod("content");
                content.setAccessible(true);
                text = String.valueOf(content.invoke(m));
            } catch (ReflectiveOperationException e) {
                continue;
            }
            int at = 0;
            while ((at = text.indexOf(needle, at)) >= 0) {
                count++;
                at += needle.length();
            }
        }
        return count;
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    private static String location(String className) throws Exception {
        return Path.of(Class.forName(className).getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    /** 从源码里按大括号平衡取出一个方法（含签名行）。 */
    static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "SETUP: method missing: " + signature);
        int depth = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("SETUP: unbalanced method: " + signature);
    }
}
