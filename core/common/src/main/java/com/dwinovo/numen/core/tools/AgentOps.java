package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.skill.SkillInfo;
import com.dwinovo.numen.agent.skill.SkillInjection;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.LearnedSkillBodyResolver;
import com.dwinovo.numen.api.NumenPlugins;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Agent-side (client-local) tool implementations — the business half of
 * {@code LoadSkillTool} and {@code TodoWriteTool}. These run on the agent thread
 * with no server body and return their result directly.
 */
public final class AgentOps {
    private final java.util.function.Supplier<LearnedSkillBodyResolver> learnedSkills;

    public AgentOps() {
        this(() -> NumenPlugins.capability(LearnedSkillBodyResolver.class));
    }

    /** A reader lookup seam; production always defaults to the original capability port. */
    public AgentOps(java.util.function.Supplier<LearnedSkillBodyResolver> learnedSkills) {
        this.learnedSkills = java.util.Objects.requireNonNull(learnedSkills);
    }

    public String loadSkill(String name, String file) {
        SkillRegistry registry = SkillRegistry.instance();
        if (file != null && !file.isBlank()) {
            // 三级披露:正文引用的附属文件按需拉取
            try {
                String text = registry.readSupportFile(name, file);
                return SkillInjection.supportFile(name, file, text);
            } catch (IllegalArgumentException ex) {
                return "{\"success\":false,\"error\":\"" + escapeJson(ex.getMessage()) + "\"}";
            }
        }
        var maybe = registry.get(name);
        if (maybe.isEmpty()) {
            String available = registry.all().stream()
                    .map(SkillInfo::name)
                    .map(AgentOps::quote)
                    .collect(Collectors.joining(","));
            return "{\"success\":false,\"error\":\"unknown skill: " + escapeJson(name)
                    + "\",\"available\":[" + available + "]}";
        }

        // 成型交给 SkillInjection:主人打斜杠命令走的是另一条路,进上下文的东西必须一样。
        return SkillInjection.body(maybe.get(), null);
    }

    /** Explicit learned-reference branch; the legacy two-argument loader stays unchanged. */
    public String loadSkill(UUID companion, String name, String file, String exactRef) {
        if (name != null || file != null)
            throw new IllegalArgumentException("ref cannot be combined with name or file");
        LearnedSkillBodyResolver.Reference.parse(exactRef);
        if (companion == null) throw new IllegalArgumentException("learned skill context missing");
        var resolver = learnedSkills.get();
        if (resolver == null) throw new IllegalStateException("learned-skill-resolver-unavailable");
        var result = resolver.resolve(companion, exactRef, LearnedSkillBodyResolver.View.EXECUTOR);
        if (result == null || !exactRef.equals(result.exactRef()))
            throw new IllegalStateException("learned skill resolver identity mismatch");
        if (!result.available())
            throw new IllegalArgumentException("learned skill unavailable: " + result.gap());
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("ref", exactRef);
        response.addProperty("body", result.body());
        return response.toString();
    }

    /** Explicitly inspect one package member, never mount, adopt or execute it. */
    public String loadPackageComponent(UUID companion,String name,String file,String packageRef,String componentRef) {
        if(name!=null || file!=null) throw new IllegalArgumentException("package component cannot be combined with name or file");
        LearnedSkillBodyResolver.Reference.parse(packageRef);
        LearnedSkillBodyResolver.Reference.parse(componentRef);
        if(companion==null) throw new IllegalArgumentException("learned skill context missing");
        var resolver=learnedSkills.get();
        if(resolver==null) throw new IllegalStateException("learned-skill-resolver-unavailable");
        var result=resolver.resolveComponent(companion,packageRef,componentRef,LearnedSkillBodyResolver.View.EXECUTOR);
        if(result==null || !packageRef.equals(result.packageRef()) || !componentRef.equals(result.componentRef()))
            throw new IllegalStateException("learned skill resolver identity mismatch");
        if(!result.available()) throw new IllegalArgumentException("learned skill unavailable: "+result.gap());
        JsonObject response=new JsonObject(); response.addProperty("success",true); response.addProperty("ref",componentRef);
        response.addProperty("package_ref",packageRef); response.addProperty("body",result.body());
        response.addProperty("availability",result.availability()); return response.toString();
    }

    private static String quote(String s) {
        return "\"" + escapeJson(s) + "\"";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    private static final Set<String> ALLOWED_STATUSES =
            Set.of("pending", "in_progress", "completed", "cancelled");
    private static final Set<String> ALLOWED_PRIORITIES =
            Set.of("high", "medium", "low");

    /** One todo entry — its @Arg components become the array item's object schema. */
    public record Todo(
String content,
String status,
String priority) {}

    public String todowrite(
List<Todo> todos) {
        if (todos == null) {
            throw new IllegalArgumentException("todos is required");
        }
        int inProgressCount = 0;
        boolean workRemains = false;
        // Echo back the canonical JSON; the model reads it next turn as its plan.
        JsonArray echo = new JsonArray();
        for (int i = 0; i < todos.size(); i++) {
            Todo t = todos.get(i);
            if (t == null || t.content() == null || t.content().isBlank()) {
                throw new IllegalArgumentException("todos[" + i + "].content must not be blank");
            }
            if (!ALLOWED_STATUSES.contains(t.status())) {
                throw new IllegalArgumentException(
                        "todos[" + i + "].status must be one of " + ALLOWED_STATUSES + ", got: " + t.status());
            }
            if (!ALLOWED_PRIORITIES.contains(t.priority())) {
                throw new IllegalArgumentException(
                        "todos[" + i + "].priority must be one of " + ALLOWED_PRIORITIES + ", got: " + t.priority());
            }
            if ("in_progress".equals(t.status())) inProgressCount++;
            if ("in_progress".equals(t.status()) || "pending".equals(t.status())) workRemains = true;
            JsonObject o = new JsonObject();
            o.addProperty("content", t.content());
            o.addProperty("status", t.status());
            o.addProperty("priority", t.priority());
            echo.add(o);
        }
        String echoed = echo.toString();
        if (workRemains && inProgressCount != 1) {
            return "{\"success\":false,\"message\":\"unfinished work requires exactly one todo in_progress; got "
                    + inProgressCount + "\",\"todos\":" + echoed + "}";
        }
        return "{\"success\":true,\"message\":\"plan snapshot replaced; continue only the in_progress item\",\"todos\":"
                + echoed + "}";
    }
}
