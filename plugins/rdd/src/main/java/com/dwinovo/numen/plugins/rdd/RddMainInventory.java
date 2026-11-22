package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.observation.SelfInventoryQuery;
import java.util.Map;

/** Compatibility facade; main36 aggregation has one shared implementation. */
final class RddMainInventory {
    record Result(Map<String, Long> counts, Long total, String reason) { boolean known() { return reason == null; } }
    private RddMainInventory() {}
    static Result aggregate(Object raw) {
        var result = SelfInventoryQuery.aggregate(raw);
        return new Result(result.counts(), result.total(), result.reason());
    }
}
