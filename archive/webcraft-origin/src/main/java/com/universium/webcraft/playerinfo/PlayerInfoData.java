package com.universium.webcraft.playerinfo;

import java.util.List;
import java.util.Objects;

public record PlayerInfoData(
        String playerName,
        long playedTimeTicks,
        List<PlayerInfoItem> equipment,
        PlayerInfoItem mainHand,
        PlayerInfoItem offHand,
        List<PlayerInfoItem> inventory,
        List<PlayerInfoItem> enderChest
) {
    public PlayerInfoData {
        playerName = Objects.requireNonNull(playerName, "playerName");
        equipment = List.copyOf(Objects.requireNonNull(equipment, "equipment"));
        mainHand = Objects.requireNonNull(mainHand, "mainHand");
        offHand = Objects.requireNonNull(offHand, "offHand");
        inventory = List.copyOf(Objects.requireNonNull(inventory, "inventory"));
        enderChest = List.copyOf(Objects.requireNonNull(enderChest, "enderChest"));
    }
}

