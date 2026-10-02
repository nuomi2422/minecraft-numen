package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T4 · 世界事实判据的合法值清单（钉 schema）。
 *
 * <h2>为什么这个类只有 1 个测试却必须补</h2>
 *
 * <p>改动前 {@code WorldFactConditionsTest} 全仓只有<b>一个</b>测试方法
 * （{@code validatesExplicitEvidenceOnly}，8 个断言）。也就是说：<b>整个世界观只由 4 个
 * 字符串字面量决定，而它们没有任何独立的值钉死。</b>
 *
 * <h2>扩判据是「三处必须同步」的高危操作</h2>
 *
 * <pre>
 *   ① WorldFactConditions.valid / knownType   ← schema（这里）
 *   ② RddWorldFacts.matches                   ← 真身（Minecraft 侧读世界）
 *   ③ RddDecomposer SYSTEM_PROMPT + prompt     ← 告知规划器有哪些合法条件
 * </pre>
 *
 * <p>漏掉任何一处，后果都是 RL-18 同族事故：<b>规划器生成了一个永远判不出来的条件，
 * 整条链静默卡死且不产生失败</b>。更隐蔽的是
 * {@code RddDecomposer.parseOne} 现在对未知 type 是<b>静默丢弃整条二级、连报错都没有</b>——
 * 漏改 ① 时的表现是「规划器产出被悄悄吃掉」，看起来像模型不听话。
 *
 * <p>本类把「合法清单」变成显式的值，并附一条护栏：
 * 只要有人加了新 type，本类必须同步更新 —— 更新时会被迫去看另外两处。
 *
 * <p>零生产改动、零构建改动、零 Minecraft（纯 schema，不读世界）。
 */
class WorldFactConditionsPinTest {

    private static Map<String, Object> c(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ── ① 合法 type 清单（核心 pin）─────────────────────────────────

    /** 本仓当前<b>唯一</b>合法的 4 个世界事实类型。加新 type 时本清单必须同步改。 */
    private static final List<String> PINNED_TYPES = List.of("advancement", "structure", "entity_killed", "base");

    @Test
    void knownTypesArePinnedToExactlyFour() {
        for (String t : PINNED_TYPES) {
            assertTrue(WorldFactConditions.knownType(t), "'" + t + "' 必须是已知世界事实类型");
        }
        // 逐个核对「常见的误加项」都不在清单里
        for (String notKnown : List.of(
                "inventory",              // 背包类，走 HardCodedEvaluator，不走这里
                "item_held", "block_placed", "dimension_entered", "time_elapsed", "chat_said",
                "biome", "weather", "entity_seen", "recipe_known", "damage_taken",
                "Advancement", "BASE", "")) {
            assertFalse(WorldFactConditions.knownType(notKnown),
                    "'" + notKnown + "' 不在合法清单里。knownType 决定监测台的 evidenceSource 分流，"
                            + "多认一个 = 证据来源被错标（伪造成服务端世界事实）");
        }
        assertFalse(WorldFactConditions.knownType(null));
        assertFalse(WorldFactConditions.knownType(42));
    }

    @Test
    void unknownTypeIsRejectedLoudlyInSchema() {
        // 「合法但没实现」与「拼错了」在 schema 层都只能表现为 false —— 这正是危险之处：
        // parseOne 拿到 false 会静默丢弃整条二级。本类至少把「必须为 false」钉死，
        // 让想加新 type 的人一定会撞到这里。
        assertFalse(WorldFactConditions.valid(c("type", "biome", "biome", "minecraft:plains")),
                "未知 type 必须判非法（加了新 type 请同步本类 + RddWorldFacts.matches + RddDecomposer 提示词，三处缺一不可）");
        assertFalse(WorldFactConditions.valid(c("type", "item_held", "asset_key", "minecraft:stone")));
        assertFalse(WorldFactConditions.valid(c("type", "chat_said")));
    }

    @Test
    void missingOrNonStringTypeIsRejected() {
        assertFalse(WorldFactConditions.valid(null));
        assertFalse(WorldFactConditions.valid(Map.of()), "空条件不是世界事实");
        assertFalse(WorldFactConditions.valid(c("type", 7)), "type 必须是字符串");
        assertFalse(WorldFactConditions.valid(c("type", List.of("base"))), "type 必须是字符串而不是集合");
    }

    // ── ② 背包类条件不属于世界事实 ────────────────────────────────

    @Test
    void assetKeyOrGroupMakesItNotAWorldFact() {
        assertFalse(WorldFactConditions.valid(c("type", "base", "asset_key", "minecraft:chest")),
                "★ 带 asset_key 的条件走背包判定器，不许同时被当世界事实 —— 两个真身打架 = 判据语义漂移");
        assertFalse(WorldFactConditions.valid(c("type", "base", "group", "food")),
                "带 group 的条件走分组计数，不许同时被当世界事实");
        assertFalse(WorldFactConditions.valid(c("type", "inventory", "asset_key", "minecraft:stone", "minimum", 1)));
    }

    // ── ③ 每个 type 各自的必填项 ───────────────────────────────────

    @Test
    void advancementRequiresAValidAdvancementId() {
        assertTrue(WorldFactConditions.valid(c("type", "advancement", "advancement", "minecraft:story/root")));
        assertFalse(WorldFactConditions.valid(c("type", "advancement")),
                "advancement 没有 id = 不知道要查哪个成就 = 永远判不了");
        assertFalse(WorldFactConditions.valid(c("type", "advancement", "advancement", "")));
        assertFalse(WorldFactConditions.valid(c("type", "advancement", "advancement", "story/root")),
                "id 必须是 [命名空间:路径] 形状，裸路径会让查询永远落空");
        assertFalse(WorldFactConditions.valid(c("type", "advancement", "advancement", "Minecraft:Story/Root")),
                "命名空间必须全小写（资源 id 形状校验）");
    }

    @Test
    void structureRequiresAValidStructureId() {
        assertTrue(WorldFactConditions.valid(c("type", "structure", "structure", "minecraft:village_plains")));
        assertFalse(WorldFactConditions.valid(c("type", "structure")));
        assertFalse(WorldFactConditions.valid(c("type", "structure", "structure", "village_plains")));
        assertFalse(WorldFactConditions.valid(c("type", "structure", "structure", "minecraft:")));
    }

    @Test
    void entityKilledRequiresEntityIdAndPositiveWholeMinimum() {
        assertTrue(WorldFactConditions.valid(c("type", "entity_killed", "entity", "minecraft:zombie")));
        assertTrue(WorldFactConditions.valid(c("type", "entity_killed", "entity", "minecraft:zombie", "minimum", 1)));
        assertTrue(WorldFactConditions.valid(c("type", "entity_killed", "entity", "minecraft:zombie", "minimum", 20)));
        assertFalse(WorldFactConditions.valid(c("type", "entity_killed")),
                "entity_killed 没有 entity id = 不知道数什么");
        assertFalse(WorldFactConditions.valid(c("type", "entity_killed", "entity", "minecraft:zombie", "minimum", 0)),
                "★ minimum 必须为正：0 个算「已达成」= 一开始就假完成");
        assertFalse(WorldFactConditions.valid(c("type", "entity_killed", "entity", "minecraft:zombie", "minimum", -1)));
        assertFalse(WorldFactConditions.valid(c("type", "entity_killed", "entity", "minecraft:zombie", "minimum", 1.5)),
                "★ minimum 必须是整数：1.5 个生物不存在，判据永远为假 = 静默卡死");
        assertFalse(WorldFactConditions.valid(c("type", "entity_killed", "entity", "minecraft:zombie", "minimum", "2")),
                "minimum 必须是数字，不能是字符串");
    }

    @Test
    void entityKilledMinimumDefaultsToOne() {
        // 不写 minimum = 默认 1（写 0 会被拒，所以默认值必须是 1 才自洽）
        assertTrue(WorldFactConditions.valid(c("type", "entity_killed", "entity", "minecraft:zombie", "optional", true)),
                "带无关字段不影响合法性；默认 minimum 应为 1");
    }

    @Test
    void baseNeedsNothing() {
        assertTrue(WorldFactConditions.valid(c("type", "base")),
                "base 无额外必填项（真实语义是「重生床双格完整 + 朝向一致 + 旁边站得下人」，见 RddWorldFacts.inspectBase）");
        assertTrue(WorldFactConditions.valid(c("type", "base", "dimension", "minecraft:overworld")));
    }

    // ── ④ dimension 是可选的，但给了就必须合法 ───────────────────────

    @Test
    void dimensionIsOptionalButMustBeWellFormedWhenPresent() {
        assertTrue(WorldFactConditions.valid(c("type", "advancement", "advancement", "minecraft:story/root")));
        assertTrue(WorldFactConditions.valid(c("type", "advancement", "advancement", "minecraft:story/root",
                "dimension", "minecraft:the_nether")));
        assertFalse(WorldFactConditions.valid(c("type", "advancement", "advancement", "minecraft:story/root",
                "dimension", "the_nether")), "dimension 也必须是 [命名空间:路径]");
        assertFalse(WorldFactConditions.valid(c("type", "advancement", "advancement", "minecraft:story/root",
                "dimension", "")), "空 dimension 不得被当成「不限维度」");
        assertFalse(WorldFactConditions.valid(c("type", "advancement", "advancement", "minecraft:story/root",
                "dimension", 5)), "dimension 必须是字符串");
    }

    // ── ⑤ 护栏：knownType 与 valid 必须说同一件事 ───────────────────

    @Test
    void knownTypeAndValidNeverDisagreeOnTheTypeToken() {
        for (String type : PINNED_TYPES) {
            // 造一个「除了 type 之外什么都不给」的裸条件
            boolean known = WorldFactConditions.knownType(type);
            boolean valid = WorldFactConditions.valid(c("type", type));
            if (type.equals("base")) {
                assertTrue(known && valid, "base 既要 known 也要 valid");
            } else {
                assertTrue(known, type + " 必须 knownType");
                assertFalse(valid, type + " 缺必填 id 时 valid 必须为 false（两处对 type 本身看法必须一致）");
            }
        }
    }

    @Test
    void pinnedTypeListMatchesWhatKnownTypeAccepts() {
        // 把「我以为的清单」与「实现实际接受的」做一次对照。
        // 实现内部是一个内联 Set.of(...)，这里通过逐项探测 + 排除常见误加项来锁定。
        Set<String> accepted = new java.util.HashSet<>();
        for (String candidate : List.of("advancement", "structure", "entity_killed", "base",
                "inventory", "asset", "item", "block", "entity", "biome", "dimension", "recipe",
                "effect", "stat", "weather", "time", "damage", "xp", "level")) {
            if (WorldFactConditions.knownType(candidate)) accepted.add(candidate);
        }
        assertEquals(Set.copyOf(PINNED_TYPES), accepted,
                "★ 实现接受的 type 集合与本类钉住的清单不一致。要么有人加了新 type（请同步本类"
                        + "并同时检查 RddWorldFacts.matches 与 RddDecomposer 提示词），要么有人删了旧的。");
    }
}