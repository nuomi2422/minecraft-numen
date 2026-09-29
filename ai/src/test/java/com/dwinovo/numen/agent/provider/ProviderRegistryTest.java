package com.dwinovo.numen.agent.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站点注册表（只读内置 numen_providers.json）:
 * ctx 查询、生成参数缺省、别名规范化、本地部署站点在册。
 */
class ProviderRegistryTest {

    @Test
    void knownModelCtxAndUnknownFallback() {
        assertEquals(1000000, ProviderRegistry.contextWindow("deepseek", "deepseek-chat"));
        assertEquals(ProviderRegistry.DEFAULT_CTX, ProviderRegistry.contextWindow("deepseek", "no-such-model"));
    }

    @Test
    void generationParamsDefaultToUnset() {
        ProviderRegistry.Model m = ProviderRegistry.model("deepseek", "deepseek-chat");
        assertNotNull(m);
        assertNull(m.temperature());
        assertEquals(0, m.maxTokens());
    }

    @Test
    void unknownModelHasNoRegistryEntry() {
        assertNull(ProviderRegistry.model("deepseek", "custom-model-id"));
    }

    @Test
    void aliasesResolveThroughCanonicalId() {
        assertEquals(ProviderRegistry.baseUrl("moonshot"), ProviderRegistry.baseUrl("kimi"));
        assertEquals("thinking-type", ProviderRegistry.thinkingFormat("doubao"));
    }

    @Test
    void localDeploymentSitesAreRegistered() {
        for (String id : new String[]{"ollama", "lmstudio", "vllm"}) {
            assertTrue(ProviderRegistry.has(id), id);
            assertTrue(ProviderRegistry.baseUrl(id).startsWith("http://localhost:"), id);
        }
    }

    @Test
    void anthropicRowCarriesProtocol() {
        assertEquals("anthropic", ProviderRegistry.protocol("anthropic"));
        assertEquals("", ProviderRegistry.protocol("deepseek"));
    }

    /**
     * OpenCode Go 缺头就直接被拒（2026-09-29 实测）。
     *
     * <p>网关返回 {@code 400 MissingSessionID: "Request is missing x-opencode-session and
     * cannot be routed efficiently"}；文档同时要求客户端自报 User-Agent 而非用 SDK 名。
     * 这两个头是**接入 Go 的必要条件**，不是可选优化 —— 所以锁在这里：
     * 以后有人整理 numen_providers.json 时删掉它们，测试会立刻红。
     */
    @Test
    void openCodeGoSendsTheTwoHeadersItsGatewayDemands() {
        var h = ProviderRegistry.headers("opencode-go");
        assertTrue(h.containsKey("x-opencode-session"),
                "Go 网关缺 x-opencode-session 会 400 MissingSessionID");
        assertFalse(h.getOrDefault("x-opencode-session", "").isBlank(),
                "空 session id 等同没发");
        assertTrue(h.containsKey("User-Agent"), "Go 要求客户端自报身份");
        assertTrue(h.get("User-Agent").contains("numen"),
                "User-Agent 要能表明是哪个 agent，不要用 SDK 名");
        assertEquals("https://opencode.ai/zen/go/v1", ProviderRegistry.baseUrl("opencode-go"),
                "Go 与 Zen 是两套独立端点：写错会打空账户的余额制 Zen");
    }

    @Test
    void openCodeGoKnowsItsDeepSeekVariants() {
        // 带 .1 与不带 .1 是两个不同模型；踩错过：deepseek-v4-flash 在 Go 上 403，
        // 而 deepseek-v4.1-flash 200。所以两个都要在册。
        assertNotNull(ProviderRegistry.model("opencode-go", "deepseek-v4.1-flash"));
        assertNotNull(ProviderRegistry.model("opencode-go", "deepseek-v4-flash"));
        assertEquals(1000000, ProviderRegistry.contextWindow("opencode-go", "deepseek-v4.1-flash"));
    }
}
