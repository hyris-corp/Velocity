/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.connection.client;

import com.velocitypowered.api.event.player.PlayerClientModInfoEvent;
import com.velocitypowered.api.util.ClientModInfo;
import com.velocitypowered.api.util.ModInfo;
import com.velocitypowered.proxy.VelocityServer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Tracks client-advertised mod evidence and queries Lunar Client's installed-mods API. */
final class ClientModInfoTracker {
  private static final Logger LOGGER = LogManager.getLogger(ClientModInfoTracker.class);
  private static final String LUNAR_BRAND = "lunarclient";
  private static final String LUNAR_APOLLO_CHANNEL = "lunar:apollo";
  private static final String LUNAR_JSON_CHANNEL = "apollo:json";
  private static final int MAX_RESPONSE_BYTES = 262144;
  private static final long SCAN_TIMEOUT_SECONDS = 10;
  private static final Set<String> KNOWN_LOADERS = Set.of("fabric", "forge", "neoforge", "quilt");

  private final ConnectedPlayer player;
  private final VelocityServer server;
  private final Object lock = new Object();
  private final Map<String, String> mods = new java.util.HashMap<>();
  private final Set<String> loaders = new java.util.HashSet<>();
  private final Set<String> channels = new java.util.HashSet<>();
  private @org.checkerframework.checker.nullness.qual.Nullable String brand;
  private boolean lunarClient;
  private boolean lunarScanComplete;
  private @org.checkerframework.checker.nullness.qual.Nullable String requestId;
  private @org.checkerframework.checker.nullness.qual.Nullable CompletableFuture<ClientModInfo> pendingRequest;

  ClientModInfoTracker(ConnectedPlayer player, VelocityServer server) {
    this.player = player;
    this.server = server;
  }

  ClientModInfo snapshot() {
    synchronized (lock) {
      return new ClientModInfo(mods, loaders, channels, brand, lunarClient, lunarScanComplete);
    }
  }

  void recordBrand(String brand) {
    synchronized (lock) {
      this.brand = brand;
      String normalized = brand.toLowerCase(Locale.ROOT);
      String[] brandTokens = normalized.split("[^a-z0-9]+");
      lunarClient |= normalized.contains(LUNAR_BRAND)
          || normalized.contains("lunar client")
          || normalized.contains("lunar-client");
      for (String token : brandTokens) {
        if (KNOWN_LOADERS.contains(token)) {
          loaders.add(token);
        }
      }
      publishLocked();
    }
  }

  void recordForgeMods(ModInfo info) {
    synchronized (lock) {
      for (ModInfo.Mod mod : info.getMods()) {
        mods.put(mod.getId(), mod.getVersion());
        if (KNOWN_LOADERS.contains(mod.getId().toLowerCase(Locale.ROOT))) {
          loaders.add(mod.getId().toLowerCase(Locale.ROOT));
        }
      }
      String type = info.getType().toLowerCase(Locale.ROOT);
      if (KNOWN_LOADERS.contains(type)) {
        loaders.add(type);
      }
      publishLocked();
    }
  }

  void recordChannels(Iterable<? extends com.velocitypowered.api.proxy.messages.ChannelIdentifier> added,
                      boolean registered) {
    synchronized (lock) {
      for (var channel : added) {
        String id = channel.getId().toLowerCase(Locale.ROOT);
        if (registered) {
          channels.add(id);
        } else {
          channels.remove(id);
        }
        lunarClient |= LUNAR_APOLLO_CHANNEL.equals(id) || LUNAR_JSON_CHANNEL.equals(id);
      }
      publishLocked();
      if (registered) {
        sendRequestIfReadyLocked();
      }
    }
  }

  CompletableFuture<ClientModInfo> request() {
    synchronized (lock) {
      if (lunarScanComplete) {
        return CompletableFuture.completedFuture(snapshotLocked());
      }
      if (pendingRequest != null) {
        return pendingRequest;
      }
      pendingRequest = new CompletableFuture<>();
      CompletableFuture.delayedExecutor(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute(() -> {
        synchronized (lock) {
          if (pendingRequest != null) {
            CompletableFuture<ClientModInfo> timedOut = pendingRequest;
            pendingRequest = null;
            requestId = null;
            timedOut.complete(snapshotLocked());
          }
        }
      });
      sendRequestIfReadyLocked();
      return pendingRequest;
    }
  }

  /**
   * Consumes client-to-proxy Lunar JSON messages and records installed-mod responses.
   * Other plugin-message channels are untouched.
   */
  boolean handlePluginMessage(String channel, ByteBuf payload) {
    if (!LUNAR_JSON_CHANNEL.equals(channel)) {
      return false;
    }
    if (payload.readableBytes() > MAX_RESPONSE_BYTES) {
      LOGGER.warn("Discarding oversized Lunar mod response from {}", player.getUsername());
      return true;
    }

    byte[] bytes = ByteBufUtil.getBytes(payload);
    var parsed = LunarClientModProtocol.parseInstalledModsResponse(bytes);
    if (parsed.isEmpty()) {
      LOGGER.debug("Ignoring malformed Lunar plugin message from {}", player.getUsername());
      return true;
    }

    synchronized (lock) {
      String responseId = parsed.get().requestId();
      if (requestId == null || responseId != null && !requestId.equals(responseId)) {
        return true;
      }
      mods.putAll(parsed.get().mods());
      lunarClient = true;
      lunarScanComplete = true;
      requestId = null;
      publishLocked();
      CompletableFuture<ClientModInfo> completed = pendingRequest;
      pendingRequest = null;
      if (completed != null) {
        completed.complete(snapshotLocked());
      }
    }
    return true;
  }

  private void sendRequestIfReadyLocked() {
    if (pendingRequest == null || requestId != null
        || !channels.contains(LUNAR_APOLLO_CHANNEL) && !channels.contains(LUNAR_JSON_CHANNEL)) {
      return;
    }
    UUID id = UUID.randomUUID();
    if (player.sendPluginMessage(
        com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.from(LUNAR_JSON_CHANNEL),
        LunarClientModProtocol.installedModsRequest(id))) {
      requestId = id.toString();
    } else {
      LOGGER.debug("Lunar mod request could not be sent for {}", player.getUsername());
    }
  }

  private ClientModInfo snapshotLocked() {
    return new ClientModInfo(mods, loaders, channels, brand, lunarClient, lunarScanComplete);
  }

  private void publishLocked() {
    server.getEventManager().fireAndForget(new PlayerClientModInfoEvent(player, snapshotLocked()));
  }
}
