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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import com.velocitypowered.api.event.player.PlayerClientModInfoEvent;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.event.VelocityEventManager;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ClientModInfoTrackerTest {
  @Test
  void requestsLunarModsOnceChannelIsRegisteredAndCompletesWithResponse() {
    ConnectedPlayer player = mock(ConnectedPlayer.class);
    VelocityServer server = mock(VelocityServer.class);
    VelocityEventManager eventManager = mock(VelocityEventManager.class);
    when(server.getEventManager()).thenReturn(eventManager);
    when(player.getUsername()).thenReturn("LunarPlayer");
    when(player.sendPluginMessage(any(MinecraftChannelIdentifier.class), any(byte[].class)))
        .thenReturn(true);
    ClientModInfoTracker tracker = new ClientModInfoTracker(player, server);

    CompletableFuture<com.velocitypowered.api.util.ClientModInfo> requested = tracker.request();
    assertFalse(requested.isDone());
    tracker.recordChannels(List.of(MinecraftChannelIdentifier.from("apollo:json")), true);

    org.mockito.ArgumentCaptor<byte[]> requestCaptor = org.mockito.ArgumentCaptor.forClass(byte[].class);
    verify(player).sendPluginMessage(
        org.mockito.ArgumentMatchers.eq(MinecraftChannelIdentifier.from("apollo:json")),
        requestCaptor.capture());
    String requestId = JsonParser.parseString(new String(requestCaptor.getValue(), StandardCharsets.UTF_8))
        .getAsJsonObject().get("request_id").getAsString();
    String response = "{\"@type\":\"type.googleapis.com/"
        + "lunarclient.apollo.modsetting.v1.InstalledModsResponse\","
        + "\"request_id\":\"" + requestId + "\",\"mod_groups\":[{\"mods\":["
        + "{\"id\":\"MouseTweaks\"}]}]}";

    assertTrue(tracker.handlePluginMessage("apollo:json",
        Unpooled.wrappedBuffer(response.getBytes(StandardCharsets.UTF_8))));
    var info = requested.join();
    assertEquals("", info.getMods().get("MouseTweaks"));
    assertTrue(info.isLunarClient());
    assertTrue(info.isLunarScanComplete());
    verify(eventManager, org.mockito.Mockito.atLeastOnce())
        .fireAndForget(any(PlayerClientModInfoEvent.class));
  }
}
