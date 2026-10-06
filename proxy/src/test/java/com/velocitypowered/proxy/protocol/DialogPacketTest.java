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

package com.velocitypowered.proxy.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils.Direction;
import com.velocitypowered.proxy.protocol.packet.DialogClearPacket;
import com.velocitypowered.proxy.protocol.packet.DialogShowPacket;
import com.velocitypowered.proxy.protocol.packet.ServerboundCustomClickActionPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;

class DialogPacketTest {
  private static final ProtocolVersion TARGET = ProtocolVersion.MINECRAFT_1_21_11;

  @Test
  void targetPacketIdsAreRegisteredInTheirCorrectStatesAndDirections() {
    assertEquals(0x08, StateRegistry.CONFIG.getProtocolRegistry(Direction.SERVERBOUND, TARGET)
        .getPacketId(new ServerboundCustomClickActionPacket()));
    assertEquals(0x41, StateRegistry.PLAY.getProtocolRegistry(Direction.SERVERBOUND, TARGET)
        .getPacketId(new ServerboundCustomClickActionPacket()));
    assertEquals(0x12, StateRegistry.CONFIG.getProtocolRegistry(Direction.CLIENTBOUND, TARGET)
        .getPacketId(new DialogShowPacket(StateRegistry.CONFIG)));
    assertEquals(0x11, StateRegistry.CONFIG.getProtocolRegistry(Direction.CLIENTBOUND, TARGET)
        .getPacketId(DialogClearPacket.INSTANCE));
    assertEquals(0x8A, StateRegistry.PLAY.getProtocolRegistry(Direction.CLIENTBOUND, TARGET)
        .getPacketId(new DialogShowPacket(StateRegistry.PLAY)));
    assertEquals(0x89, StateRegistry.PLAY.getProtocolRegistry(Direction.CLIENTBOUND, TARGET)
        .getPacketId(DialogClearPacket.INSTANCE));
  }

  @Test
  void inlineDialogEncodingUsesNbtInConfigAndInlineHolderInPlay() {
    var definition = CompoundBinaryTag.builder()
        .putString("type", "minecraft:notice")
        .put("title", CompoundBinaryTag.builder().putString("text", "Autenticação").build())
        .build();
    for (var state : new StateRegistry[]{StateRegistry.CONFIG, StateRegistry.PLAY}) {
      ByteBuf expected = Unpooled.buffer();
      ByteBuf actual = Unpooled.buffer();
      if (state == StateRegistry.PLAY)
        ProtocolUtils.writeVarInt(expected, 0); // inline holder, not a dialog registry ID
      ProtocolUtils.writeBinaryTag(expected, TARGET, definition);
      new DialogShowPacket(state, definition).encode(actual, Direction.CLIENTBOUND, TARGET);
      assertArrayEquals(io.netty.buffer.ByteBufUtil.getBytes(expected), io.netty.buffer.ByteBufUtil.getBytes(actual));
      expected.release();
      actual.release();
    }
  }
}
