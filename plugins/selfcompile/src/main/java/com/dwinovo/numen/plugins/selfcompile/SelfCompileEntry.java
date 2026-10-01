package com.dwinovo.numen.plugins.selfcompile;

import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.plugins.selfcompile.generated.RddGetInventoryTool;
import com.dwinovo.numen.plugins.selfcompile.generated.RddWhereamiTool;
import net.neoforged.fml.ModList;

import java.nio.file.Path;

/**
 * Numen 插件入口：只做安装转发。
 *
 * <h2>★ 这个插件现在真正负责什么（2026-10-01 核实）</h2>
 *
 * <p><b>活的只有前两个工具，是「需求登记口」，不是改码通道：</b>
 * <ul>
 *   <li>{@code selfcompile_request} —— 把游戏内 AI 的需求写进一个审计目录，
 *       <b>不生成代码、不执行命令、不编译、不部署</b>（工具描述原话）。
 *       它的实际角色 = <b>工程流 stager 里「进游戏监督 AI」那个小流程的落地面</b>：
 *       游戏里的 AI 用它把「想改什么」落到盘上，外层工程流读取并排期。</li>
 *   <li>{@code selfcompile_status} —— 查状态（idle / controlled）。</li>
 * </ul>
 *
 * <p><b>后两个是本系统自己生成的产物</b>（在 {@code generated/} 下），
 * 每完成一轮变异要手工更新这里的注册行——这就是自编译闭环的字面形态。</p>
 *
 * <p><b>已停用：</b>{@link MutationPipeline} 那条变异流水线（11 态状态机 / 编译 /
 * 验证 / 预算 / Harness 写锁）在生产代码里 <b>0 处引用</b>，从未接线。详见该类注释。
 * <b>不要在这里接线它</b>——真正的改码通道是外层
 * {@code rdd-selfcompile/scripts/run-mutation.ps1}（唯一改码入口），
 * 且它已有一套 MutationId 与门禁，两套并存必然漂移。
 */
public final class SelfCompileEntry implements NumenPlugin {

    @Override
    public void setup(com.dwinovo.numen.api.NumenApi numen) {
        Path root = numen.configDir().resolve("selfcompile").resolve("mutations");
        SelfCompileService service = new SelfCompileService(root);
        numen.registerTool(new SelfCompileStatusTool(service));
        numen.registerTool(new SelfCompileRequestTool(service));
        // Self-Compile 自变异系统生成的工具（每轮变异后更新这里）
        numen.registerTool(new RddWhereamiTool());
        numen.registerTool(new RddGetInventoryTool());

        Path skills = ModList.get().getModFileById(SelfCompileMod.MOD_ID)
                .getFile().findResource("skills");
        if (skills != null) numen.bundleSkills(skills);

        numen.onClient(() -> {
            // 预留：后续挂自变异 UI / 状态面板。
        });
        numen.contributeState(uuid -> "<selfcompile><state>controlled</state></selfcompile>");
    }
}
