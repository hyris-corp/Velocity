/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.velocitypowered.api.event.player;

import com.google.common.base.Preconditions;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.util.ClientModInfo;

/** Fired when Velocity learns new client mod, loader, brand, channel, or Lunar information. */
public final class PlayerClientModInfoEvent {
  private final Player player;
  private final ClientModInfo clientModInfo;

  public PlayerClientModInfoEvent(Player player, ClientModInfo clientModInfo) {
    this.player = Preconditions.checkNotNull(player, "player");
    this.clientModInfo = Preconditions.checkNotNull(clientModInfo, "clientModInfo");
  }

  public Player getPlayer() {
    return player;
  }

  public ClientModInfo getClientModInfo() {
    return clientModInfo;
  }

  @Override
  public String toString() {
    return "PlayerClientModInfoEvent{" + "player=" + player + ", clientModInfo=" + clientModInfo
        + '}';
  }
}
