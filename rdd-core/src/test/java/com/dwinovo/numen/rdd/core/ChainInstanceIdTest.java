package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T24 · chainInstanceId（75 号 §15.2「每次真正创建新链分配 UUID 并持久化」的最小落地）。
 *
 * <h2>为什么是它、为什么是现在</h2>
 *
 * <p>模块文档 §6 未完成表里有一条「chainInstanceId / planVersion：每次建链分配 UUID 并持久化；
 * 现在 goalId 不区分同伙伴多次提交」。goalId 是**内容**身份（同一段目标文本 → 同一个 id），
 * 它回答不了「这是第几次提交」；而恢复支线的恢复令牌、协商回执的消费者校验、跨轮对账，
 * 都需要「这一条链」的稳定身份 —— 同伴重提同一目标必须得到<b>不同</b>的链实例 id。
 *
 * <h2>刻意做小的三件事（本批不做的写明）</h2>
 * <ol>
 *   <li><b>只在构造时分配一次</b>，随 toJson/fromJson 活过重启（75 号 §15.8 迁移规则：
 *       旧存档首次补字段，读作 null 并当场分配——不伪造历史）；</li>
 *   <li><b>只加读口</b>（{@code chainInstanceId()}、snapshot 带上它），消费侧（协商/恢复令牌/对账）
 *       后续批接 —— 本批不碰任何既有调用点，零行为变化；</li>
 *   <li><b>planVersion 不做</b>：§15.2 的 planVersion 是「计划结构提交版本」，语义上要跟
 *       PlanPatch 的提交点绑定，那是 G3 组的事，不是这个最小批能捎带的。</li>
 * </ol>
 *
 * <p>纯 JVM，零 Minecraft。
 */
class ChainInstanceIdTest {

    private static Goal oneStage(String goalId) {
        return new Goal(goalId, "测试目标 " + goalId, List.of(
                new PrimaryGoal("primary-" + goalId, "阶段一", List.of(
                        Subtask.hardCoded("subtask-" + goalId, "做一步",
                                Map.of("asset_key", "minecraft:stone", "minimum", 1))))));
    }

    // ── ① 分配即唯一；两次构造同 goal 也不同 ─────────────────────────

    @Test
    void 分配即非空且同伴重提同目标得到不同实例id() {
        TaskChain a = new TaskChain(oneStage("goal-x"));
        TaskChain b = new TaskChain(oneStage("goal-x"));   // 同 goalId、同内容

        assertNotNull(a.chainInstanceId(), "链实例 id 必须非空");
        assertNotNull(b.chainInstanceId());
        assertNotEquals(a.chainInstanceId(), b.chainInstanceId(),
                "goalId 是内容身份；同伴重提同一目标必须是两条不同的链（IC02 的一半）");
    }

    @Test
    void 同一链对象反复读是同一个id() {
        TaskChain chain = new TaskChain(oneStage("goal-y"));
        String first = chain.chainInstanceId();
        assertEquals(first, chain.chainInstanceId(), "读口必须幂等——它不是随机数生成器");
    }

    // ── ② 快照可见（监测台对账的读数口）──────────────────────────────

    @Test
    void 快照带链实例id() {
        TaskChain chain = new TaskChain(oneStage("goal-z"));
        Object view = chain.snapshot().get("chainInstanceId");
        assertEquals(chain.chainInstanceId(), view,
                "snapshot 是监测台的唯一观测面；链实例 id 必须出现在那里");
    }

    // ── ③ 活得过重启（toJson → fromJson 往返保持）────────────────────

    @Test
    void 重启往返保持同一实例id() {
        TaskChain chain = new TaskChain(oneStage("goal-rt"));
        chain.startCurrent();
        String restored = TaskChain.fromJson(chain.toJson()).chainInstanceId();
        assertEquals(chain.chainInstanceId(), restored,
                "链实例 id 随链持久化；重启后必须还是同一条链（不是新链）");
    }

    @Test
    void 旧存档无该字段读作null并当场分配_不伪造历史() {
        // 手工去掉新字段，模拟 75 号 §15.8 说的「旧存档首次补字段」
        TaskChain chain = new TaskChain(oneStage("goal-old"));
        String json = chain.toJson();
        String legacy = json.replaceAll("\"chainInstanceId\":\"[^\"]*\",?", "");
        // 兼容尾逗号
        legacy = legacy.replace(",}", "}").replace(",]", "]");

        TaskChain restored = TaskChain.fromJson(legacy);
        assertNotNull(restored.chainInstanceId(),
                "旧链读盘当场分配新实例 id（往后稳定）；读作 null 且不再分配才是坏方向");
    }

    // ── ④ 并发构造不撞号（UUID 的兜底验证，也防将来有人改成计数器）──────

    @Test
    void 并发构造不产生重复实例id() throws InterruptedException {
        int n = 64;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        var ids = java.util.concurrent.ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(n);
        try {
            for (int i = 0; i < n; i++) {
                pool.execute(() -> {
                    try {
                        ids.add(new TaskChain(oneStage("goal-cc")).chainInstanceId());
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(n, ids.size(), "并发建链必须无撞号（将来若有人把 UUID 换成计数器，这条会红）");
    }
}
