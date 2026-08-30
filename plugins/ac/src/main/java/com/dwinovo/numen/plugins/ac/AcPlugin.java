package com.dwinovo.numen.plugins.ac;

import com.dwinovo.numen.ac.api.AcTool;
import com.dwinovo.numen.ac.api.ToolSchema;
import com.dwinovo.numen.ac.core.AcAuthoringService;
import com.dwinovo.numen.ac.core.AcExecutor;
import com.dwinovo.numen.ac.core.DefaultToolRegistry;
import com.dwinovo.numen.ac.core.InMemoryAcVersionStore;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.plugins.ac.bridge.NumenHostAdapter;
import com.dwinovo.numen.plugins.ac.bridge.NumenSchemaAdapter;
import com.dwinovo.numen.plugins.ac.bridge.NumenToolBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AC 插件的 NUMEN 宿主适配器。把 Numen 工具目录桥接成 AC 原子工具并注册，
 * 暴露 {@code ac_execute / ac_status / ac_resume} 三个门面工具给主 AI。
 *
 * <p>AC core（ac-api/ac-core）保持纯 JVM、不依赖 NUMEN；本插件是它唯一的宿主面。
 * 门面工具（ac_*）不注册为 AC 步骤工具，避免递归。
 */
public final class AcPlugin implements NumenPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(AcPlugin.class);

    private final DefaultToolRegistry registry = new DefaultToolRegistry();
    private final AcSessions sessions = new AcSessions();
    private AcExecutor executor;
    private AcAuthoringService authoring;

    @Override
    public void setup(NumenApi numen) {
        int bridged = 0;
        int skipped = 0;
        for (NumenTool tool : ToolRegistry.all()) {
            String name = tool.name();
            if (name == null || name.isBlank()) {
                skipped++;
                continue;
            }
            if (name.startsWith("ac_")) {           // 门面工具不注册为 AC 步骤
                skipped++;
                continue;
            }
            try {
                ToolSchema schema = NumenSchemaAdapter.from(tool);
                AcTool bridge = new NumenToolBridge(new NumenHostAdapter(tool));
                registry.register(name, bridge, schema);
                bridged++;
            } catch (RuntimeException e) {
                skipped++;
                LOG.warn("[ac] 跳过工具 {}: {}", name, e.getMessage());
            }
        }
        executor = new AcExecutor(registry);
        authoring = new AcAuthoringService(registry, new InMemoryAcVersionStore());

        numen.registerTool(new AcExecuteTool(executor, authoring, sessions));
        numen.registerTool(new AcStatusTool(sessions));
        numen.registerTool(new AcResumeTool(executor, sessions));

        LOG.info("[ac] 桥接 {} 个 Numen 工具为 AC 步骤工具, 跳过 {} 个", bridged, skipped);
    }

    /** 供调试/外部查询 AC 工具目录。 */
    public java.util.List<String> acToolNames() {
        return registry.toolNames();
    }
}
