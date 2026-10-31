package com.dwinovo.numen.plugins.learner.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Skill 目录的<strong>只读读数</strong>（给 `learner_status` 用）。
 *
 * <h2>为什么需要它</h2>
 * 2026-10-31 实机：目录是空的，但唯一能发现这件事的方法是**去文件系统看 `skills.jsonl` 在不在**。
 * 那是"靠考古验证"，不是"可观测"。
 *
 * <p>所以把读数搬进状态工具：空目录、读不出来、各状态各几条，一眼可见 ——
 * 而且要和"为什么空"（没有过门产物 / 登记入口没接）区分开：
 * 本类只说事实（文件在不在、几条、什么状态），不猜原因。
 *
 * <p>纯 JVM（只读文件），可离线用临时目录测。
 */
public final class SkillCatalogView {

    private SkillCatalogView() {
    }

    /** 统计目录现状。任何异常都如实落在 `error` 里，不抛。 */
    public static Map<String, Object> stats(Path configDir) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (configDir == null) {
            out.put("readable", false);
            out.put("error", "configDir 为空");
            return out;
        }
        Path file = configDir.resolve("skills.jsonl");
        out.put("file", file.toString());
        if (!Files.isRegularFile(file)) {
            // 空目录 ≠ 坏目录：这里必须说清是"还没有条目"，不是"读不到"。
            out.put("readable", true);
            out.put("exists", false);
            out.put("entries", 0);
            out.put("note", "目录文件还不存在：说明还没有条目 —— 一次都没有「通过契约校验的产物」被登记（登记入口本身可能正常）");
            return out;
        }
        try {
            SkillRegistry reg = SkillRegistry.open(file);
            int trial = 0, published = 0, draft = 0, suspended = 0, revoked = 0;
            for (SkillEntry e : reg.all()) {
                switch (e.status()) {
                    case TRIAL -> trial++;
                    case PUBLISHED -> published++;
                    case DRAFT -> draft++;
                    case SUSPENDED -> suspended++;
                    case REVOKED -> revoked++;
                }
            }
            out.put("readable", true);
            out.put("exists", true);
            out.put("entries", reg.size());
            out.put("retrievable", reg.retrievable().size());
            out.put("trial", trial);
            out.put("published", published);
            out.put("draft", draft);
            out.put("suspended", suspended);
            out.put("revoked", revoked);
            out.put("skipped_lines", reg.skippedLines());
            if (reg.size() == 0) {
                out.put("note", "文件存在但没有任何条目：可能只写过空目录，或全部行都坏了（见 skipped_lines）");
            }
            return out;
        } catch (IOException | RuntimeException e) {
            out.put("readable", false);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return out;
        }
    }
}
