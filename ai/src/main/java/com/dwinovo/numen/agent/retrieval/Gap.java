package com.dwinovo.numen.agent.retrieval;

/**
 * G5a 最小 `Gap`：按 provider 归属，程序码可 grep、报文带 buyer 手感（≤3 行）。
 *
 * <p>「未召回」与「读取失败」分开（IC09）；装不下条目的截断原因挂在
 * {@code ContextSnapshot.truncationReasons}.
 */
public record Gap(String providerId, String code, String message) {

    public Gap {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Gap.code 不能为空");
    }
}
