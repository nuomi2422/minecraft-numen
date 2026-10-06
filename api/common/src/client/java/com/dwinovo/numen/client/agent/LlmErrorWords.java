package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.http.LlmHttpException;
import net.minecraft.network.chat.Component;

/**
 * LLM 失败 → 分类人话的唯一真源。话术纪律:说人话 + 说下一步,不甩堆栈
 * (堆栈的去处是日志,传输层已经记全了)。设置屏的检测按钮与回合失败的
 * HUD/聊天栏播报共用这一张表——同一种错在哪里看到都是同一句话。
 */
import com.dwinovo.numen.data.ModLanguageData;

public final class LlmErrorWords {

    private LlmErrorWords() {}

    public static String classify(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof LlmHttpException http) {
            if (http.isUnauthorized()) return t(ModLanguageData.Keys.GUI_PROVIDERS_CHECK_UNAUTHORIZED);
            if (http.statusCode() == 404) return t(ModLanguageData.Keys.GUI_PROVIDERS_CHECK_NOT_FOUND);
            if (http.isRateLimited()) return t(ModLanguageData.Keys.GUI_PROVIDERS_CHECK_RATE_LIMITED);
            if (http.statusCode() >= 500) return t(ModLanguageData.Keys.GUI_PROVIDERS_CHECK_SERVER_ERROR);
            return t(ModLanguageData.Keys.GUI_PROVIDERS_CHECK_BAD_REQUEST) + " (HTTP " + http.statusCode() + ")";
        }
        return t(ModLanguageData.Keys.GUI_PROVIDERS_CHECK_NETWORK);
    }

    /**
     * HTTP 状态码；不是 {@link LlmHttpException} 时返回 <b>-1</b>（网络类故障）。
     *
     * <p>存在的意义：把「怎么从异常链里找到 {@code LlmHttpException}」这条规则
     * <b>只写一遍</b> —— {@link #classify} 与调用方的熔断判断必须看同一份口径，
     * 否则会出现「人话说是 400，熔断却按网络故障处理」这种两端口径漂移。
     */
    public static int httpStatus(Throwable error) {
        Throwable cause = unwrap(error);
        return cause instanceof LlmHttpException http ? http.statusCode() : -1;
    }

    /** 顺着 cause 链找 {@link LlmHttpException}；找不到返回链条末端。 */
    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause != null && !(cause instanceof LlmHttpException) && cause.getCause() != null
                && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String t(String key) {
        return Component.translatable(key).getString();
    }
}
