package com.dwinovo.numen.agent.retrieval;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 75 号 §15.5 `RetrievalRegistration`（最小实现）—— 独立能力接口的注册表。
 *
 * <p>行为判据：
 * <ul>
 *   <li>同 {@code providerId} 第二次注册被 <b>拒绝</b>（不静默最后写入者覆盖）——跟
 *       `NumenPlugins` 的「重名跳过」前辈行为同形。</li>
 *   <li>{@code byId} 找不到 → Optional.empty；不做静默 fallback。</li>
 * </ul>
 *
 * <p><b>本批不做</b>：插件卸载时注销相关缓存失效（§15.8-B5）——
 * {@code remove(providerId)} 只是登记用，消毒语义归后续批。
 */
public final class RetrievalRegistry {

    private final Map<String, RetrievalProvider> byId = new LinkedHashMap<>();

    public synchronized Registration register(RetrievalProvider provider) {
        if (provider == null) {
            return Registration.rejected(null, "provider 不能为空");
        }
        String id = provider.providerId() == null ? "" : provider.providerId();
        if (id.isBlank()) {
            return Registration.rejected(id, "providerId 不能为空");
        }
        if (byId.containsKey(id)) {
            return Registration.rejected(id, "重复 providerId（不许静默覆盖）");
        }
        byId.put(id, provider);
        return Registration.accepted(id);
    }

    public synchronized Optional<RetrievalProvider> byId(String providerId) {
        return Optional.ofNullable(byId.get(providerId));
    }

    public synchronized boolean remove(String providerId) {
        return byId.remove(providerId) != null;
    }

    public synchronized int size() {
        return byId.size();
    }
}
