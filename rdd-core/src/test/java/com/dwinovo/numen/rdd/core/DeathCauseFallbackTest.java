package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-04：最后伤害来源兜底（2026-10-27）。
 *
 * <p>实测缺口：最近 3 天 52 次死亡里 8 次归因 `unknown`，原始数据长这样：
 * <pre>
 *   deathCauseId="genericKill", deathCauseMsg="RDD was killed", deathKind="unknown"
 * </pre>
 * 原版在拿不到凶手时给的就是这条泛化文案。但死亡那一刻实体上还留着
 * 最后一次伤害来源（msgId + 直接来源实体名），可以拿它做兜底。
 *
 * <p>本类的纪律同时也是护栏：<b>兜底只能把 UNKNOWN 变成已知，不能把已知改掉，
 * 也不能把"读不到"编成"被怪杀"</b>。
 */
class DeathCauseFallbackTest {

    @Test
    void 原分类已知时兜底不插手() {
        assertEquals("lava",
                DeathCauseClassifier.classifyWithFallback("inFire", "烧死", "mob_attack", "Zombie"),
                "原分类已经给出 lava，兜底不许改写");
        assertEquals("drown",
                DeathCauseClassifier.classifyWithFallback("drown", "淹死", "arrow", "Skeleton"));
    }

    @Test
    void genericKill用最后伤害来源救回来() {
        assertEquals("mob",
                DeathCauseClassifier.classifyWithFallback("genericKill", "RDD was killed", "mob_attack", "Zombie"),
                "genericKill + mob_attack → mob（这正是那 8 次的样子）");
        assertEquals("mob",
                DeathCauseClassifier.classifyWithFallback("genericKill", "RDD was killed", "arrow", ""),
                "投射物 msgId 也算 mob（骷髅的箭）");
        assertEquals("explosion",
                DeathCauseClassifier.classifyWithFallback("genericKill", "RDD was killed", "explosion", "Creeper"));
    }

    @Test
    void 伤害来源也读不到时仍诚实报unknown() {
        assertEquals("unknown",
                DeathCauseClassifier.classifyWithFallback("genericKill", "RDD was killed", "", ""),
                "两样都没有 → 必须还是 unknown；猜成 mob 就是把'不知道'冒充成已知");
        assertEquals("unknown",
                DeathCauseClassifier.classifyWithFallback(null, null, null, null));
    }

    @Test
    void 只有实体名时按名字词表判() {
        assertEquals("mob",
                DeathCauseClassifier.classifyWithFallback("genericKill", "RDD was killed", "unknown_id", "Skeleton"),
                "id 不认得但来源名认得 → mob");
        assertEquals("unknown",
                DeathCauseClassifier.classifyWithFallback("genericKill", "RDD was killed", "unknown_id",
                        "Armor Stand"),
                "名字也不认得 → 不许硬猜");
    }

    @Test
    void 玩家凶手不被兜底吞成怪() {
        assertEquals("player",
                DeathCauseClassifier.classifyWithFallback("playerAttack", "被Steve杀死了", "player_attack", "Steve"),
                "玩家杀是主人插手，归因方向与怪物相反，不许混");
    }

    @Test
    void 兜底不抛异常() {
        assertDoesNotThrow(() -> DeathCauseClassifier.classifyWithFallback(null, "", null, ""));
        assertDoesNotThrow(() -> DeathCauseClassifier.classifyWithFallback("", "", "", ""));
    }
}
