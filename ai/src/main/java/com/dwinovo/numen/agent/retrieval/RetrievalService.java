package com.dwinovo.numen.agent.retrieval;

/**
 * 75 号 §15.5 `RetrievalService` —— 检索的<b>统一入口</b>（G5c 最小版）。
 *
 * <p>「统一入口」的最小含义：调用方不再逐个 provider 问，只对着这个接口说话：
 * <ul>
 *   <li>{@link #buildContext}：多 provider 合并 → 预算 → 去重 → {@code ContextSnapshot}。</li>
 *   <li>{@link #expand}：按 {@code EntryRef} 找到 owning provider 再转发。</li>
 * </ul>
 */
public interface RetrievalService {

    ContextSnapshot buildContext(RetrievalQuery query);

    Result<EntryBody> expand(EntryRef ref, ExpansionRequest request);
}
