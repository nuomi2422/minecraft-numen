package com.dwinovo.numen.agent.retrieval;

/**
 * G5g（75 号 §15.8-B6 的前置）：**执行入口的唯一闸门**。
 *
 * <h2>为什么要有它</h2>
 * G5f 证明的是「展开不等于执行」——但那靠的是「provider 里没写 dispatch」这种**约定**。
 * G5g 把工具目录接进检索之后，目录条目会真的出现在上下文里，
 * 「看到 goto 的说明书 → 顺手执行 goto」就成了一个必须**结构性**挡住的路，
 * 不能只靠「实现的人很小心」。
 *
 * <h2>判据（Y4）</h2>
 * 任何带着 {@link EntryDescriptor#viewOnly()} = true 的来源请求执行，
 * 一律回 {@link #CODE_VIEW_ONLY}，**不做「尽力而为」的降级**：
 * 拒绝要说清「这条是只读目录，不是执行票据」，而不是悄悄执行掉。
 *
 * <p><b>本批没有执行通道</b>：{@link #CODE_NO_PATH} 是给「非只读条目」的诚实答复 ——
 * 经验条目也不能凭检索结果直接执行（那要 B6 的发布资格 + executionRef）。
 * 也就是说：今天这个闸门对**所有**条目都是关的，只是关的理由不同。
 */
public final class ExecutionGate {

    /** 只读目录条目（`tool:`）被拿去执行 → 拒。 */
    public static final String CODE_VIEW_ONLY = "EXECUTION_FROM_VIEW_ONLY_REF";

    /** 非只读条目，但本批根本没有执行通道（B6 才开）。 */
    public static final String CODE_NO_PATH = "NO_EXECUTION_PATH";

    private static final String OK = "OK";

    private ExecutionGate() {}

    /** 一次判定：allowed=false 时 code/message 必须能直接给人看。 */
    public record Verdict(boolean allowed, String code, String message) {
        public Verdict {
            code = code == null ? "" : code;
            message = message == null ? "" : message;
        }
    }

    /** 按 descriptor 判定（ref 前缀已在 {@link EntryDescriptor} 里强制成 viewOnly，这里只读它）。 */
    public static Verdict check(EntryDescriptor descriptor) {
        if (descriptor == null) {
            return new Verdict(false, "BAD_DESCRIPTOR", "没有条目元数据，不能执行");
        }
        if (descriptor.viewOnly()) {
            return new Verdict(false, CODE_VIEW_ONLY,
                    "「" + descriptor.entryRef() + "」是只读目录条目：可以看、可以展开，"
                            + "但不是执行票据。要执行请走任务/工具入口，别从检索结果直接下发。");
        }
        return new Verdict(false, CODE_NO_PATH,
                "本批没有「从检索结果执行」的通道（B6 才开）；这条不是只读目录，但也不许绕过任务入口。");
    }

    /** 便捷入口：按 ref + descriptor 判定，前缀与 descriptor 不一致时按更保守的那侧拒。 */
    public static Verdict check(EntryRef ref, EntryDescriptor descriptor) {
        if (descriptor == null) {
            return check((EntryDescriptor) null);
        }
        if (ref != null && ref.value() != null
                && ref.value().startsWith(ToolCatalogRetrievalProvider.REF_PREFIX)
                && !descriptor.viewOnly()) {
            // ref 说是目录、descriptor 说不是 —— 两份证据打架时按「不许执行」走
            return new Verdict(false, CODE_VIEW_ONLY,
                    "ref 前缀是 tool:（只读目录），但元数据没标 viewOnly —— 两份证据打架，按不许执行处理。");
        }
        return check(descriptor);
    }

    public static String okCode() {
        return OK;
    }
}
