package com.universium.webcraft.api;

/**
 * Opts a read route into WebSocket subscriptions where enabled by the operator. Reads must
 * return stable, immutable snapshots. The core reuses the route's regular access checks and
 * compares consecutive results before sending updates.
 */
public interface WebCraftLiveRoute extends WebCraftReadRoute {
    /** Returns the same change signal throughout the lifetime of this route. */
    WebCraftChanges changes();

    /** A live route that explicitly serves public snapshots. */
    interface Public extends WebCraftLiveRoute, PublicRoute {}
}
