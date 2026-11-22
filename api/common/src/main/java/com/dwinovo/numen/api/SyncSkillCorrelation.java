package com.dwinovo.numen.api;

import java.util.UUID;

/** Server-only correlation, not authorization. The host must first verify sender ownership,
 * capture the live companion owner and exact server token, and pass the actual wire id/tool.
 * No selection id means ordinary dispatch. Never accept owner or token from the client. */
public interface SyncSkillCorrelation {
    enum Binding { NONE, BOUND, DUPLICATE, REJECTED }
    enum Receipt { SUCCESS, FAIL, CANCELED, UNKNOWN }
    Binding bind(String selectionId, UUID owner, UUID companion, WorldContextStamp.Token token,
                 String wireId, String tool);
    /** Call once with actual synchronous receipt; asynchronous acceptance/exception is UNKNOWN. */
    void receipt(String selectionId, UUID owner, UUID companion, WorldContextStamp.Token token,
                 String wireId, String tool, Receipt receipt);
}
