package com.dwinovo.numen.agent.retrieval;

/**
 * 75 号 §15.5 `RetrievalProvider` —— 一家检索 provider 的最小实现接口。
 * 纯查询（不调 LLM，见 IC10 的预算条款）；provider 自带能力版本/预算/授权的解读权。
 *
 * <p><b>不是 @FunctionalInterface</b>（还差一个扩展点 `expand`，归 G5b/B5）——
 * 只写 `search` 或 lambda 都合法，但接口签名故意留出第二个方法位。
 */
public interface RetrievalProvider {

    String providerId();

    SearchPage search(RetrievalQuery query);
}
