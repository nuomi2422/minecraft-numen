package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code learner_fact_shadow}：共同事实 vs 使用账本的<b>只读对账</b>（第三批 N1）。
 *
 * <p><b>为什么能同时看见两侧</b>：事实文件在 {@code config/numen/rdd-facts/<uuid>.json}，
 * 账本在 {@code config/numen/usage-ledger.jsonl} —— 两个都是<b>共享 configDir 下的普通文件</b>，
 * 所以只靠路径就能读，不需要跨插件 import（那条路被 {@code numen-plugin.gradle} 封死了）。
 * 对账逻辑本身在 rdd-core（{@code FactShadowReconciler}）。
 *
 * <p><b>★ 只读，绝不修</b>。这是 shadow 的定义：先量出差异形态，再决定改哪边。
 * 现在就自动「修正」共同事实，等于用一份还没验证的猜测去覆盖生产状态。
 *
 * <p><b>★ 依赖缺失要显式降级</b>：对账器在 rdd-core 且只 compileOnly 进来，
 * 运行时由 rdd 插件提供。rdd 不在 ⇒ 这里会 {@code NoClassDefFoundError}，
 * 所以整段包在 catch 里<b>回一句人话</b>，而不是让工具崩掉或假装「一致」。
 */
final class LearnerFactShadowTool implements NumenTool {

    /** 对账器类名（rdd-core 提供）。抽成常量是为了让"缺席降级"能被单测直接构造。 */
    static final String RECONCILER_CLASS = "com.dwinovo.numen.rdd.fact.FactShadowReconciler";

    /** rdd 插件没加载：**不是** "没有差异"，是 "没法对账"。 */
    static final class MissingReconciler extends Exception {
        MissingReconciler(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 反射调用对账器（037/T16 抽出的接缝：让"rdd 缺席"这条降级路径可测）。
     *
     * @throws MissingReconciler 类不在（rdd 插件未加载）
     * @throws ReflectiveOperationException 类在但签名不对/调用失败
     */
    static Object reconcile(Path facts, Path ledger, String className)
            throws MissingReconciler, ReflectiveOperationException {
        Class<?> c;
        try {
            c = Class.forName(className);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            throw new MissingReconciler("reconciler absent: " + className, e);
        }
        java.lang.reflect.Method m = c.getMethod("reconcile", Path.class, Path.class);
        try {
            return m.invoke(null, facts, ledger);
        } catch (LinkageError e) {
            // 版本不兼容：类在、方法签名却对不上（NoSuchMethodError/IncompatibleClassChangeError）。
            // 这同样是"没法对账"，不能让它冒出去把工具调用打成崩。
            throw new MissingReconciler("reconciler incompatible: " + className + " (" + e + ")", e);
        }
    }

    @Override
    public String name() {
        return "learner_fact_shadow";
    }

    @Override
    public String description() {
        return "共同事实 vs 使用账本的只读对账：系统自称完成的阶段，和产物真的被用了吗，两边对不上就列出来。"
                + "★ 只读，绝不自动改任何一侧 —— 先看差异形态，再决定该修哪边。"
                + "★ 读不到任何一侧时报告不可信（「读不到 ≠ 一致」）。";
    }

    @Override
    public NumenTool.Residency residency() {
        return NumenTool.Residency.DEFERRED;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object().build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Path configDir = LearnerPlugin.configDir();
            if (configDir == null) {
                reply.accept(TaskResult.fail("configDir 未初始化（插件 setup 没跑完？）").toJson());
                return;
            }
            java.util.UUID id = companion.getUUID();
            Path facts = configDir.resolve("rdd-facts").resolve(id + ".json");
            Path ledger = configDir.resolve("usage-ledger.jsonl");

            // ★ 反射调用：rdd-core 是 compileOnly 依赖，编译期可见但运行时可能不在。
            //   直接 import 的话 rdd 插件缺席就是 NoClassDefFoundError（今天刚踩过这个坑）。
            Object report;
            try {
                report = reconcile(facts, ledger, RECONCILER_CLASS);
            } catch (MissingReconciler e) {
                // 显式降级：说清缺什么、为什么不能对账，不崩也不假装
                reply.accept(TaskResult.fail("对账器不可用：rdd 插件未加载（共同事实对账依赖 rdd-core）。"
                        + "这不是「没有差异」，而是「没法对账」。").toJson());
                return;
            } catch (ReflectiveOperationException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                reply.accept(TaskResult.fail("对账失败: " + cause).toJson());
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data;
            try {
                // toMap() 同样是反射来的（Report 是 record），异常要显式接住
                data = (Map<String, Object>) report.getClass().getMethod("toMap").invoke(report);
            } catch (ReflectiveOperationException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                reply.accept(TaskResult.fail("对账结果取不出来: " + cause).toJson());
                return;
            }
            Map<String, Object> out = new LinkedHashMap<>(data);
            out.put("facts_file", facts.toString());
            out.put("usage_ledger", ledger.toString());
            boolean ok = Boolean.TRUE.equals(data.get("trustworthy"));
            reply.accept(TaskResult.ok(ok ? "fact shadow report（只读，未改任何一侧）"
                    : "★ fact shadow 报告不可信（有一侧读不到）", out).toJson());
        } catch (RuntimeException e) {
            reply.accept(TaskResult.fail("learner_fact_shadow 失败: " + e.getMessage()).toJson());
        }
    }
}