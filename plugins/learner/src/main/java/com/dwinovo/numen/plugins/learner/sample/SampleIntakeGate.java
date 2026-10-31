package com.dwinovo.numen.plugins.learner.sample;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 样本层的<strong>运行开关</strong>：`<configDir>/learner-sample-intake.json`，形如 `{"enabled":true}`。
 *
 * <h2>为什么默认是关，而且缺文件时不自动建</h2>
 * 同目录里 `learner-auto-intake.json` 的默认是<b>开</b>，它的理由是「宁可多扫，不静默失明」。
 * 样本层相反：<b>它现在还没有消费者</b> —— 复盘调度、TTL 归档、环境块目录都在 D/H 段。
 * 打开它只会让 `samples.jsonl` 一直长而没有任何东西读，还多一份没人核对的证据。
 * 「写了但不被读」比「没写」更容易被误读成「学习已经跑起来了」，所以默认关。
 *
 * <p>同理，缺文件时<b>不</b>自动生成：自动生成会让「默认关」变成一个看起来被配置过的文件，
 * 下一个人会以为有人特意关掉了它。
 *
 * <h2>读失败往哪倒</h2>
 * 坏 JSON / 读不了 → <b>关</b>（失败往安全侧倒），并给出 reason 让调用方记日志。
 * 这与 AutoIntake「读失败保持开」不同，原因同上：开着的代价是制造没消费者的数据。
 *
 * <p>纯 JVM、可离线单测。
 */
public final class SampleIntakeGate {

    /** 开关文件名（与 `learner-auto-intake.json` 同目录同风格）。 */
    public static final String FILE_NAME = "learner-sample-intake.json";

    private SampleIntakeGate() {
    }

    /**
     * 开关状态。
     *
     * @param enabled 是否启用样本层
     * @param reason  {@code enabled_file} / {@code disabled_by_file} / {@code no_file} / {@code malformed} / {@code unreadable}
     */
    public record State(boolean enabled, String reason) {
        public State {
            reason = reason == null ? "" : reason;
        }

        public static State off(String reason) {
            return new State(false, reason);
        }

        public static State on(String reason) {
            return new State(true, reason);
        }
    }

    /** 解析配置正文。空/坏 → 关。 */
    public static State parse(String json) {
        if (json == null || json.isBlank()) {
            return State.off("malformed");
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return State.off("malformed");
            }
            JsonObject o = root.getAsJsonObject();
            JsonElement e = o.get("enabled");
            if (e == null || e.isJsonNull()) {
                return State.off("malformed");
            }
            // 只认明确的 true：写成 "true" 字符串、1、"yes" 一律不放行。
            boolean on = e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
            return on ? State.on("enabled_file") : State.off("disabled_by_file");
        } catch (RuntimeException ex) {
            return State.off("malformed");
        }
    }

    /** 从 configDir 读开关。缺文件 → 关（不建文件）。 */
    public static State read(Path configDir) {
        if (configDir == null) {
            return State.off("no_file");
        }
        Path f = configDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(f)) {
            return State.off("no_file");
        }
        try {
            return parse(Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return State.off("unreadable");
        }
    }
}
