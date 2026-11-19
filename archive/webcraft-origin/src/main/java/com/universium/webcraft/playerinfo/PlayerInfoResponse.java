package com.universium.webcraft.playerinfo;

public record PlayerInfoResponse(PlayerInfoData data) {
    public String toJson() {
        return PlayerInfoResponseJson.toJson(data);
    }
}

