package com.dwinovo.numen.agent.context;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文预算（IC-75 §15.5）：<b>先预留必知区，再压可选正文/目录；必知区自身装不下就
 * 显式拒绝（DENIED / CONSTRAINT_OVERFLOW），绝不静默切掉危险信息。</b>
 *
 * <p><b>为什么需要它</b>：运行期状态是「全部拼起来」交给模型的——`<current_task>`、背包、
 * 状态效果、骑乘、各插件现算片段，串成一条 XML。谁也没个数：背包一百多格时它自己就能吃掉
 * 几千 token，于是要么把真正要紧的任务镜像挤到很后面（模型读漏），要么整轮请求超窗。
 * 更要紧的是，**没有任何一处说得出「这次少了什么」**——少了也不会有人知道。
 *
 * <p><b>为什么必知区不参与裁剪</b>：`<current_task>` 是「我在干什么」，插件片段是
 * 「任务链/需求缺口」这种**只能现算、丢了取不回来**的东西。裁掉它们不是省 token，是改变语义。
 * 所以本类只做两件事：可选段落按优先级整段丢（并记原因），必知区装不下就拒绝这一次。
 *
 * <p><b>为什么不切半句</b>：中段截断会把一句话变成另一句话（"背包里有 64 个铁锭" → "背包里有 64"），
 * 而截断点之后的内容既没法复现也没法说清少了什么。<b>整段丢弃 + 点名</b>是可核的，
 * 半句截断不是。所以本类只丢整段，不做字符级裁剪。
 *
 * <p><b>字符上限是独立硬上限</b>：{@link #maxChars} 数的是 Java 字符数，不是 token 的替身。
 * {@link Section#tokens()} 由调用方用项目唯一那把尺算好（{@code CompactSplit}），
 * 本类不自己造第二把尺——两把尺会漂移，而漂移过的限额等于没有限额。
 * {@link #tokenizerVersion} 只记录「这批数是谁算的」，不做计量。
 *
 * <p><b>确定性</b>：不读时钟、不随机、不依赖线程。同一份输入两次分配结果逐字节相同——
 * 否则「为什么这一轮少了背包」就只能靠猜。
 *
 * <p><b>本类不做什么</b>：不认识 Minecraft，不拼 XML，不发事件，不调 LLM。切段与拼回都在调用点。
 */
public record ContextBudget(
        /** 参与分配的可选段落数上限（0 = 不限）。 */
        int maxCandidates,
        /** 保留的可选段落数上限（0 = 不限）。 */
        int maxRows,
        /** 展开正文条数上限（0 = 不限）。本轮没有正文展开，与 {@link #maxRows} 同值即可——留给 G5。 */
        int maxExpanded,
        /** 总 token 上限（0 = 不限）。 */
        long maxTokens,
        /** 总字符上限（0 = 不限）。独立硬上限，不是 token 的替身。 */
        long maxChars,
        /** 给必知区预留的 token：可选段落不许吃掉这部分（0 = 不额外预留）。 */
        long mandatoryReservedTokens,
        /** 这批 token 数是哪个尺子算的（本批 {@code compact-split-v1}）。只记录，不计量。 */
        String tokenizerVersion) {

    /** 全不限。配置里不写 {@code context} 段时用这个——行为与没有本机制完全一致。 */
    public static ContextBudget unlimited() {
        return new ContextBudget(0, 0, 0, 0L, 0L, 0L, "none");
    }

    /** 六维全 0 = 不限（此时 {@link #allocate} 只做全保留，不产生任何丢弃原因）。 */
    public boolean isUnlimited() {
        return maxCandidates <= 0 && maxRows <= 0 && maxExpanded <= 0
                && maxTokens <= 0 && maxChars <= 0 && mandatoryReservedTokens <= 0;
    }

    /**
     * 一段待分配的上下文。
     *
     * @param name      段落名（进丢弃原因与 monitor 事件，必须稳定可 grep，例如 {@code current_task}）
     * @param text      正文（{@code null} 按空串处理）
     * @param mandatory 必知区：永不裁、永不截；它装不下就是整次 DENIED
     * @param tokens    token 数，由调用方用唯一那把尺算好（负数按 0）
     * @param priority  保留优先级，<b>越大越先保留</b>（可选段落之间比；同值按输入顺序，保证确定性）。
     *                  调用点用它表达「丢了能不能取回」：能重取的低，丢不掉的（任务链镜像）高。
     */
    public record Section(String name, String text, boolean mandatory, int tokens, int priority) {

        public Section {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("section name is required");
            if (text == null) text = "";
            if (tokens < 0) tokens = 0;
        }

        /** 同优先级 0（不关心优先级时用它）。 */
        public Section(String name, String text, boolean mandatory, int tokens) {
            this(name, text, mandatory, tokens, 0);
        }

        public int chars() {
            return text.length();
        }

        public boolean isEmpty() {
            return text.isEmpty();
        }
    }

    /** 分配状态：{@code OK} 全装下；{@code TRUNCATED} 丢了可选段落；{@code DENIED} 必知区自身超限。 */
    public static final String OK = "OK";
    public static final String TRUNCATED = "TRUNCATED";
    public static final String DENIED = "DENIED";

    /** 拒绝原因码（进事件与日志，别改字串——测试与运维都按它 grep）。 */
    public static final String CONSTRAINT_OVERFLOW = "CONSTRAINT_OVERFLOW";

    /**
     * 一次分配的结果。
     *
     * @param status             {@link #OK}/{@link #TRUNCATED}/{@link #DENIED}
     * @param reasonCode         DENIED 时为 {@link #CONSTRAINT_OVERFLOW}，否则空串
     * @param kept               保留的段落（DENIED 时仍列出<b>全部</b>必知区——拒绝不等于返回空）
     * @param dropped            丢掉的段落（整段）
     * @param tokensUsed         保留段落的 token 合计
     * @param charsUsed          保留段落的字符合计
     * @param truncationReasons  每条丢弃一段的原因，形如 {@code inventory:TOKEN_LIMIT}
     * @param budget             本次用的预算（回显，省得调用方再去取一遍）
     */
    public record Allocation(String status, String reasonCode, List<Section> kept, List<Section> dropped,
                             long tokensUsed, long charsUsed, List<String> truncationReasons,
                             ContextBudget budget) {

        public boolean denied() {
            return DENIED.equals(status);
        }

        public boolean truncated() {
            return TRUNCATED.equals(status);
        }

        public List<String> keptNames() {
            List<String> names = new ArrayList<>(kept.size());
            for (Section s : kept) names.add(s.name());
            return List.copyOf(names);
        }

        public List<String> droppedNames() {
            List<String> names = new ArrayList<>(dropped.size());
            for (Section s : dropped) names.add(s.name());
            return List.copyOf(names);
        }
    }

    /**
     * 分配。
     *
     * <p>两条顺序，别混：
     * <ul>
     *   <li><b>取舍顺序</b>＝可选段落按 {@link Section#priority()} 从高到低（同优先级按输入顺序）；</li>
     *   <li><b>输出顺序</b>＝{@code kept}/{@code dropped}/原因一律<b>按输入顺序</b>——
     *       调用点拼回去的字节必须与不限额时完全一致，否则提示词缓存前缀会被打散。</li>
     * </ul>
     *
     * <p>必知区按输入顺序全保留，与优先级无关。
     */
    public static Allocation allocate(ContextBudget budget, List<Section> sections) {
        ContextBudget b = budget == null ? unlimited() : budget;
        List<Section> input = sections == null ? List.of() : sections;

        long mandatoryTokens = 0L;
        long mandatoryChars = 0L;
        for (Section s : input) {
            if (!s.mandatory()) continue;
            mandatoryTokens += s.tokens();
            mandatoryChars += s.chars();
        }

        boolean overflow = (b.maxTokens() > 0 && mandatoryTokens > b.maxTokens())
                || (b.maxChars() > 0 && mandatoryChars > b.maxChars());
        if (overflow) {
            // 必知区自身就装不下：不裁它，也不要可选的——这一轮该被拒绝，而不是"发半截然后装没事"。
            List<Section> kept = new ArrayList<>();
            List<Section> dropped = new ArrayList<>();
            List<String> reasons = new ArrayList<>();
            for (Section s : input) {
                if (s.mandatory()) {
                    kept.add(s);
                } else {
                    dropped.add(s);
                    reasons.add(s.name() + ":DENIED");
                }
            }
            return new Allocation(DENIED, CONSTRAINT_OVERFLOW, List.copyOf(kept), List.copyOf(dropped),
                    mandatoryTokens, mandatoryChars, List.copyOf(reasons), b);
        }

        // 可选段落能用多少：总量先扣掉「必知区已占」与「给必知区预留」里更严的那个。
        long optionalTokenCeiling = b.maxTokens() > 0
                ? Math.max(0L, b.maxTokens() - Math.max(mandatoryTokens, b.mandatoryReservedTokens()))
                : Long.MAX_VALUE;
        long optionalCharCeiling = b.maxChars() > 0
                ? Math.max(0L, b.maxChars() - mandatoryChars)
                : Long.MAX_VALUE;
        int rowCap = b.maxRows() > 0 ? b.maxRows() : Integer.MAX_VALUE;
        int candidateCap = b.maxCandidates() > 0 ? b.maxCandidates() : Integer.MAX_VALUE;

        // 取舍顺序：优先级高的先决定（稳定排序：同优先级保持输入顺序 → 结果确定）。
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            if (!input.get(i).mandatory()) order.add(i);
        }
        order.sort((a, c) -> {
            int byPriority = Integer.compare(input.get(c).priority(), input.get(a).priority());
            return byPriority != 0 ? byPriority : Integer.compare(a, c);
        });

        String[] reasonOf = new String[input.size()];
        long optionalTokens = 0L;
        long optionalChars = 0L;
        int optionalKept = 0;
        int optionalSeen = 0;
        for (int idx : order) {
            Section s = input.get(idx);
            optionalSeen++;
            if (optionalSeen > candidateCap) {
                reasonOf[idx] = "CANDIDATE_LIMIT";
            } else if (optionalKept >= rowCap) {
                reasonOf[idx] = "ROW_LIMIT";
            } else if (b.maxTokens() > 0 && optionalTokens + s.tokens() > optionalTokenCeiling) {
                reasonOf[idx] = "TOKEN_LIMIT";
            } else if (b.maxChars() > 0 && optionalChars + s.chars() > optionalCharCeiling) {
                reasonOf[idx] = "CHAR_LIMIT";
            } else {
                optionalTokens += s.tokens();
                optionalChars += s.chars();
                optionalKept++;
            }
        }

        // 输出顺序：按输入顺序回填，拼回去的字节与不限额时一致。
        List<Section> kept = new ArrayList<>(input.size());
        List<Section> dropped = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            Section s = input.get(i);
            if (s.mandatory()) {
                kept.add(s);
            } else if (reasonOf[i] == null) {
                kept.add(s);
            } else {
                dropped.add(s);
                reasons.add(s.name() + ":" + reasonOf[i]);
            }
        }

        long tokensUsed = mandatoryTokens + optionalTokens;
        long charsUsed = mandatoryChars + optionalChars;
        String status = dropped.isEmpty() ? OK : TRUNCATED;
        return new Allocation(status, "", List.copyOf(kept), List.copyOf(dropped),
                tokensUsed, charsUsed, List.copyOf(reasons), b);
    }

    /** 把预算里的六维写成一行（日志/事件用）。 */
    public String describe() {
        if (isUnlimited()) return "unlimited";
        return "tokens=" + (maxTokens > 0 ? String.valueOf(maxTokens) : "∞")
                + ",chars=" + (maxChars > 0 ? String.valueOf(maxChars) : "∞")
                + ",reserve=" + mandatoryReservedTokens
                + ",rows=" + (maxRows > 0 ? String.valueOf(maxRows) : "∞")
                + ",candidates=" + (maxCandidates > 0 ? String.valueOf(maxCandidates) : "∞")
                + ",tokenizer=" + tokenizerVersion;
    }
}
