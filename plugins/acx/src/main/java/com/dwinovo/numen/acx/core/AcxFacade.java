package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxRunRecord;
import com.dwinovo.numen.acx.api.AcxStatus;
import com.dwinovo.numen.acx.api.AcxToolRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 宿主门面的「业务层」：Map→Map 协议，宿主插件只需包一层 NumenTool 转发。
 *
 * <p>这样切的原因：门面的参数解析 / 状态口径 / 拒绝语义全在游戏外可测（本类），
 * 插件侧只剩「注册工具名 + 把 NumenTool 参数搬进 Map + 把回包原样吐回」这类薄壳，
 * 迁入时要读的代码面小得多。</p>
 *
 * <p>信封形状对齐 Numen 的 {@code TaskResult}：
 * {@code {"success":bool, "message":str, "data":{...}}}。查询类（status/library）
 * 的 {@code success} 只表示「查询成功」，AC 自身的终态在 {@code data.status}。</p>
 *
 * <p>门面清单（工具名由宿主插件注册，建议 {@code acx_} 前缀）：</p>
 * <ul>
 *   <li>{@code acx_execute} → {@link #execute execute}</li>
 *   <li>{@code acx_status} → {@link #status status}</li>
 *   <li>{@code acx_resume} → {@link #resume resume}</li>
 *   <li>{@code acx_cancel} → {@link #cancel cancel}</li>
 *   <li>{@code acx_publish} / {@code acx_approve} / {@code acx_rollback} / {@code acx_library}（要挂 {@link FileAcxLibrary}）</li>
 * </ul>
 */
public final class AcxFacade {

    private final AcxSessionManager sessions;
    private final Function<String, AcxDefinition> lookupActive;
    private final BiFunction<String, String, AcxDefinition> lookupVersion;
    private final AcxToolRegistry blocks;
    private final FileAcxLibrary library;

    /**
     * @param sessions     会话层（必填）
     * @param lookupActive 按名字取「当前生效版本」；返回 null 视为未找到（必填）
     * @param lookupVersion 按 名字+版本 取定义；可为 null（此时带 ac_version 的请求会被拒）
     * @param blocks       积木注册表，用于解析内联 ac_json 的校验（必填）
     * @param library      版本库；可为 null（publish/approve/rollback/library 会回拒）
     */
    public AcxFacade(AcxSessionManager sessions,
                     Function<String, AcxDefinition> lookupActive,
                     BiFunction<String, String, AcxDefinition> lookupVersion,
                     AcxToolRegistry blocks,
                     FileAcxLibrary library) {
        this.sessions = Objects.requireNonNull(sessions, "sessions 不能为 null");
        this.lookupActive = Objects.requireNonNull(lookupActive, "lookupActive 不能为 null");
        this.lookupVersion = lookupVersion;
        this.blocks = Objects.requireNonNull(blocks, "blocks 不能为 null");
        this.library = library;
    }

    public AcxFacade(AcxSessionManager sessions,
                     Function<String, AcxDefinition> lookupActive,
                     AcxToolRegistry blocks,
                     FileAcxLibrary library) {
        this(sessions, lookupActive, null, blocks, library);
    }

    // ── 执行门面 ────────────────────────────────────────────────────────

    /**
     * 跑一个 AC。参数：{@code ac_json} 或 {@code ac_name}（二选一）、
     * 可选 {@code ac_version}、可选 {@code input}（Map）。
     *
     * <p>立刻回执 RUNNING + run_id（对齐 Numen「已受理 ≠ 已完成」），终态用 status 查。</p>
     */
    public Map<String, Object> execute(Map<String, Object> req) {
        try {
            AcxDefinition def = resolve(req);
            Map<String, Object> input = asMap(req.get("input"));
            String runId = sessions.start(def, input);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("run_id", runId);
            data.put("ac_name", def.name());
            data.put("ac_version", def.version());
            data.put("status", "RUNNING");
            data.put("async", true);
            return ok("已受理，后台执行中", data);
        } catch (RuntimeException e) {
            return err(e.getMessage());
        }
    }

    /** 查一次执行。参数：{@code run_id}。查询成功时 {@code data.status} 才是 AC 终态。 */
    public Map<String, Object> status(Map<String, Object> req) {
        String runId = str(req.get("run_id"));
        if (runId == null) {
            return err("缺少 run_id");
        }
        AcxSessionManager.Session s = sessions.session(runId);
        if (s == null) {
            return err("未知 run_id: " + runId);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", runId);
        if (s.isRunning()) {
            data.put("state", "RUNNING");
            data.put("elapsed_ms", System.currentTimeMillis() - s.startedAtMillis());
            return ok("仍在执行", data);
        }
        if (s.error() != null) {
            data.put("state", "ERROR");
            data.put("error", String.valueOf(s.error().getMessage()));
            return err("会话异常: " + s.error().getMessage());
        }
        AcxRunRecord r = s.record();
        data.putAll(r.toMap());
        data.put("state", r.status().name());
        String msg;
        switch (r.status()) {
            case SUCCESS -> msg = "完成";
            case PAUSED -> msg = "暂停" + suffix(r.pausedReason());
            case FAIL -> msg = "失败" + suffix(r.errorMessage());
            default -> msg = "超时";
        }
        return ok(msg, data);
    }

    /**
     * 续跑。参数：{@code run_id}、可选 {@code input}（环境事实刷新）。
     * 只有 PAUSED 能续；版本/指纹不符会被<b>同步拒绝</b>。
     */
    public Map<String, Object> resume(Map<String, Object> req) {
        String runId = str(req.get("run_id"));
        if (runId == null) {
            return err("缺少 run_id");
        }
        AcxSessionManager.Session s = sessions.session(runId);
        if (s == null) {
            return err("未知 run_id: " + runId);
        }
        if (s.isRunning()) {
            return err("仍在执行，不能续跑: " + runId);
        }
        AcxRunRecord r = s.record();
        if (r == null || r.status() != AcxStatus.PAUSED) {
            return err("只有 PAUSED 可以续跑，当前: " + (r == null ? "无记录" : r.status()));
        }
        int resumedFrom = r.completedStepIndex();
        try {
            if (!sessions.resume(runId, asMap(req.get("input")))) {
                return err("续跑被拒绝: " + runId);
            }
        } catch (RuntimeException e) {
            return err("续跑被拒绝: " + e.getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", runId);
        data.put("state", "RUNNING");
        data.put("async", true);
        data.put("resumed_from_step", resumedFrom);
        return ok("已受理，从断点续跑", data);
    }

    /** 协作式取消。参数：{@code run_id}。 */
    public Map<String, Object> cancel(Map<String, Object> req) {
        String runId = str(req.get("run_id"));
        if (runId == null) {
            return err("缺少 run_id");
        }
        AcxSessionManager.Session s = sessions.session(runId);
        if (s == null) {
            return err("未知 run_id: " + runId);
        }
        if (!s.isRunning()) {
            return err("已结束，无需取消: " + runId);
        }
        boolean sent = sessions.cancel(runId);
        if (!sent) {
            return err("取消请求发送失败: " + runId);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", runId);
        data.put("cancel_requested", true);
        return ok("已发送取消请求（协作式，积木轮询后收手）", data);
    }

    // ── 版本库门面（挂了 library 才有） ─────────────────────────────────

    /** 发布一个新版本（GENERATED，不切 active）。参数：{@code ac_json}、可选 {@code note}。 */
    public Map<String, Object> publish(Map<String, Object> req) {
        if (library == null) {
            return err("本门面未挂载版本库，不能发布");
        }
        try {
            Object json = req.get("ac_json");
            if (!(json instanceof String s) || s.isBlank()) {
                return err("发布需要 ac_json（完整 AC 定义）");
            }
            AcxDefinition def = AcxLoader.parseJson(s, blocks);
            List<String> problems = library.validate(def, new java.util.HashSet<>(library.names()));
            if (!problems.isEmpty()) {
                return err("校验不通过: " + String.join("; ", problems));
            }
            String version = library.publish(def, strOr(req.get("note"), ""),
                    new java.util.HashSet<>(library.names()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", def.name());
            data.put("version", version);
            data.put("status", library.status(def.name(), version).name());
            data.put("active", false);
            return ok("已发布（未生效，等人工批准）", data);
        } catch (RuntimeException e) {
            return err(e.getMessage());
        }
    }

    /** 人工批准并切为 active。参数：{@code name}、{@code version}、可选 {@code approver}/{@code note}。 */
    public Map<String, Object> approve(Map<String, Object> req) {
        if (library == null) {
            return err("本门面未挂载版本库，不能批准");
        }
        String name = str(req.get("name"));
        String version = str(req.get("version"));
        if (name == null || version == null) {
            return err("需要 name 和 version");
        }
        try {
            library.approve(name, version, strOr(req.get("approver"), "unknown"), strOr(req.get("note"), ""));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", name);
            data.put("version", version);
            data.put("status", library.status(name, version).name());
            data.put("active_version", library.activeVersion(name));
            return ok("已批准并生效", data);
        } catch (RuntimeException e) {
            return err(e.getMessage());
        }
    }

    /** 回滚到某个仍为 STABLE 的旧版本。参数：{@code name}、{@code version}、可选 {@code reason}。 */
    public Map<String, Object> rollback(Map<String, Object> req) {
        if (library == null) {
            return err("本门面未挂载版本库，不能回滚");
        }
        String name = str(req.get("name"));
        String version = str(req.get("version"));
        if (name == null || version == null) {
            return err("需要 name 和 version");
        }
        try {
            library.rollback(name, version, strOr(req.get("reason"), ""));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", name);
            data.put("version", version);
            data.put("active_version", library.activeVersion(name));
            return ok("已回滚", data);
        } catch (RuntimeException e) {
            return err(e.getMessage());
        }
    }

    /** 版本库只读视图（名字 + 当前生效版本）。 */
    public Map<String, Object> library(Map<String, Object> req) {
        if (library == null) {
            return err("本门面未挂载版本库");
        }
        Map<String, Object> active = new LinkedHashMap<>();
        Map<String, Object> pending = new LinkedHashMap<>();
        for (String name : library.names()) {
            // 观测接口必须容错：某条 AC 只有未批准版本时只是「没上线」，不是整体失败
            if (!library.isOnline(name)) {
                pending.put(name, "未上线（等人工批准）");
                continue;
            }
            try {
                active.put(name, library.activeVersion(name));
            } catch (RuntimeException e) {
                pending.put(name, e.getMessage());
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("names", library.names());
        data.put("active", active);
        data.put("not_online", pending);
        return ok("版本库状态", data);
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    /** ac_json / ac_name（+可选 ac_version）二选一解析。 */
    private AcxDefinition resolve(Map<String, Object> req) {
        Object json = req.get("ac_json");
        Object name = req.get("ac_name");
        boolean hasJson = json instanceof String s && !s.isBlank();
        boolean hasName = name instanceof String n && !n.isBlank();
        if (hasJson == hasName) {
            throw new IllegalArgumentException("必须且只能给 ac_json 或 ac_name 之一");
        }
        if (hasJson) {
            try {
                return AcxLoader.parseJson((String) json, blocks);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("AC JSON 解析失败: " + e.getMessage());
            }
        }
        String acName = (String) name;
        String version = str(req.get("ac_version"));
        AcxDefinition def;
        if (version != null) {
            if (lookupVersion == null) {
                throw new IllegalArgumentException("本门面未接按版本查询，不能指定 ac_version");
            }
            def = lookupVersion.apply(acName, version);
        } else {
            def = lookupActive.apply(acName);
        }
        if (def == null) {
            throw new IllegalArgumentException("未找到 AC: " + acName + (version == null ? "" : "@" + version));
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException("input 必须是对象: " + v.getClass().getSimpleName());
    }

    private static String str(Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static String strOr(Object v, String fallback) {
        String s = str(v);
        return s == null ? fallback : s;
    }

    private static String suffix(String detail) {
        return detail == null || detail.isBlank() ? "" : "：" + detail;
    }

    private static Map<String, Object> ok(String message, Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("message", message);
        if (data != null) {
            m.put("data", data);
        }
        return m;
    }

    private static Map<String, Object> err(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("message", message == null ? "未知错误" : message);
        return m;
    }
}
