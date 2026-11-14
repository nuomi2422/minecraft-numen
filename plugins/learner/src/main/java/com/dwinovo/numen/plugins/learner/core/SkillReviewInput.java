package com.dwinovo.numen.plugins.learner.core;

import com.dwinovo.numen.plugins.learner.skills.SkillBodyStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Historical review input only: original selected body and exact-call journal facts, never current permission.
 * Reuses the original result snapshot parser and body reader; no registry fallback, time-neighbor join or writer. */
final class SkillReviewInput {
    private static final int TRACE_BYTES = 4 * 1024 * 1024;
    private static final int TRACE_ROWS = 6;
    private SkillReviewInput() {}

    static String forMemo(Path dir, UUID companion, Memo memo, int limit) {
        if ("skill_delivery".equals(memo.stage()))
            return "技能复盘输入: body_status=NOT_AVAILABLE trace_status=NOT_AVAILABLE（原交付样本无历史正文摘要或原任务回执，不猜补）";
        var fact = SkillDeliveryIntake.taskResultFromSnapshot(memo.snapshot());
        if (fact == null || companion == null || !companion.toString().equals(fact.scope().companionId()))
            return "技能复盘输入: source_status=UNKNOWN（原任务快照或同伴身份不可核）";
        StringBuilder out = new StringBuilder("技能复盘输入: source_status=ORIGINAL_TASK_CANDIDATE；非采用/有效性证明\n");
        // Identity already lives in the memo. Bound repeats so malicious metadata cannot displace the body budget.
        out.append("ref=").append(clip(fact.ref(), 100)).append('@').append(clip(fact.version(), 32))
                .append(" task_id=").append(clip(fact.taskId(), 80))
                .append(" call_id=").append(clip(fact.taskCall(), 100)).append('\n');
        String body = originalBody(dir, fact, limit);
        if (body == null) out.append("body_status=UNKNOWN（原正文缺失、不可核或摘要不符）\n");
        else if (body.length() > limit - out.length() - 260)
            out.append("body_status=UNKNOWN_LIMIT（原正文超过既有切片预算，未冒充完整）\n");
        else out.append("body_status=COMPLETE\n<original-skill-body>\n").append(body).append("\n</original-skill-body>\n");
        Trace trace = trace(dir, fact);
        String status = trace.limited() ? "UNKNOWN_LIMIT" : trace.rows().isEmpty() ? "NOT_AVAILABLE" : "EXACT_CALL";
        int reserve = 100;
        List<String> included = new ArrayList<>();
        for (String row : trace.rows()) {
            if (out.length() + reserve + row.length() + 1 > limit) { status = "UNKNOWN_LIMIT"; break; }
            included.add(row); reserve += row.length() + 1;
        }
        out.append("trace_status=").append(status).append("（仅原scope+call；非前后世界状态）\n");
        for (String row : included) out.append(row).append('\n');
        return out.toString();
    }

    private static String originalBody(Path dir, SkillDeliveryIntake.TaskResultFact fact, int limit) {
        String ref = fact.bodyRef();
        // BodyStore is the existing integrity reader. Do not let a historical ref escape its original store.
        if (!ref.matches("skill-body:[A-Za-z0-9_-]+@v[1-9][0-9]*-[A-Za-z0-9_-]+[.]md")
                || !fact.bodyDigest().matches("[0-9a-f]{64}")) return null;
        try {
            Path file = dir.resolve("skill-bodies").resolve(ref.substring("skill-body:".length()));
            if (!Files.isRegularFile(file) || Files.size(file) > 4L * limit) return null;
            String body = SkillBodyStore.read(dir, ref);
            if (body == null || body.isBlank()) return null;
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
            return fact.bodyDigest().equals(digest) ? body : null;
        } catch (IOException | NoSuchAlgorithmException | RuntimeException unavailable) {
            return null;
        }
    }

    private record Trace(List<String> rows, boolean limited) {}
    private static Trace trace(Path dir, SkillDeliveryIntake.TaskResultFact fact) {
        Path file = dir.resolve("monitor/tools.jsonl");
        if (!Files.isRegularFile(file)) return new Trace(List.of(), false);
        List<String> rows = new ArrayList<>();
        boolean limited = false;
        try (var channel = Files.newByteChannel(file)) {
            long size = channel.size(), start = Math.max(0, size - TRACE_BYTES);
            limited = start > 0;
            channel.position(start);
            ByteBuffer bytes = ByteBuffer.allocate((int) (size - start));
            while (bytes.hasRemaining() && channel.read(bytes) > 0) { /* bounded original tail */ }
            if (bytes.hasRemaining()) limited = true; // concurrent truncation is incomplete, never a complete trace
            bytes.flip();
            String text = StandardCharsets.UTF_8.decode(bytes).toString();
            if (start > 0) {
                int first = text.indexOf('\n'); // discard a partial original line, never parse an invented prefix
                text = first < 0 ? "" : text.substring(first + 1);
            }
            for (String line : text.split("\n")) {
                if (line.isBlank()) continue;
                try {
                    JsonObject envelope = JsonParser.parseString(line).getAsJsonObject();
                    if (!"tool_result".equals(str(envelope, "type"))
                            || !fact.scope().worldId().equals(str(envelope, "world_id"))
                            || !fact.scope().serverSession().equals(str(envelope, "server_session"))
                            || !envelope.has("world_epoch")
                            || !envelope.getAsJsonPrimitive("world_epoch").isNumber()
                            || envelope.get("world_epoch").getAsBigDecimal().longValueExact() != fact.scope().epoch()) continue;
                    JsonObject data = envelope.getAsJsonObject("data");
                    if (!fact.scope().companionId().equals(str(data, "companion_id"))
                            || !fact.taskCall().equals(str(data, "tool_call_id"))
                            || !str(data, "journaled_by").startsWith("server")) continue;
                    String event = str(envelope, "event_id");
                    if (event.isBlank()) continue; // no fabricated trace/event identity
                    if (data.has("task_id") && !fact.taskId().equals(str(data, "task_id"))) continue;
                    String tool = str(data, "tool"), result = str(data, "result");
                    if (event.length() > 100 || tool.length() > 80 || result.length() > 180) limited = true;
                    rows.add("event:" + clip(event, 100) + " tool=" + clip(tool, 80)
                            + " result=" + clip(result, 180));
                    if (rows.size() > TRACE_ROWS) { rows.remove(0); limited = true; }
                } catch (RuntimeException malformed) { limited = true; }
            }
        } catch (IOException | RuntimeException unavailable) { return new Trace(List.of(), true); }
        return new Trace(List.copyOf(rows), limited);
    }

    private static String str(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isString()) return "";
        return object.get(key).getAsString();
    }
    private static String clip(String text, int limit) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= limit ? oneLine : oneLine.substring(0, limit) + "…";
    }
}
