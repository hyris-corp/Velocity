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

import com.velocitypowered.api.auth.AccountType;
import com.velocitypowered.api.auth.AuthIdentity;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.UUID;

/** Versioned backend-only request/response codec for trusted player identity snapshots. */
public final class AuthIdentityProtocol {
  public static final String CHANNEL = "hyris:identity";
  public static final int VERSION = 1;
  public static final int MAGIC = 0x48595249; // HYRI
  public static final int REQUEST = 1;
  public static final int RESPONSE = 2;

  public static final int STATUS_OK = 0;
  public static final int STATUS_UNAUTHENTICATED = 1;
  public static final int STATUS_INVALID_REQUEST = 2;
  public static final int STATUS_UNSUPPORTED_VERSION = 3;

  private static final int REQUEST_LENGTH = 22;
  private static final int RESPONSE_LENGTH = 25;
  private static final MinecraftChannelIdentifier IDENTIFIER =
      MinecraftChannelIdentifier.from(CHANNEL);
  private static final UUID ZERO_UUID = new UUID(0, 0);

  private AuthIdentityProtocol() { }

  public static boolean isIdentityChannel(String channel) {
    return CHANNEL.equals(channel);
  }

  /**
   * Handles only messages sent by the current backend connection. Returns true when the channel
   * was consumed, including malformed or client-id-mismatched requests.
   */
  public static boolean handle(VelocityServerConnection source, PluginMessagePacket packet) {
    if (!CHANNEL.equals(packet.getChannel()))
      return false;

    var player = source.getPlayer();
    var request = decodeRequest(packet.content());
    if (request == null) {
      reply(source, STATUS_INVALID_REQUEST, ZERO_UUID, AccountType.UNKNOWN, false);
      return true;
    }
    if (request.version() != VERSION) {
      reply(source, STATUS_UNSUPPORTED_VERSION, request.playerId(), AccountType.UNKNOWN, false);
      return true;
    }
    if (request.type() != REQUEST || !request.playerId().equals(player.getUniqueId())
        || player.getCurrentServer().map(connection -> connection != source).orElse(true)) {
      reply(source, STATUS_INVALID_REQUEST, request.playerId(), AccountType.UNKNOWN, false);
      return true;
    }

    AuthIdentity identity = player.getAuthIdentity();
    boolean currentIdentity = identity.playerId().equals(player.getUniqueId())
        && identity.authenticatedUuid() != null;
    boolean authenticated = currentIdentity && identity.authenticated();
    reply(source, authenticated ? STATUS_OK : STATUS_UNAUTHENTICATED,
        currentIdentity ? identity.authenticatedUuid() : player.getUniqueId(),
        currentIdentity ? identity.accountType() : AccountType.UNKNOWN,
        authenticated);
    return true;
  }

  private static Request decodeRequest(ByteBuf source) {
    if (source.readableBytes() != REQUEST_LENGTH)
      return null;
    ByteBuf input = source.duplicate();
    if (input.readInt() != MAGIC)
      return null;
    int version = input.readUnsignedByte();
    int type = input.readUnsignedByte();
    UUID playerId = new UUID(input.readLong(), input.readLong());
    return new Request(version, type, playerId);
  }

  static byte[] request(UUID playerId) {
    ByteBuf output = Unpooled.buffer(REQUEST_LENGTH, REQUEST_LENGTH);
    output.writeInt(MAGIC);
    output.writeByte(VERSION);
    output.writeByte(REQUEST);
    output.writeLong(playerId.getMostSignificantBits());
    output.writeLong(playerId.getLeastSignificantBits());
    byte[] bytes = io.netty.buffer.ByteBufUtil.getBytes(output);
    output.release();
    return bytes;
  }

  private static void reply(VelocityServerConnection target, int status, UUID playerId,
                            AccountType accountType, boolean authenticated) {
    ByteBuf output = Unpooled.buffer(RESPONSE_LENGTH, RESPONSE_LENGTH);
    output.writeInt(MAGIC);
    output.writeByte(VERSION);
    output.writeByte(RESPONSE);
    output.writeByte(status);
    output.writeLong(playerId.getMostSignificantBits());
    output.writeLong(playerId.getLeastSignificantBits());
    output.writeByte(accountTypeCode(accountType));
    output.writeBoolean(authenticated);
    try {
      target.sendPluginMessage(IDENTIFIER, io.netty.buffer.ByteBufUtil.getBytes(output));
    } finally {
      output.release();
    }
  }

  private static int accountTypeCode(AccountType accountType) {
    return switch (accountType) {
      case UNKNOWN -> 0;
      case OFFLINE -> 1;
      case PREMIUM -> 2;
    };
  }

  private record Request(int version, int type, UUID playerId) { }
}
