package com.dwinovo.numen.plugins.rdd;

/** Pure 2026-11-09 test seam: stale side context must not leak old facts. */
final class SideContextDecorator {
    private SideContextDecorator() {}

    static String decorate(String block, String cachedWorldId, String cachedSession,
                           String dimension, long sampledGameTime,
                           String currentWorldId, String currentSession) {
        boolean same = block != null && cachedWorldId != null && cachedSession != null
                && cachedWorldId.equals(currentWorldId) && cachedSession.equals(currentSession);
        if (!same) {
            return "<rdd><enabled>true</enabled><active>false</active>"
                    + "<side_context_status>UNKNOWN</side_context_status>"
                    + "<note>支线上下文来源/生命周期不匹配，不注入旧事实；请重新评估当前目标。</note></rdd>";
        }
        return block.replace("</rdd>",
                "<side_context_status>CURRENT</side_context_status>"
                        + "<side_context_dimension>" + dimension + "</side_context_dimension>"
                        + "<side_context_sampled_game_time>" + sampledGameTime + "</side_context_sampled_game_time></rdd>");
    }
}
