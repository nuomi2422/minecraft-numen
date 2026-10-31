package com.dwinovo.numen.plugins.learner.funnel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 漏斗阶梯的**整段**回归（而不是逐阶孤立用例）。
 *
 * <h2>为什么要这一支</h2>
 * 2026-10-31 实机里最贵的一课是：每一阶单测都绿，但整条线是空的 ——
 * 因为"库里的条目全被过滤"这种状态，没有任何单测在模拟。
 * 这组用例把三种真实形态钉死：
 * <ol>
 *   <li><b>全被过滤</b>（今天的 live 状态）：refs 全 rendered=false → 呈现为 0；</li>
 *   <li><b>一阶一阶推进</b>：呈现 → 展开 → 采用 → 执行 → 效果，每次只前进一格；</li>
 *   <li><b>中途停住</b>：有呈现无展开时，停点必须说"已呈现未展开"，不许说"未召回"。</li>
 * </ol>
 */
class FunnelLadderTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String TS = "2026-10-31T12:00:00Z";
    private static final long TS_MS = java.time.Instant.parse(TS).toEpochMilli();
    private static final String REF = "exp:execution|某条经验";

    private static JsonObject ref(String id, boolean rendered, String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("ref", id);
        o.addProperty("rendered", rendered);
        o.addProperty("reason", reason);
        return o;
    }

    private static String snapshot(JsonObject... refs) {
        JsonObject env = new JsonObject();
        env.addProperty("event_id", "ctx-" + TS);
        env.addProperty("timestamp", TS);
        env.addProperty("type", "retrieval_snapshot");
        JsonObject data = new JsonObject();
        data.addProperty("role", "EXECUTOR");
        JsonArray arr = new JsonArray();
        for (JsonObject r : refs) {
            arr.add(r);
        }
        data.add("refs", arr);
        env.add("data", data);
        return env.toString();
    }

    private static String expmem(String type, String state, String eventId) {
        JsonObject env = new JsonObject();
        env.addProperty("event_id", eventId);
        env.addProperty("timestamp", TS);
        env.addProperty("type", type);
        JsonObject data = new JsonObject();
        data.addProperty("ref", REF);
        if (state != null) {
            data.addProperty("state", state);
        }
        env.add("data", data);
        return env.toString();
    }

    private static FunnelLedger ledgerOf(List<String> ctx, List<String> exp) {
        return FunnelReport.ledgerOf(FunnelReplay.replay(ctx, exp, TS_MS + 1000));
    }

    @Test
    void 全被过滤时呈现为0且停点仍是已召回未呈现() {
        // 2026-10-31 live 的真实形态：100 个 ref 只渲染 20，且 live 同伴 0 条可检索。
        FunnelLedger l = ledgerOf(List.of(snapshot(
                ref(REF, false, "draft_or_suspended"),
                ref("exp:policy|另一条", false, "not_retrievable"))), List.of());

        assertEquals(0, l.countAt(FunnelStage.PRESENTED, FunnelLedger.Caller.EXECUTOR),
                "全过滤时呈现必须是 0 —— 这条用来防止「回放把过滤当渲染」");
        assertEquals(2, l.countAt(FunnelStage.RECALLED, FunnelLedger.Caller.EXECUTOR),
                "被过滤的条目仍然算召回：它们进过候选集");
        assertEquals("已召回未呈现",
                l.whereStopped(REF, FunnelLedger.Caller.EXECUTOR).verdict());
        assertTrue(l.whereStopped(REF, FunnelLedger.Caller.EXECUTOR).verdict()
                        .contains("已召回"),
                "停点不能倒退成「未召回」");
    }

    @Test
    void 阶梯每次只前进一格() {
        // ① 呈现
        FunnelLedger afterPresent = ledgerOf(
                List.of(snapshot(ref(REF, true, ""))), List.of());
        assertEquals("已呈现未展开", afterPresent.whereStopped(REF, FunnelLedger.Caller.EXECUTOR).verdict());

        // ② 展开
        FunnelLedger afterExpand = ledgerOf(
                List.of(snapshot(ref(REF, true, ""))),
                List.of(expmem("expanded", null, "e1")));
        assertEquals("已展开未采用", afterExpand.whereStopped(REF, FunnelLedger.Caller.EXECUTOR).verdict());

        // ③ 采用（trial_used 是近似）
        FunnelLedger afterAdopt = ledgerOf(
                List.of(snapshot(ref(REF, true, ""))),
                List.of(expmem("expanded", null, "e1"), expmem("trial_used", null, "e2")));
        assertEquals("已采用未执行", afterAdopt.whereStopped(REF, FunnelLedger.Caller.EXECUTOR).verdict());

        // ④ 执行
        FunnelLedger afterExec = ledgerOf(
                List.of(snapshot(ref(REF, true, ""))),
                List.of(expmem("expanded", null, "e1"), expmem("trial_used", null, "e2"),
                        expmem("usage_recorded", "RUNNING", "e3")));
        assertEquals("已执行未见效果", afterExec.whereStopped(REF, FunnelLedger.Caller.EXECUTOR).verdict());

        // ⑤ 效果
        FunnelLedger afterEffect = ledgerOf(
                List.of(snapshot(ref(REF, true, ""))),
                List.of(expmem("expanded", null, "e1"), expmem("trial_used", null, "e2"),
                        expmem("usage_recorded", "SUCCESS", "e3")));
        assertTrue(afterEffect.whereStopped(REF, FunnelLedger.Caller.EXECUTOR).verdict()
                        .startsWith("reached_end"),
                "五步走完必须报 reached_end，否则面板会一直提示「还差一阶」");
    }

    @Test
    void 缺少更早的阶时按最高到达阶报_这是已知局限() {
        // 直接从工具调用进来的路径：只有 expanded，没有 snapshot 的 rendered。
        // 现实里会发生（预算把呈现裁掉了，但模型仍可能从别处拿到 ref 去展开）。
        //
        // ⚠ 这里的断言记录的是**当前行为**，它暴露一个已知局限：
        //   whereStopped 只看"最高到达阶"，不检查中间是否缺阶。
        //   所以「跳过召回直接展开」会读成「已展开未采用」，看不出前面断了。
        //   要修就得改成"按相邻阶连续性判停"，那是另一件事，已记进 162 §29.6。
        FunnelLedger l = ledgerOf(List.of(), List.of(expmem("expanded", null, "e1")));
        var stop = l.whereStopped(REF, FunnelLedger.Caller.EXECUTOR);
        assertEquals(FunnelStage.EXPANDED, stop.reached());
        assertEquals("已展开未采用", stop.verdict());
        assertEquals(0, l.countAt(FunnelStage.RECALLED, FunnelLedger.Caller.EXECUTOR),
                "账本如实记着没有召回 —— 局限只在 whereStopped 的措辞，不在数据");
        // 2026-10-31 补：断点单列成 gaps，措辞保持稳定（避免面板抖动）。
        assertTrue(stop.hasGaps(), "跳过更早的阶必须被标出来，否则「已展开未采用」读不出前面断了");
        assertTrue(stop.gaps().contains(FunnelStage.RECALLED), "缺的就是召回这一阶");
        assertTrue(stop.gaps().contains(FunnelStage.PRESENTED), "呈现也没记录，同样算断点");
        assertFalse(stop.gaps().contains(FunnelStage.EXPANDED),
                "到达的那一阶不是断点：把最高阶也算进 gaps 会让面板永远报断");
    }
}
