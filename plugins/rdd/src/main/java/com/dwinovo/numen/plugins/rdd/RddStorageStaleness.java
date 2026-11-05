package com.dwinovo.numen.plugins.rdd;

/**
 * 「旧缓存要标历史」的纯逻辑（2026-11-05，R2）。
 *
 * <p>为什么单独成类：RddContainerMemory 的类加载会碰到 Minecraft 类型
 * （{@code net/minecraft/world/level/Level}），在纯 JVM 单测里直接 {@code NoClassDefFoundError}。
 * 判定规则本身与游戏无关，抽出来后可以离线钉住。
 *
 * <p>规则：只有**超龄**才标；标一条 {@code stale="true" age_s="N"} 并把现在时的「你身边的容器里放着：」
 * 改成「(N 秒前看到的) 容器里放着：」——内容一个字不改，调用方仍能拿到上次看到的东西。
 */
final class RddStorageStaleness {

    /** 超过这个年龄的缓存必须带 stale 标记（2 秒扫描节奏的 5 倍，避免正常抖动被标旧）。 */
    static final long STALE_AFTER_MS = 10_000L;

    private RddStorageStaleness() {}

    static String markStale(String text, long ageMs, long staleAfterMs) {
        if (text == null || text.isEmpty() || ageMs <= staleAfterMs) return text;
        long ageS = Math.max(1, ageMs / 1000);
        String marked = text.replaceFirst("<storage ",
                "<storage stale=\"true\" age_s=\"" + ageS + "\" ");
        return marked.replace("你身边的容器里放着：", "(" + ageS + " 秒前看到的) 容器里放着：");
    }
}
