package com.dwinovo.numen.plugins.learner.sample;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * TTL 归档的时长配置（158 §8：\"只归档不删证据\"）。文件：`config/numen/learner-sample-ttl.json`。
 *
 * <h2>为什么要可配而不是写死</h2>
 * 7 天是<b>未校准</b>的初值：一条样本"放多久算过期"取决于实际复盘吞吐，
 * 而实测队列长期 64/64 满、复盘还会 LLM 超时（162 §27.8）。
 * 写死的 7 天在这种情况下等于"永不归档"，待办会一直堆。
 *
 * <h2>为什么提供一个 \"minutes\"</h2>
 * 只为了<b>能验证</b>：等 7 天再去看归档跑没跑，等于没验。
 * 用分钟档可以把"归档真的会改状态、且不动证据"在一次会话里跑出来。
 * 生产请用 `days`。
 *
 * <p>缺文件 / 解析失败 / 数值非法 ⇒ 回退默认 7 天（不许因为配置写坏就停止归档）。
 * 纯 JVM，可离线单测。
 */
public final class SampleTtlConfig {

    public static final String FILE = "learner-sample-ttl.json";
    public static final long DEFAULT_DAYS = 7L;
    public static final long MIN_MINUTES = 1L;
    public static final long MAX_DAYS = 365L;

    private SampleTtlConfig() {
    }

    /** 读配置得到的 TTL（毫秒）。任何异常都回退默认。 */
    public static long ttlMillis(Path configDir) {
        if (configDir == null) {
            return DEFAULT_DAYS * 24 * 60 * 60 * 1000L;
        }
        Path f = configDir.resolve(FILE);
        if (!Files.isRegularFile(f)) {
            return DEFAULT_DAYS * 24 * 60 * 60 * 1000L;
        }
        try {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            if (o.has("minutes") && !o.get("minutes").isJsonNull()) {
                long m = Math.max(MIN_MINUTES, o.get("minutes").getAsLong());
                return m * 60_000L;
            }
            if (o.has("days") && !o.get("days").isJsonNull()) {
                long d = o.get("days").getAsLong();
                d = Math.max(1L, Math.min(MAX_DAYS, d));
                return d * 24 * 60 * 60 * 1000L;
            }
            return DEFAULT_DAYS * 24 * 60 * 60 * 1000L;
        } catch (RuntimeException | java.io.IOException e) {
            return DEFAULT_DAYS * 24 * 60 * 60 * 1000L;
        }
    }
}
