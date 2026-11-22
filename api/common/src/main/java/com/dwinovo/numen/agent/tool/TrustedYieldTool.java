package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import java.util.function.Consumer;

/** Yield-only trusted host entry. Binding must be captured by the original host run. */
public interface TrustedYieldTool {
    Object captureHostBinding(NumenPlayer companion);
    void onTrustedHostCall(String id, JsonObject args, NumenPlayer companion, Object binding, Consumer<String> reply);
}
