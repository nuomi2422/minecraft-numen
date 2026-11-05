package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.ChangesQuery;
import com.dwinovo.numen.api.observation.ChangesV2Response;
import com.dwinovo.numen.api.observation.WorldObservationService;
import com.dwinovo.numen.worldstate.core.changes.v2.ChangeEvent;
import com.dwinovo.numen.worldstate.core.changes.v2.ChangeFeed;
import com.dwinovo.numen.worldstate.core.changes.v2.ChangesPage;

import java.util.ArrayList;
import java.util.List;

/**
 * 版本化 changes 的**映射逻辑**（042 R3-1/R3-2，纯函数，无 MC 依赖）。
 *
 * <p>为什么单独抽出来：能力面（ObservationCapability）依赖 MC 类型，离线测不了；
 * 而"哪个版本走哪条路、缺 epoch 怎么办"恰恰是最容易写错的地方。
 *
 * <h2>规则（定死）</h2>
 * <ul>
 *   <li>`schemaVersion=1` → **明确拒绝**，让调用方走老的 `changes(after,...)`
 *       （不静默降级，否则等于悄悄改语义）；</li>
 *   <li>`schemaVersion=2` + **epoch 为空** → `resetRequired=true`，游标跟着 watermark，
 *       绝不默认"当前 epoch"（重启后序号会重发，缺 epoch 的游标不可信）；</li>
 *   <li>feed 缺席 → ERROR（拿不到增量 ≠ 没有变化）；</li>
 *   <li>其余 → 原样映射 `ChangesPage`。</li>
 * </ul>
 */
public final class VersionedChangesMapper {

    private VersionedChangesMapper() {}

    public static ChangesV2Response map(ChangesQuery query, ChangeFeed feed) {
        if (query == null) {
            return ChangesV2Response.error("changes query required");
        }
        // 显式三分，不用二分开关：schemaVersion=99 绝不能掉进 v1 分支（静默降级）
        if (query.schemaVersion() == ChangesQuery.SCHEMA_V1) {
            return ChangesV2Response.error(
                    "schemaVersion=1 must use changes(after, topics, limit)");
        }
        if (query.schemaVersion() != ChangesQuery.SCHEMA_V2) {
            return ChangesV2Response.error("unsupported schemaVersion=" + query.schemaVersion());
        }
        if (feed == null) {
            return ChangesV2Response.error("v2 change feed not available");
        }
        long watermark = feed.watermark();
        String epoch = feed.streamEpoch();
        if (query.epoch().isBlank()) {
            // 缺 epoch = 不知道游标属于哪条流 → 必须重同步，而不是默认当前 epoch
            return new ChangesV2Response(WorldObservationService.Status.OK,
                    List.of(), watermark, true, watermark, false, epoch,
                    "epoch missing; resync required");
        }
        ChangesPage page = feed.query(query.after(), query.epoch(), toSet(query.topics()),
                query.limit());
        List<ChangesV2Response.Event> events = new ArrayList<>(page.events().size());
        for (ChangeEvent e : page.events()) {
            events.add(new ChangesV2Response.Event(e.streamSeq(), e.topic(), e.key(),
                    e.kind().name(), e.observationRevision(), e.epochMs()));
        }
        return new ChangesV2Response(WorldObservationService.Status.OK, events,
                page.nextCursor(), page.resetRequired(), page.watermark(), page.hasMore(),
                page.streamEpoch(), page.resetRequired() ? "cursor not usable; resync required" : null);
    }

    private static java.util.Set<String> toSet(List<String> topics) {
        if (topics == null || topics.isEmpty()) return java.util.Set.of();
        return new java.util.LinkedHashSet<>(topics);
    }
}
