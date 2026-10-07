package com.dwinovo.numen.rdd.side;

import java.util.Map;
import java.util.UUID;

/**
 * 一类支线的硬编码实现（SPI）。
 *
 * <p><b>以后加一条支线 = 加一个 {@code SideTaskType} + 一个 {@link SideTaskTrigger}。</b>
 * 生命周期、持久化、回执代次校验、主线恢复全部复用通用引擎，不再各写一套。
 */
public interface SideTaskType {

    String typeId();

    /** 保护超时（tick）：创建时刻 + 该值 = deadline。 */
    long deadlineTicks();

    SideTask create(CreationContext ctx);

    SideTaskVerdict evaluate(SideTask task, SideTaskWorldPort world);

    /** 渲染注入执行 AI 的上下文（{@code <side_task>…</side_task>} 形状）。 */
    String renderContext(SideTask task, SideTaskWorldPort world);

    record CreationContext(
            UUID companionId,
            String instanceId,
            long generation,
            long scheduledGameDay,
            long gameTime,
            long deadlineGameTime,
            MainlineResumeToken mainlineToken,
            Map<String, String> data) {
        public CreationContext {
            data = data == null ? Map.of() : Map.copyOf(data);
        }
    }
}
