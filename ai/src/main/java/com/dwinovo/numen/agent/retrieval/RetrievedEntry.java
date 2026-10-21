package com.dwinovo.numen.agent.retrieval;

/**
 * 75 号 §15.5 一条条目经过排序/适用性检查后的「可呈现条目」。
 *
 * <p>{@code applicability}：<b>PASS</b> 才算可用于行动；UNKNOWN 只呈现为待核实线索
 * （IC08/IC09），<b>不经自动执行</b>。
 */
public record RetrievedEntry(EntryDescriptor descriptor, EntryBody body, Applicability applicability) {

    public RetrievedEntry {
        if (descriptor == null) throw new NullPointerException("descriptor 不能为空");
        if (applicability == null) throw new NullPointerException("applicability 不能为空");
    }
}
