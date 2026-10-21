package com.dwinovo.numen.agent.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G5c 判据 S1–S6：统一入口的合并/部分失败/路由。
 *
 * <p>固定班底：experience provider（1 条经验 + 1 条与工具目录同 ref 的碰撞条目）+
 * tool-catalog provider（2 个 PROCEDURE 条目）。三组都用合成 source，不碰真 host。
 */
class MinimalRetrievalServiceTest {

    private static ExperienceEntryRead read(String ref, String title, EntryKind kind, Maturity m,
                                             String body, List<String> applicable) {
        return new ExperienceEntryRead(ref, kind, title, "摘要 " + title,
                applicable, List.<String>of(), List.<String>of(), m, 1, false,
                body, "hash-" + ref, "fake");
    }

    private static RetrievedEntry e(String ref, String title, EntryKind kind, Maturity m, String body) {
        return new ExperienceRetrievalProvider("experience", () -> List.of(read(ref, title, kind, m, body, List.<String>of())))
                .search(new RetrievalQuery("q-1", "c-1", "w-1", RetrievalRole.PLANNER, "目标",
                        List.of(), List.of(), List.of(), 1, "",
                        new RetrievalBudget(10, 0, 0, 0, 5000, 0, "none"), "ACTIVE"))
                .entries().stream()
                .filter(x -> ref.equals(x.descriptor().entryRef()))
                .findFirst().orElseThrow();
    }

    private static MinimalRetrievalService service(RetrievalProvider... providers) {
        RetrievalRegistry r = new RetrievalRegistry();
        for (RetrievalProvider p : providers) assertTrue(r.register(p).ok());
        return new MinimalRetrievalService(r);
    }

    private static RetrievalQuery q(int maxCandidates) {
        return new RetrievalQuery("q-1", "c-1", "w-1", RetrievalRole.PLANNER, "目标",
                List.of(), List.of(), List.of(), 1, "",
                new RetrievalBudget(maxCandidates, 0, 0, 0, 5000, 0, "none"), "ACTIVE");
    }

    /** 经验 provider（带一条与工具目录同 ref 的条目，测跨 provider 去重）。 */
    private static RetrievalProvider experienceProvider() {
        return new ExperienceRetrievalProvider("experience", () -> List.of(
                read("exp:mining", "挖矿经验", EntryKind.EXPERIENCE, Maturity.VERIFIED, "经验正文", List.<String>of()),
                read("tool:goto", "经验侧的同名条目", EntryKind.EXPERIENCE, Maturity.OBSERVED, "碰撞体", List.<String>of())));
    }

    /** 工具目录 provider（2 个 PROCEDURE）。 */
    private static RetrievalProvider toolProvider() {
        return new ToolCatalogRetrievalProvider(() -> List.of(
                read("tool:goto", "goto 工具", EntryKind.PROCEDURE, Maturity.VERIFIED, "走向目标", List.<String>of()),
                read("tool:mine", "mine 工具", EntryKind.PROCEDURE, Maturity.VERIFIED, "挖方块", List.<String>of())));
    }

    @Test
    void s1_crossProviderDedupByEntryRef() {
        ContextSnapshot s = service(experienceProvider(), toolProvider()).buildContext(q(50));
        long gotoCount = s.directory().stream()
                .filter(x -> "tool:goto".equals(x.descriptor().entryRef())).count();
        assertEquals(1, gotoCount, "同 entryRef 跨 provider 只进一次（先注册的经验侧赢）");
        assertEquals(3, s.directory().size(), "3 条唯一条目：exp:mining + tool:goto + tool:mine");
    }

    @Test
    void s2_partialProviderGapsSurviveIntoSnapshot() {
        // experience 正常 + tool 目录超截（maxCandidates=1 → 它的页带 MAX_CANDIDATES gap）
        ContextSnapshot s = service(experienceProvider(), toolProvider()).buildContext(q(1));
        assertTrue(s.gaps().stream().anyMatch(g -> "MAX_CANDIDATES".equals(g.code())),
                "provider 的 gap 必须原样出现在最终 snapshot");
        assertTrue(s.directory().size() >= 1, "另一路的条目照常进目录（部分失败不算全局失败）");
        assertEquals(ContextSnapshot.Status.PARTIAL, s.status());
    }

    @Test
    void s3_throwingProviderBecomesGapNotCrash() {
        RetrievalProvider bomb = new RetrievalProvider() {
            @Override public String providerId() { return "bomb"; }
            @Override public SearchPage search(RetrievalQuery q) { throw new IllegalStateException("炸了"); }
        };
        ContextSnapshot s = service(experienceProvider(), bomb).buildContext(q(50));
        assertTrue(s.gaps().stream().anyMatch(g -> "PROVIDER_THROW".equals(g.code())),
                "provider 异常必须变成 gap，不许冒泡");
        assertTrue(s.directory().stream().anyMatch(x -> "exp:mining".equals(x.descriptor().entryRef())),
                "好 provider 的条目照常进目录");
        assertEquals(ContextSnapshot.Status.PARTIAL, s.status());
    }

    @Test
    void s4_expandRoutesByRefPrefix() {
        // tool provider 覆写 expand（匿名实现 RetrievalProvider，不做目录搜索）
        RetrievalProvider tools = new RetrievalProvider() {
            @Override public String providerId() { return "tool-catalog"; }
            @Override public SearchPage search(RetrievalQuery q) { return SearchPage.empty(providerId(), q.queryId()); }
            @Override
            public Result<EntryBody> expand(EntryRef ref, ExpansionRequest req) {
                return new Result.Ok<>(new EntryBody(ref.value(), "h", "text/plain", "工具正文", false, providerId()));
            }
        };
        MinimalRetrievalService svc = service(experienceProvider(), tools);
        Result<EntryBody> toolBody = svc.expand(new EntryRef("tool:mine"), new ExpansionRequest("q", RetrievalRole.PLANNER, 100, ""));
        assertTrue(toolBody instanceof Result.Ok, "tool: 前缀应路由到 tool-catalog");
        Result<EntryBody> expBody = svc.expand(new EntryRef("exp:mining"), new ExpansionRequest("q", RetrievalRole.PLANNER, 100, ""));
        assertTrue(expBody instanceof Result.Err, "experience 只有 default expand → Err 转发");
        assertEquals("UNSUPPORTED", ((Result.Err<EntryBody>) expBody).code());
        Result<EntryBody> noOwner = svc.expand(new EntryRef("nobody:xyz"), new ExpansionRequest("q", RetrievalRole.PLANNER, 100, ""));
        assertTrue(noOwner instanceof Result.Err && "NO_OWNER".equals(((Result.Err<EntryBody>) noOwner).code()),
                "无 owner 必须显式 NO_OWNER");
    }

    @Test
    void s5_unsupportedIsForwardedNotSwallowed() {
        MinimalRetrievalService svc = service(experienceProvider());
        Result<EntryBody> r = svc.expand(new EntryRef("exp:mining"), new ExpansionRequest("q", RetrievalRole.PLANNER, 100, ""));
        assertTrue(r instanceof Result.Err);
        assertEquals("UNSUPPORTED", ((Result.Err<EntryBody>) r).code(), "default 的 UNSUPPORTED 要原样透传，不许吞成别的错");
    }

    @Test
    void s6_procedureEntriesStayUnknownAndPassExecutorView() {
        ContextSnapshot s = service(toolProvider()).buildContext(q(50));
        for (RetrievedEntry e : s.directory()) {
            assertEquals(Applicability.UNKNOWN, e.applicability(),
                    "目录条目不许检索层擅自升 PASS");
            assertEquals(EntryKind.PROCEDURE, e.descriptor().kind());
        }
        ContextSnapshot exec = RoleViewSelector.select(s, RetrievalRole.EXECUTOR);
        assertEquals(2, exec.directory().size(),
                "VERIFIED 的 PROCEDURE 应过 EXECUTOR 门（工具目录是能力清单）");
    }
}
