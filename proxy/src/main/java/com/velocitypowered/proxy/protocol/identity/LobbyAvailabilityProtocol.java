package com.velocitypowered.proxy.protocol.identity;

import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import io.netty.buffer.ByteBuf;

/** Backend-only channel for lobby room availability. */
public final class LobbyAvailabilityProtocol {
  public static final String CHANNEL = "hyris:lobby_availability";
  private static final int MAGIC = 0x48594c41; // HYLA
  public static final int REQUEST = 0;
  public static final int RESPONSE = 1;

  private LobbyAvailabilityProtocol() { }

  public static boolean isChannel(String channel) {
    return CHANNEL.equals(channel);
  }

  public static boolean handle(VelocityServerConnection source, PluginMessagePacket packet,
                               java.util.function.BiConsumer<String, Boolean> update,
                               java.util.function.BiConsumer<String, Boolean> responseHandler) {
    if (!CHANNEL.equals(packet.getChannel())) return false;
    ByteBuf input = packet.content().duplicate();
    if (input.readableBytes() != 6 || input.readInt() != MAGIC)
      return true;
    int type = input.readUnsignedByte();
    if (type == REQUEST) {
      source.sendPluginMessage(com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
          .from(CHANNEL), response(false));
    } else if (type == RESPONSE) {
      boolean available = input.readBoolean();
      update.accept(source.getServer().getServerInfo().getName(), available);
      responseHandler.accept(source.getServer().getServerInfo().getName(), available);
    }
    return true;
  }

  public static byte[] request() {
    return message(REQUEST, false);
  }

  public static byte[] response(boolean available) {
    return message(RESPONSE, available);
  }

  private static byte[] message(int type, boolean available) {
    return java.nio.ByteBuffer.allocate(6).putInt(MAGIC).put((byte) type)
        .put((byte) (available ? 1 : 0)).array();
  }
}
