package com.universium.webcraft.runtime;

import com.universium.webcraft.playerinfo.PlayerInfoData;

import java.util.List;

public interface webcraftPlayerDataProvider {
    PlayerInfoData getPlayerInfo(String playerName);

    default List<String> getPlayerNames() {
        return List.of();
    }

    default List<String> getOnlinePlayerNames() {
        return getPlayerNames();
    }
}

