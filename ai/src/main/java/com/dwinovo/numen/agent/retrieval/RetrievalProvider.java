package com.dwinovo.numen.agent.retrieval;

/**
 * 75 号 §15.5 `RetrievalProvider` —— 一家检索 provider 的最小实现接口。
 * 纯查询（不调 LLM，见 IC10 的预算条款）；provider 自带能力版本/预算/授权的解读权。
 *
 * <p><b>不是 @FunctionalInterface</b> —— G5b 加了 `expand`，实现侧只要覆写一个即可。
 */
public interface RetrievalProvider {

    String providerId();

    SearchPage search(RetrievalQuery query);

    /**
     * G5b（§15.5 `expand(EntryRef, ExpansionRequest)`）—— 按需展开压缩 descriptor 成可用 body。
     *
     * <p><b>default 返 {@code Err("UNSUPPORTED")}</b>：不外抛（与 RuntimeException 不同），
     * 调用方 getErr 可得 code + message，不影响 G5a 的既有消费者。
     */
    default Result<EntryBody> expand(EntryRef ref, ExpansionRequest request) {
        return new Result.Err<>("UNSUPPORTED", providerId() + " 未覆写 expand");
    }
}
