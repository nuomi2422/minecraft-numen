package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规划策略（最小有效目标 + 成本/风险约束）测试。
 *
 * <p>策略是纯逻辑，这里既断言策略本身，也断言<b>最终请求正文</b>确实带上了这些约束
 * （用真实的 prompt 构造器拼接，跟生产同一条代码路径），并断言模型违反硬规则时被安全拦下。
 */
class RddPlanningPolicyTest {

    private static final UUID COMPANION = UUID.fromString("c29c5c40-0632-4982-9ab7-d99528d58e17");

    private static boolean requested(String objective) {
        return RddPlanningPolicy.optionalAllowed(objective);
    }

    // ---------- 1. 通关/普通推进不默认上昂贵配置 ----------

    @Test
    void 通关目标不默认放行可选升级() {
        assertFalse(requested("通关MC"));
        assertFalse(requested("推进到铁器时代"));
        assertFalse(requested(""));

        List<String> deferred = RddPlanningPolicy.deferredOptional("通关MC");
        assertTrue(deferred.stream().anyMatch(s -> s.contains("书架")), "书架必须默认延后");
        assertTrue(deferred.stream().anyMatch(s -> s.contains("满级")), "满级附魔台必须默认延后");
        // 2026-11-11（主人否决旧的「裸词=大工程」判定）：村民/基本交易/装备准备不再算可选大工程，
        //   否则村庄供食、安家、交易阶段会被误删。断言改为「不再延后」+ 策略块说明句仍在。
        assertFalse(deferred.stream().anyMatch(s -> s.contains("村民")), "村民交易体系不再默认延后（2026-11-11 变更）");
        assertTrue(RddPlanningPolicy.block("通关MC", "stage_a").contains("不因提到村民"),
                "策略块必须说明「不因提到村民或全套装备就当可选大工程」");
        assertTrue(deferred.stream().anyMatch(s -> s.contains("自动化")), "自动化农场必须默认延后");
        assertTrue(deferred.stream().anyMatch(s -> s.contains("生电")), "生电大工程必须默认延后");
    }

    @Test
    void 策略块在未要求时写明延后而不是默默省略() {
        String block = RddPlanningPolicy.block("通关MC", "stage_a");

        assertTrue(block.contains("最小有效目标"));
        assertTrue(block.contains("书架"), "要显式写出延后了什么，才可审计");
        assertTrue(block.contains("生电大工程"), "生电大工程要显式列为门槛项（主人路线点到才做）");
        assertTrue(block.contains("复用现有资产"));
        assertTrue(block.contains("每步可验收"));
        assertFalse(block.contains("已明确要求，允许纳入"), "没要求就不该出现放行语句");
    }

    // ---------- 3. 明确要求时放行 ----------

    @Test
    void 主人明确要求时才放行可选升级() {
        assertTrue(requested("帮我做满级附魔台和15个书架"));
        assertTrue(requested("建一个村民交易所"));
        assertTrue(requested("搞生电"));

        assertTrue(RddPlanningPolicy.deferredOptional("帮我做满级附魔台").isEmpty(), "已要求就不再列为延后");
        String block = RddPlanningPolicy.block("帮我做满级附魔台和书架", "stage_a");
        assertTrue(block.contains("已明确要求，允许纳入"));
    }

    // ---------- 4. 高风险阶段带准备/分诊/回退 ----------

    @Test
    void 高风险目标带分诊与回退约束() {
        String nether = RddPlanningPolicy.block("下界要塞收集烈焰棒", "stage_a");
        assertTrue(nether.contains("下界"), "要标出本次适用的风险场景");
        assertTrue(nether.contains("分诊"), "必须要求失败分诊");
        assertTrue(nether.contains("资源不存在或耗尽"), "分诊四类要写全");
        assertTrue(nether.contains("路径不可达"));
        assertTrue(nether.contains("稳定软件缺陷"));
        assertTrue(nether.contains("未知原因保持未知"), "禁止失败即归因缺工具");
        assertTrue(nether.contains("水桶") && nether.contains("床"), "下界水桶/床的禁令必须在场");

        String deepMine = RddPlanningPolicy.block("挖钻石到 y=-58", "stage_b");
        assertTrue(deepMine.contains("深矿下探"), "深矿要识别为高风险");

        String end = RddPlanningPolicy.block("击败末影龙", "stage_a");
        assertTrue(end.contains("末地"));
    }

    @Test
    void 普通目标也要写出通用风险约束() {
        String block = RddPlanningPolicy.block("采集木头", "stage_b");

        assertTrue(block.contains("高风险步骤必须写明准备与失败回退"));
        assertTrue(block.contains("禁止由失败直接推出"), "反自编译跳跃的约束任何阶段都在场");
    }

    // ---------- 5. 最终实际请求含策略约束（不是只测 helper） ----------

    @Test
    void 一级二级上限从已接受目标提取并限制在解析硬门内() {
        assertEquals(4, RddPlanningPolicy.maxSubtasksPerPrimary(
                "规划必须拆成多个一级目标，每个一级最多4个二级步骤；后续阶段按前置完成后再展开。"));
        assertEquals(4, RddPlanningPolicy.maxSubtasksPerPrimary("每个一级目标至多 4 个二级子步骤"));
        assertEquals(RddPlanningPolicy.MAX_SUBTASKS_PER_PRIMARY,
                RddPlanningPolicy.maxSubtasksPerPrimary("通关MC"));
        assertEquals(RddPlanningPolicy.MAX_SUBTASKS_PER_PRIMARY,
                RddPlanningPolicy.maxSubtasksPerPrimary(null));
        assertEquals(1, RddPlanningPolicy.maxSubtasksPerPrimary("每个一级最多0个二级步骤"));
        assertEquals(RddPlanningPolicy.MAX_SUBTASKS_PER_PRIMARY,
                RddPlanningPolicy.maxSubtasksPerPrimary("每个一级最多99个二级步骤"));
        assertEquals(RddPlanningPolicy.MAX_SUBTASKS_PER_PRIMARY,
                RddPlanningPolicy.maxSubtasksPerPrimary("每个一级最多999999999999999999999个二级步骤"));
    }

    @Test
    void 显式一级上限进入策略块与观测规则() {
        String objective = "每个一级最多4个二级步骤；不得把完整长链塞进一个一级";
        String block = RddPlanningPolicy.block(objective, "stage_a");
        assertTrue(block.contains("每个一级最多 4 个二级步骤"));
        assertTrue(block.contains("超限整批会被拒绝"));
        assertTrue(RddPlanningPolicy.appliedRules(objective, "stage_a")
                .contains("primary-subtask-limit:4"));
        assertFalse(RddPlanningPolicy.block("通关MC", "stage_a").contains("阶段拆分上限"));
    }

    @Test
    void 一级0只能一个二级的硬约束进入策略与观测() {
        String objective = "一级0只能且必须生成1个二级步骤；不得生成火把或工具前置";
        assertEquals(1, RddPlanningPolicy.firstPrimarySubtaskCount(objective));
        assertEquals(0, RddPlanningPolicy.firstPrimarySubtaskCount("每个一级最多4个二级步骤"));
        assertTrue(RddPlanningPolicy.block(objective, "stage_a").contains("恰好 1 个二级步骤"));
        assertTrue(RddPlanningPolicy.appliedRules(objective, "stage_a")
                .contains("first-primary-subtask-count:1"));
    }

    @Test
    void 一级0数量硬门识别实机常用的必须恰好只有措辞() {
        String objective = "一级0必须恰好只有1个二级：就地确认村庄结构并检查床、箱子和食物";
        assertEquals(1, RddPlanningPolicy.firstPrimarySubtaskCount(objective));
        assertTrue(RddPlanningPolicy.block(objective, "stage_b").contains("恰好 1 个二级步骤"));
        assertEquals(1, RddPlanningPolicy.firstPrimarySubtaskCount("一级 0 只能有 1 个二级步骤"));
        assertEquals(1, RddPlanningPolicy.firstPrimarySubtaskCount("一级0必须只有1个二级任务"));
    }

    @Test
    void 显式早期路线锚点进入提示与确定性范围校验() {
        String objective = "路线硬约束：一级0至一级2只允许坐标20784,9904周围96格；不得引用范围外坐标或历史基地。";
        var route = RddPlanningPolicy.routeConstraint(objective);
        assertNotNull(route);
        assertEquals(2, route.lastPrimaryIndex());
        assertEquals(20784, route.x());
        assertEquals(9904, route.z());
        assertEquals(96, route.radius());
        assertTrue(RddPlanningPolicy.block(objective, "stage_b").contains("早期路线硬约束"));
        assertTrue(RddPlanningPolicy.appliedRules(objective, "stage_b")
                .contains("early-route-bound:0-2:20784,9904:96"));

        var nearby = new com.dwinovo.numen.rdd.api.SubtaskSpec("检查村庄箱子",
                java.util.Map.of("type", "container_nearby", "radius", 8), null);
        var stale = new com.dwinovo.numen.rdd.api.SubtaskSpec("返回旧基地(10728,64,9500)",
                java.util.Map.of("type", "container_nearby", "radius", 8), null);
        var negated = new com.dwinovo.numen.rdd.api.SubtaskSpec("在当前村庄核对，不引用旧基地",
                java.util.Map.of("type", "container_nearby", "radius", 8), null);
        assertTrue(RddPlanningPolicy.routeAllowed(List.of(nearby), objective, 1));
        assertTrue(RddPlanningPolicy.routeAllowed(List.of(negated), objective, 1),
                "a prohibition on stale bases is not a stale-base action");
        assertFalse(RddPlanningPolicy.routeAllowed(List.of(stale), objective, 1));
        assertTrue(RddPlanningPolicy.routeAllowed(List.of(stale), objective, 6));
    }

    @Test
    void stage_a最终请求含策略约束() {
        String base = RddStagePlanner.planningPrompt("通关MC", Map.of("minecraft:iron_pickaxe", 1));

        String request = RddPlanningKnowledge.withKnowledge(null, COMPANION,
                RddPlanningKnowledge.attach(base, RddPlanningPolicy.block("通关MC", "stage_a")),
                "通关MC", "stage_a", List.of());

        assertTrue(request.contains(base), "原规划提示保留");
        assertTrue(request.contains("最小有效目标优先"), "策略约束必须真的进了请求");
        assertTrue(request.contains("可选升级默认延后"));
        assertTrue(request.contains("主人路线点到才做"), "大工程门槛必须真的进了请求");
    }

    @Test
    void stage_b最终请求含策略约束() {
        String base = RddDecomposer.decompositionPrompt("挖钻石", Map.of(), List.of());

        String request = RddPlanningKnowledge.withKnowledge(null, COMPANION,
                RddPlanningKnowledge.attach(base, RddPlanningPolicy.block("挖钻石", "stage_b")),
                "挖钻石", "stage_b", List.of());

        assertTrue(request.contains(base));
        assertTrue(request.contains("最小有效目标优先"));
        assertTrue(request.contains("深矿下探"), "深矿阶段要带上对应风险约束");
    }

    @Test
    void 策略约束与经验知识可以同时进请求_且知识缺失不影响策略() {
        String base = RddStagePlanner.planningPrompt("通关MC", Map.of());
        String withPolicy = RddPlanningKnowledge.attach(base, RddPlanningPolicy.block("通关MC", "stage_a"));

        // 无经验（知识源为空）时，策略约束必须原样保留
        String request = RddPlanningKnowledge.withKnowledge((c, o, s, f) -> "",
                COMPANION, withPolicy, "通关MC", "stage_a", List.of());

        assertEquals(withPolicy, request, "知识缺失不得破坏策略约束");
        assertTrue(request.contains("最小有效目标优先"));
    }

    @Test
    void 观测摘要只记决策结果不含推理() {
        List<String> rules = RddPlanningPolicy.appliedRules("挖钻石", "stage_b");

        assertTrue(rules.contains("minimal-effective-goal"));
        assertTrue(rules.contains("optional-deferred"));
        assertTrue(rules.contains("reuse-held-assets"));
        assertTrue(rules.contains("verifiable-conditions"));
        assertTrue(rules.stream().anyMatch(r -> r.startsWith("risk:")), "高风险要作为规则被记录");
        assertTrue(rules.contains("side-branch-cost-gate"));
    }
}
