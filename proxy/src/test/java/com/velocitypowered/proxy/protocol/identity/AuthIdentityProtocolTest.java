/*
 * Copyright (C) 2018-2026 Velocity Contributors
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

package com.velocitypowered.proxy.protocol.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.auth.AccountType;
import com.velocitypowered.api.auth.AuthIdentity;
import com.velocitypowered.api.auth.AuthState;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuthIdentityProtocolTest {
  @Test
  void respondsOnlyForTheConnectedPlayersUuidAndReportsPremiumProof() {
    UUID id = UUID.randomUUID();
    ConnectedPlayer player = mock(ConnectedPlayer.class);
    VelocityServerConnection source = mock(VelocityServerConnection.class);
    when(source.getPlayer()).thenReturn(player);
    when(player.getUniqueId()).thenReturn(id);
    when(player.getCurrentServer()).thenReturn(Optional.of((ServerConnection) source));
    when(player.getAuthIdentity()).thenReturn(new AuthIdentity(id, id, "OfficialPlayer",
        AccountType.PREMIUM, AuthState.AUTHENTICATED));

    var packet = requestPacket(AuthIdentityProtocol.request(id));
    assertTrue(AuthIdentityProtocol.handle(source, packet));
    packet.release();
    var response = captureResponse(source);
    assertEquals(AuthIdentityProtocol.STATUS_OK, response.get() & 0xff);
    assertEquals(id, new UUID(response.getLong(), response.getLong()));
    assertEquals(2, response.get() & 0xff);
    assertTrue(response.get() != 0);
  }

  @Test
  void offlineIdentityRemainsUnauthenticatedUntilItsGateCompletes() {
    UUID id = UUID.randomUUID();
    ConnectedPlayer player = mock(ConnectedPlayer.class);
    VelocityServerConnection source = mock(VelocityServerConnection.class);
    when(source.getPlayer()).thenReturn(player);
    when(player.getUniqueId()).thenReturn(id);
    when(player.getCurrentServer()).thenReturn(Optional.of((ServerConnection) source));
    when(player.getAuthIdentity()).thenReturn(new AuthIdentity(id, id, "OfflinePlayer",
        AccountType.OFFLINE, AuthState.OFFLINE_AUTHENTICATING));

    var packet = requestPacket(AuthIdentityProtocol.request(id));
    assertTrue(AuthIdentityProtocol.handle(source, packet));
    packet.release();
    var response = captureResponse(source);
    assertEquals(AuthIdentityProtocol.STATUS_UNAUTHENTICATED, response.get() & 0xff);
    assertEquals(id, new UUID(response.getLong(), response.getLong()));
    assertEquals(1, response.get() & 0xff);
    assertFalse(response.get() != 0);
  }

  @Test
  void rejectsUuidMismatchWithoutDisclosingTheConnectedIdentity() {
    UUID actual = UUID.randomUUID();
    UUID requested = UUID.randomUUID();
    ConnectedPlayer player = mock(ConnectedPlayer.class);
    VelocityServerConnection source = mock(VelocityServerConnection.class);
    when(source.getPlayer()).thenReturn(player);
    when(player.getUniqueId()).thenReturn(actual);
    when(player.getCurrentServer()).thenReturn(Optional.of((ServerConnection) source));

    var packet = requestPacket(AuthIdentityProtocol.request(requested));
    assertTrue(AuthIdentityProtocol.handle(source, packet));
    packet.release();
    var response = captureResponse(source);
    assertEquals(AuthIdentityProtocol.STATUS_INVALID_REQUEST, response.get() & 0xff);
    assertEquals(requested, new UUID(response.getLong(), response.getLong()));
    assertEquals(0, response.get() & 0xff);
    assertFalse(response.get() != 0);
  }

  private static PluginMessagePacket requestPacket(byte[] payload) {
    return new PluginMessagePacket(AuthIdentityProtocol.CHANNEL, Unpooled.wrappedBuffer(payload));
  }

  private static ByteBuffer captureResponse(VelocityServerConnection source) {
    var captor = ArgumentCaptor.forClass(byte[].class);
    verify(source).sendPluginMessage(eq(MinecraftChannelIdentifier.from(AuthIdentityProtocol.CHANNEL)),
        captor.capture());
    var bytes = captor.getValue();
    assertEquals(25, bytes.length);
    var response = ByteBuffer.wrap(bytes);
    assertEquals(AuthIdentityProtocol.MAGIC, response.getInt());
    assertEquals(AuthIdentityProtocol.VERSION, response.get() & 0xff);
    assertEquals(AuthIdentityProtocol.RESPONSE, response.get() & 0xff);
    return response;
  }
}
