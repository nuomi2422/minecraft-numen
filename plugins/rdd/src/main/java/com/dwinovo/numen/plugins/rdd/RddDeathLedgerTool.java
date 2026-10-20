package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.core.RddDeathLedger;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code rdd_death_ledger}：<b>只读</b>查看死亡台账（G2b）。
 *
 * <p><b>为什么需要这个工具</b>：死亡台账是「这次死亡丢了什么、现在算不算找回来了」的唯一真源，
 * 但它此前只有一个写入口（{@code RddDeathLedger.record}）和一堆事件；主人/AI 想知道
 * 「那批掉落物到底结案没有」只能去翻 `_ledger.json` 或猜。V-20261020-17 实测：
 * 超时分支的 `CONFIRMED_LOST` 在事件里有，但没有可回读的查询面 —— 这就是本工具补的洞。
 *
 * <p><b>三档状态的措辞纪律（不许含糊）</b>：
 * <ul>
 *   <li>{@code UNVERIFIED}：只有时钟估计，没有实体证据；</li>
 *   <li>{@code CONFIRMED_LOST}：<b>支线放弃追踪</b>（或扫描确认不在），
 *       <b>不等于</b>物品在物理上被销毁（区块没加载就不计龄）；</li>
 *   <li>{@code RECOVERED}：掉落物被观测到已经回到背包。</li>
 * </ul>
 *
 * <p>只读：不改台账、不触发回收、不推进任何状态。
 */
final class RddDeathLedgerTool implements NumenTool {

    @Override public String name() { return "rdd_death_ledger"; }

    @Override public String description() {
        return "Read-only view of the death ledger: the last deaths of this companion with their evidence "
                + "state (UNVERIFIED = clock estimate only; CONFIRMED_LOST = the recovery branch was closed "
                + "or a scan confirmed absence, NOT proof the items were destroyed; RECOVERED = tracked drops "
                + "were observed back in the inventory), death position, how many item kinds were lost, the "
                + "top lost items, and how long ago it happened. Use it when you need to know whether a death "
                + "batch is still open before planning recovery, or to explain why the companion is or is not "
                + "still going after its drops. It never changes the ledger or starts a recovery.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalInteger("limit", "How many recent deaths to return (1-" + RddDeathLedger.MAX_ENTRIES
                        + ", default 5).", 1, RddDeathLedger.MAX_ENTRIES)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            if (companion == null) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail("rdd_death_ledger requires a companion").toJson());
                return;
            }
            RddPlugin.ensureLedgerLoaded(companion.getUUID());
            RddDeathLedger.LedgerSnapshot snapshot = RddDeathLedger.snapshot(companion.getUUID());
            int limit = 5;
            if (args != null && args.has("limit") && !args.get("limit").isJsonNull()) {
                limit = Math.max(1, Math.min(RddDeathLedger.MAX_ENTRIES, args.get("limit").getAsInt()));
            }
            long now = System.currentTimeMillis();
            List<RddDeathLedger.Death> all = snapshot.deaths();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = Math.max(0, all.size() - limit); i < all.size(); i++) {
                RddDeathLedger.Death d = all.get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("seq", d.seq());
                row.put("state", d.state().name());
                row.put("deathAt", d.deathAt() == null ? "" : d.deathAt());
                row.put("lostItemKinds", d.lostItems().size());
                row.put("lostEntries", d.lostEntries());
                row.put("lostTop", topItems(d.lostItems(), 4));
                row.put("secondsAgo", Math.max(0L, (now - d.wallClockMillis()) / 1000L));
                row.put("evidence", trim(d.evidence(), 160));
                rows.add(row);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("nextSeq", snapshot.nextSeq());
            data.put("totalDeaths", all.size());
            data.put("returned", rows.size());
            data.put("deaths", rows);
            data.put("stateMeaning", "UNVERIFIED=clock estimate only; CONFIRMED_LOST=branch closed / scan says absent, "
                    + "NOT proof of physical destruction; RECOVERED=drops observed back in inventory");
            reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                    rows.isEmpty() ? "death ledger is empty (no death recorded for this companion)" : "death ledger (read-only)",
                    data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail("rdd_death_ledger failed: " + ex.getMessage()).toJson());
        }
    }

    /** 取丢失最多的几项（有界，避免把整张表塞进回执）。 */
    private static List<Map<String, Object>> topItems(Map<String, Integer> lost, int max) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(lost.entrySet());
        entries.sort((a, b) -> Integer.compare(
                b.getValue() == null ? 0 : b.getValue(),
                a.getValue() == null ? 0 : a.getValue()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < entries.size() && i < max; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", entries.get(i).getKey());
            m.put("count", entries.get(i).getValue());
            out.add(m);
        }
        return out;
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
