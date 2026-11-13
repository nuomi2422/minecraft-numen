package com.dwinovo.numen.plugins.learner.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-13 C1 学习者侧接缝：把「一次最终请求里完整交付的 skill 正文」写成**现有**
 * {@link UsageLedger} 的 PRESENTED/UNKNOWN 证据。
 *
 * <p>中性数据端口：本类不依赖 {@code api/common} 的 DeliveredContext/WorldContextStamp，
 * 父接线时只需把 {@link Context} 的字段映射过来。规则（父 2026-11-13 口述）：
 * <ul>
 *   <li>缺真实 requestId / worldToken（world/server/epoch）→ **NO_IDENTITY**：不造伪
 *       {@code episode=UNKNOWN}、不落任何行，旧曝光审计（ExposureLedger）不受影响；</li>
 *   <li>使用实例优先原 body 任务：taskId+toolCallId 齐全时用 {@code task=<id>:<toolCallId>} 做键，
 *       <b>同一任务多轮请求只留一条候选</b>；没有 body 任务才用原 requestId，并标 delivery-only；</li>
 *   <li>幂等键 = 完整 ref/version + 原同伴 + world/server/epoch + 使用实例；
 *       重放同键不双写（append 前扫 all()）；</li>
 *   <li>只写 PRESENTED/UNKNOWN：绝不写 RESULT/SUCCESS、不 claimValidity、不触发复核或世界动作；</li>
 *   <li>IO 失败不吞成成功：返回 ioFailed=true，不缓存任何状态，重试时用同一键补齐。</li>
 * </ul>
 */
public final class SkillDeliveryEvidence {

    /** 账本 kind（skill 交付证据）；不是经验/AC 的执行 kind。 */
    public static final String KIND = "SKILL";
    public static final String SOURCE_PREFIX = "request-delivery:";

    /** 一条被交付的引用（中性形状；父侧 DeliveredRef 映射到这里）。 */
    public record Ref(String entryRef, String version) {
        public Ref {
            entryRef = entryRef == null ? "" : entryRef.trim();
            version = version == null ? "" : version.trim();
        }

        /** 只接受规范 {@code skill:<id>} + 非空版本；exp/AC 等其它引用不归这里。 */
        public boolean usable() {
            return entryRef.startsWith("skill:") && entryRef.length() > "skill:".length() && !version.isBlank();
        }
    }

    /**
     * 中性交付上下文，字段与父侧 DeliveredContext 一一对应。
     *
     * @param epoch -1 = 没有真实 worldToken（NO_IDENTITY），不能伪造
     */
    public record Context(String requestId, String worldId, String serverSession, long epoch,
                          String companionId, String role,
                          String taskId, String toolCallId, List<Ref> refs) {}

    /** 一次落账结果。noIdentity=true 表示什么都没写；ioFailed=true 表示有键写失败（可重试）。 */
    public record Result(int written, int skipped, boolean noIdentity, boolean ioFailed, List<String> artifacts) {}

    private SkillDeliveryEvidence() {}

    public static Result record(Context ctx, UsageLedger ledger) {
        if (ctx == null || ledger == null) {
            return new Result(0, 0, true, false, List.of());
        }
        if (blank(ctx.requestId()) || blank(ctx.worldId()) || blank(ctx.serverSession())
                || blank(ctx.companionId()) || ctx.epoch() < 0) {
            // NO_IDENTITY：缺真实 episode/worldToken 就不落行，绝不造 episode=UNKNOWN 的伪候选。
            return new Result(0, 0, true, false, List.of());
        }
        String useKey = !blank(ctx.taskId()) && !blank(ctx.toolCallId())
                ? "task=" + ctx.taskId() + ":" + ctx.toolCallId()
                : "episode=" + ctx.requestId();
        java.util.Set<String> seen = new java.util.HashSet<>();
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
                ledger.append(artifactId, KIND, ref.entryRef(), UsageLedger.Phase.PRESENTED,
                        UsageLedger.Outcome.UNKNOWN, detail(ctx, ref, useKey), SOURCE_PREFIX + ctx.requestId(),
                        ctx.companionId());
                written++;
                ids.add(artifactId);
                seen.add(artifactId);
            } catch (RuntimeException e) {
                // IO/持久化失败：不冒充已记、不留缓存；同一键下次请求可重试补齐。
                ioFailed = true;
            }
        }
        return new Result(written, skipped, false, ioFailed, List.copyOf(ids));
    }

    /** 幂等键：ref@version + 使用实例 + 原同伴/world/server/epoch。测试直接调它钉格式。 */
    static String artifactId(Context ctx, Ref ref, String useKey) {
        return ref.entryRef() + "@" + ref.version() + "#" + useKey
                + "#c=" + ctx.companionId() + "#w=" + ctx.worldId()
                + "#s=" + ctx.serverSession() + "#e=" + ctx.epoch();
    }

    private static String detail(Context ctx, Ref ref, String useKey) {
        return "{\"skill_ref\":\"" + ref.entryRef() + "\",\"version\":\"" + ref.version()
                + "\",\"world_id\":\"" + ctx.worldId() + "\",\"server_session\":\"" + ctx.serverSession()
                + "\",\"epoch\":" + ctx.epoch() + ",\"role\":\"" + nz(ctx.role())
                + "\",\"task_id\":\"" + nz(ctx.taskId()) + "\",\"tool_call_id\":\"" + nz(ctx.toolCallId())
                + "\",\"use_key\":\"" + useKey + "\",\"delivery_only\":" + useKey.startsWith("episode=") + "}";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
