/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.velocitypowered.api.event.player;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;

class CustomClickActionEventTest {
  @Test
  void payloadIsDefensivelyCopiedAndNotPrinted() {
    Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
        new Class<?>[]{Player.class}, (proxy, method, arguments) -> {
          if (method.getName().equals("getUsername")) {
            return "Tester";
          }
          throw new UnsupportedOperationException(method.getName());
        });
    byte[] payload = new byte[]{1, 2, 3};
    CustomClickActionEvent event = new CustomClickActionEvent(player, Key.key("test", "submit"), payload);

    payload[0] = 9;
    assertArrayEquals(new byte[]{1, 2, 3}, event.getPayload());
    byte[] returned = event.getPayload();
    returned[1] = 9;
    assertArrayEquals(new byte[]{1, 2, 3}, event.getPayload());
    assertFalse(event.getResult().isAllowed());
    assertFalse(event.toString().contains("[1, 2, 3]"));
    event.setResult(CustomClickActionEvent.ForwardResult.forward());
    assertTrue(event.getResult().isAllowed());
  }

  @Test
  void decodesNamelessCompoundPayloadAndRejectsTrailingBytes() throws Exception {
    var expected = CompoundBinaryTag.builder().putString("answer", "yes").build();
    var output = new ByteArrayOutputStream();
    output.write(1); // optional anonymous NBT is present.
    BinaryTagIO.writer().writeNameless(expected, output);
    var bytes = output.toByteArray();
    var player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
        new Class<?>[]{Player.class}, (proxy, method, arguments) -> "Tester");
    var event = new CustomClickActionEvent(player, Key.key("test", "submit"), bytes);
    org.junit.jupiter.api.Assertions.assertEquals(expected, event.getPayloadNbt());

    var trailing = Arrays.copyOf(bytes, bytes.length + 1);
    assertThrows(IllegalArgumentException.class,
        () -> new CustomClickActionEvent(player, Key.key("test", "submit"), trailing).getPayloadNbt());
  }

  @Test
  void parsesOptionalAnonymousCompoundNbtPayload() {
    Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
        new Class<?>[]{Player.class}, (proxy, method, arguments) -> "Tester");
    var event = new CustomClickActionEvent(player, Key.key("hyris", "auth/login"), new byte[]{1, 10, 0});
    assertEquals(CompoundBinaryTag.empty(), event.getPayloadNbt());

    var absent = new CustomClickActionEvent(player, Key.key("hyris", "noop"), new byte[]{0});
    assertEquals(CompoundBinaryTag.empty(), absent.getPayloadNbt());
  }
}
