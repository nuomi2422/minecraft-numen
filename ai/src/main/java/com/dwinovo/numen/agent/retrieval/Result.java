package com.dwinovo.numen.agent.retrieval;

/**
 * G5b 最小 `Result<T>`（75 号 §15.5 签名的最小子集）。
 *
 * <p>使用：`r.valueOr(null) → null 带 err / valueOr(sb) 填出 code+message`；
 * 或 `if (r.isErr()) …`。暂时不做 for-i / 泛型装箱优化 —— 领域只有两种状态。
 */
public sealed interface Result<T> permits Result.Ok, Result.Err {
    record Ok<T>(T value) implements Result<T> {
        public Ok { if (value == null) throw new NullPointerException("Ok.value 不能为空"); }
    }

    record Err<T>(String code, String message) implements Result<T> {
        public Err { if (code == null || code.isBlank()) throw new IllegalArgumentException("Err.code 不能为空"); }
    }
}
