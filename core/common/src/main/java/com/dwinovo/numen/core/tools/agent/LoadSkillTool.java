package com.dwinovo.numen.core.tools.agent;
import com.dwinovo.numen.core.tools.AgentOps;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;

import java.util.Map;

/** Client-local tool (raw NumenTool): loads a skill's instructions into the conversation. */
public final class LoadSkillTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final AgentOps impl = new AgentOps();

    private record Args(String name, String file, String ref) {}

    @Override
    public String name() {
        return "load_skill";
    }

    /** 常驻:技能入口,它本身就是渐进披露的另一半。 */
    @Override
    public Residency residency() {
        return Residency.RESIDENT;
    }

    @Override
    public String description() {
        return """
                Load a specialized skill when the task at hand matches one of the skills listed in the system prompt.

                Use this tool to inject the skill's instructions and resources into the current conversation. The output contains detailed workflow guidance for the task.

                For directory skills, the name must match one of the skills listed in your system prompt's <available_skills> block.

                A skill body may reference supporting files by relative path (e.g. references/roofs.md). Call this tool again with the same name plus `file` to read one — only when the body points you there.

                To read an explicitly referenced learned component, pass only `ref` as skill:id@vN. This returns that exact eligible body, without adopting, executing, or recursively loading its references.""";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("name", "Directory skill name; required unless ref is supplied.")
                .optionalString("file", "Optional relative supporting file, with name; mutually exclusive with ref.")
                .optionalString("ref", "Exact learned component skill:id@vN; use without name or file.")
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        try {
            var args = call.args();
            Args a = GSON.fromJson(args, Args.class);
            if (args.has("ref")) {
                call.complete(impl.loadSkill(call.ctx() == null ? null : call.ctx().entityUuid(),
                        a.name(), a.file(), a.ref()));
            } else {
                if (a.name() == null || a.name().isBlank())
                    throw new IllegalArgumentException("name or exact ref is required");
                call.complete(impl.loadSkill(a.name(), a.file()));
            }
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail(ex.getMessage()).toJson());
        }
    }
}
