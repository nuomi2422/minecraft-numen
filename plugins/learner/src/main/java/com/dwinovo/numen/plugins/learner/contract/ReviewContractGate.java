package com.dwinovo.numen.plugins.learner.contract;

import com.dwinovo.numen.plugins.learner.blocks.EnvBlock;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * 复盘契约闸门的**运行模式**（159 E：让契约成为真门）。
 *
 * <h2>三档，默认 shadow</h2>
 * <ul>
 *   <li>{@link Mode#OFF} —— 完全不跑；</li>
 *   <li>{@link Mode#SHADOW} —— <b>照常落库</b>，但把「按新契约会不会被拒」记进 `reviewed` 事件。
 *       这是默认值：旧格式没有结构化证据，直接 ENFORCE 会让学习线当场停摆；</li>
 *   <li>{@link Mode#ENFORCE} —— 不通过就不 commit，memo 退回队列。</li>
 * </ul>
 *
 * <h2>为什么默认不是 ENFORCE</h2>
 * 158/159 要的是"真的门"，但把门关上之前必须先有数字：
 * 现在有多少条判定会因为缺证据被拒。没有这个数就切 ENFORCE，
 * 结果不是"学习变严格了"，而是"学习停了而且没人知道为什么"。
 *
 * <p>纯 JVM，可离线单测。
 */
public final class ReviewContractGate {

    /** 配置文件名（与 `learner-sample-intake.json` 同目录同风格）。 */
    public static final String FILE_NAME = "learner-review-contract.json";

    /** 运行模式。 */
    public enum Mode {
        OFF,
        SHADOW,
        ENFORCE
    }

    /**
     * 一次闸门判定。
     *
     * @param blocked 是否<b>真的</b>拦下（只有 ENFORCE 且不通过才为 true）
     */
    public record Decision(Mode mode, boolean blocked, Submission.Result result,
                           ReviewDraft.Status status, java.util.List<String> codes) {
        public Decision {
            codes = codes == null ? java.util.List.of() : java.util.List.copyOf(codes);
        }
    }

    private ReviewContractGate() {
    }

    /** 解析配置：只认 `{"mode":"off|shadow|enforce"}`；坏配置 → SHADOW（不因坏配置关死门）。 */
    public static Mode parseMode(String json) {
        if (json == null || json.isBlank()) {
            return Mode.SHADOW;
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            var e = o.get("mode");
            if (e == null || e.isJsonNull()) {
                return Mode.SHADOW;
            }
            return switch (e.getAsString().trim().toLowerCase(java.util.Locale.ROOT)) {
                case "off" -> Mode.OFF;
                case "enforce" -> Mode.ENFORCE;
                default -> Mode.SHADOW;
            };
        } catch (RuntimeException e) {
            return Mode.SHADOW;
        }
    }

    public static Mode mode(Path configDir) {
        if (configDir == null) {
            return Mode.SHADOW;
        }
        Path f = configDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(f)) {
            return Mode.SHADOW;
        }
        try {
            return parseMode(Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return Mode.SHADOW;
        }
    }

    /**
     * 评测一次提交。
     *
     * <p>OFF 时返回 {@code null}（真的不跑，连计数都不做）。
     */
    public static Decision evaluate(Mode mode, Submission.Draft draft,
                                    Function<String, Optional<EnvBlock>> blockLookup) {
        if (mode == null || mode == Mode.OFF) {
            return null;
        }
        Submission.Result r = Submission.validate(draft, blockLookup);
        boolean blocked = mode == Mode.ENFORCE && !r.accepted();
        return new Decision(mode, blocked, r, r.status(), r.codes());
    }
}
