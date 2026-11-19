package com.universium.webcraft.playerinfo;

public final class PlayerInfoModule {
    public static final String MODULE_ID = "player-info";
    public static final String DEFAULT_PATH_PREFIX = "/player-info";

    public PlayerInfoResponse render(PlayerInfoData data) {
        return new PlayerInfoResponse(data);
    }
}

