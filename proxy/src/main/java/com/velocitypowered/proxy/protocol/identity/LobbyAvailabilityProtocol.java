package com.velocitypowered.proxy.protocol.identity;

import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import io.netty.buffer.ByteBuf;

/** Backend-only channel for lobby room availability. */
public final class LobbyAvailabilityProtocol {
  public static final String CHANNEL = "hyris:lobby_availability";
  private static final int MAGIC = 0x48594c41; // HYLA

  private LobbyAvailabilityProtocol() { }

  public static boolean isChannel(String channel) {
    return CHANNEL.equals(channel);
  }

  public static boolean handle(VelocityServerConnection source, PluginMessagePacket packet,
                               java.util.function.BiConsumer<String, Boolean> update) {
    if (!CHANNEL.equals(packet.getChannel())) return false;
    ByteBuf input = packet.content().duplicate();
    if (input.readableBytes() != 6 || input.readInt() != MAGIC || input.readUnsignedByte() != 1)
      return true;
    update.accept(source.getServer().getServerInfo().getName(), input.readBoolean());
    return true;
  }
}
