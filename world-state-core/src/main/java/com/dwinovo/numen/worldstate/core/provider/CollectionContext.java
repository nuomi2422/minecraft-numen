package com.dwinovo.numen.worldstate.core.provider;

/**
 * 采集器执行时的上下文。刻意<b>不</b>暴露任何 Minecraft 类型——纯核心不认识世界。
 * 宿主侧（plugins/worldstate）用真实服务器实现它。
 */
public interface CollectionContext {

    /** 世界存档标识（命名空间的一部分）。 */
    String worldId();

    /**
     * 2026-11-05：旧宿主没有会话证据时返回空串（unknown）。不能默认 worldId：同一存档重开后
     * worldId 不变，旧观测却已不是本轮采集；严格读取必须等待带会话的新刷新。
     */
    default String serverSession() {
        return "";
    }

    /** 当前游戏 tick。 */
    long gameTick();

    /** 当前实际时间（毫秒），用于写观测的时间戳。 */
    long nowMs();
}
