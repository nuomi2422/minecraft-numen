package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 2026-11-13 C1 学习者侧接缝：把「一次最终请求里完整交付的 skill 正文」写成**现有**
 * {@link UsageLedger} 的 PRESENTED/UNKNOWN 证据。
 *
 * <p>中性数据端口：本类不依赖 {@code api/common} 的 DeliveredContext/WorldContextStamp，
 * 父接线时只需把 {@link Context} 的字段映射过来。规则（父 2026-11-13 口述）：
 * <ul>
 *   <li>身份必须完整：requestId + worldId + serverSession + companion 非空且 raw epoch > 0；
 *       body 任务半身份（有 taskId 无 toolCallId 或有 toolCallId 无 taskId）**显式拒**，
 *       不许退回 delivery-only 掩盖；缺任一项 → NO_IDENTITY，一行不写、不造伪 episode；</li>
 *   <li>账本不可读（{@link UsageLedger#readable()} false）→ 不能把 all() 空当「未记过」，
 *       直接报 ioFailed、不追加；</li>
 *   <li>同一 ledger 上 read-existing + append 必须同锁（{@code synchronized (ledger)}），
 *       防两个交付回调并发各写一条；</li>
 *   <li>幂等键用 SHA-256(规范 JSON 数组)，不拼冒号/井号；artifactId 保留
 *       {@code ref@version#k=<hex>} 便于人看；</li>
 *   <li>只写 PRESENTED/UNKNOWN：绝不写 RESULT/SUCCESS、不 claimValidity、不触发复核或世界动作；</li>
 *   <li>IO 失败不吞成成功：只有 append 真返回 Entry 才计 written；失败报 ioFailed，不缓存，重试可补。</li>
 * </ul>
 */
public final class SkillDeliveryEvidence {

    /** 账本 kind（skill 交付证据）；不是经验/AC 的执行 kind。 */
    public static final String KIND = "SKILL";
    public static final String SOURCE_PREFIX = "request-delivery:";

    /** 与 046-F 同一套规范形状：{@code skill:<id>}，版本 {@code v<正整数>}。 */
    static final String REF_PATTERN = "skill:[^\\s:@]+";
    static final String VERSION_PATTERN = "v[1-9][0-9]*";

    /** 一条被交付的引用（中性形状；父侧 DeliveredRef 映射到这里）。 */
    public record Ref(String entryRef, String version) {
        public Ref {
            entryRef = entryRef == null ? "" : entryRef.trim();
            version = version == null ? "" : version.trim();
        }

        /** 规范 {@code skill:<id>} + {@code vN} 才收；exp/AC 等其它引用不归这里。 */
        public boolean usable() {
            return entryRef.matches(REF_PATTERN) && version.matches(VERSION_PATTERN);
        }
    }

    /**
     * 中性交付上下文，字段与父侧 DeliveredContext 一一对应。
     *
     * @param epoch 原 world token 的 epoch；必须 &gt; 0，否则视为 NO_IDENTITY
     */
    public record Context(String requestId, String worldId, String serverSession, long epoch,
                          String companionId, String role, String phase,
                          String taskId, String toolCallId, List<Ref> refs) {}

    /** 一次落账结果。noIdentity=true 表示什么都没写；ioFailed=true 表示有键写失败或账本不可读（可重试）。 */
    public record Result(int written, int skipped, boolean noIdentity, boolean ioFailed, List<String> artifacts) {}

    private SkillDeliveryEvidence() {}

    public static Result record(Context ctx, UsageLedger ledger) {
        if (ctx == null || ledger == null) {
            return new Result(0, 0, true, false, List.of());
        }
        if (!identityComplete(ctx)) {
            return new Result(0, 0, true, false, List.of());
        }
        String useKey = !blank(ctx.taskId()) && !blank(ctx.toolCallId())
                ? "task" : "episode";
        try {
            synchronized (ledger) {
                if (!ledger.readable()) {
                    // 读不到 ≠ 没记过：不许把空账本当未见再写一遍。
                    return new Result(0, 0, false, true, List.of());
                }
                Set<String> seen = new java.util.HashSet<>();
                for (UsageLedger.Entry e : ledger.all()) {
                    if (ctx.companionId().equals(e.companionId())) {
                        seen.add(e.artifactId());
                    }
                }
                int written = 0;
                int skipped = 0;
                boolean ioFailed = false;
                List<String> ids = new ArrayList<>();
                List<Ref> refs = ctx.refs() == null ? List.of() : ctx.refs();
                for (Ref ref : refs) {
                    if (ref == null || !ref.usable()) {
                        skipped++;
                        continue;
                    }
                    String artifactId = artifactId(ctx, ref, useKey);
                    if (seen.contains(artifactId)) {
                        skipped++;
                        continue;
                    }
                    try {
                        UsageLedger.Entry row = ledger.append(artifactId, KIND, ref.entryRef(),
                                UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN,
                                detail(ctx, ref, useKey), SOURCE_PREFIX + ctx.requestId(), ctx.companionId());
                        if (row == null) {
                            ioFailed = true;
                            continue;
                        }
                        written++;
                        ids.add(artifactId);
                        seen.add(artifactId);
                    } catch (RuntimeException e) {
                        ioFailed = true;
                    }
                }
                return new Result(written, skipped, false, ioFailed, List.copyOf(ids));
            }
        } catch (RuntimeException e) {
            // readable() 自身异常等：按不可判处理，绝不当成功。
            return new Result(0, 0, false, true, List.of());
        }
    }

    private static boolean identityComplete(Context ctx) {
        if (blank(ctx.requestId()) || blank(ctx.worldId()) || blank(ctx.serverSession())
                || blank(ctx.companionId()) || ctx.epoch() <= 0) {
            return false;
        }
        boolean hasTask = !blank(ctx.taskId());
        boolean hasCall = !blank(ctx.toolCallId());
        // 半身份不许退回 episode 掩盖：要么完整 task+call，要么都没有。
        return hasTask == hasCall;
    }

    /**
     * 幂等键：SHA-256(规范 JSON 数组[ref,version,companion,world,server,epoch,useKey])。
     * artifactId 前缀保留 ref@version 供人读，{@code #k=} 后是摘要。
     */
    static String artifactId(Context ctx, Ref ref, String useKey) {
        JsonArray key = new JsonArray();
        key.add(ref.entryRef());
        key.add(ref.version());
        key.add(ctx.companionId());
        key.add(ctx.worldId());
        key.add(ctx.serverSession());
        key.add(ctx.epoch());
        if ("task".equals(useKey)) {
            // 同一 body 任务跨多轮 request 只一条：任务幂等键里**不能**有 requestId。
            key.add("task");
            key.add(nz(ctx.taskId()));
            key.add(nz(ctx.toolCallId()));
        } else {
            // 没有 body 任务才用原 requestId 当 delivery-only episode 键。
            key.add("episode");
            key.add(ctx.requestId());
        }
        return ref.entryRef() + "@" + ref.version() + "#k=" + sha256(key.toString());
    }

    private static String detail(Context ctx, Ref ref, String useKey) {
        JsonObject o = new JsonObject();
        o.addProperty("skill_ref", ref.entryRef());
        o.addProperty("version", ref.version());
        o.addProperty("world_id", ctx.worldId());
        o.addProperty("server_session", ctx.serverSession());
        o.addProperty("epoch", ctx.epoch());
        o.addProperty("role", nz(ctx.role()));
        o.addProperty("phase", nz(ctx.phase()));
        o.addProperty("task_id", nz(ctx.taskId()));
        o.addProperty("tool_call_id", nz(ctx.toolCallId()));
        o.addProperty("request_id", nz(ctx.requestId()));
        o.addProperty("use_key", useKey);
        o.addProperty("delivery_only", "episode".equals(useKey));
        return o.toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
