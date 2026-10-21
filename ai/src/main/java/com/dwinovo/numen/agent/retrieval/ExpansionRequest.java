package com.dwinovo.numen.agent.retrieval;

import java.util.Objects;

/**
 * 75 号 §15.5 `ExpansionRequest` —— 按需 body 展开的请求。
 *
 * <p>本批最小版：只保留判据/测试需要的字段。
 * 未来 B6 的 `authorizationRef`/预算授权/revision 追溯由后续批填，
 * 这里不给默认值以免掩盖调用方没填的事实。
 */
public record ExpansionRequest(
        String queryId,
        RetrievalRole role,
        int maxChars,
        String authorizationRef) {

    public ExpansionRequest {
        queryId = queryId == null ? "" : queryId;
        authorizationRef = authorizationRef == null ? "" : authorizationRef;
    }

    public boolean isIdle() {
        return queryId.isBlank();
    }
}
