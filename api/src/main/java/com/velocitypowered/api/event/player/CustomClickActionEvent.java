/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.velocitypowered.api.event.player;

import com.google.common.base.Preconditions;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.annotation.AwaitingEvent;
import com.velocitypowered.api.proxy.Player;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.key.Key;

/** Fired when a client submits a {@code minecraft:custom} click action. Payloads are untrusted. */
@AwaitingEvent
public final class CustomClickActionEvent implements ResultedEvent<CustomClickActionEvent.ForwardResult> {
  private static final int MAX_PAYLOAD_SIZE = 65536;

  private final Player player;
  private final Key action;
  private final byte[] payload;
  private final AtomicBoolean consumed = new AtomicBoolean();
  private ForwardResult result = ForwardResult.handled();

  public CustomClickActionEvent(Player player, Key action, byte[] payload) {
    this.player = Preconditions.checkNotNull(player, "player");
    this.action = Preconditions.checkNotNull(action, "action");
    this.payload = Arrays.copyOf(Preconditions.checkNotNull(payload, "payload"), payload.length);
    Preconditions.checkArgument(payload.length <= MAX_PAYLOAD_SIZE + 1,
        "Custom click payload exceeds the maximum size of %s bytes", MAX_PAYLOAD_SIZE + 1);
  }

  public Player getPlayer() { return player; }
  public Key getAction() { return action; }
  public byte[] getPayload() { return Arrays.copyOf(payload, payload.length); }
  public CompoundBinaryTag getPayloadNbt() {
    var input = new ByteArrayInputStream(payload);
    try {
      var data = new DataInputStream(input);
      if (!data.readBoolean()) {
        if (input.available() != 0)
          throw new IllegalArgumentException("Custom action payload contains trailing bytes");
        return CompoundBinaryTag.empty();
      }
      var compound = BinaryTagIO.reader(MAX_PAYLOAD_SIZE).readNameless((java.io.DataInput) data);
      if (input.available() != 0) {
        throw new IllegalArgumentException("Custom action payload contains trailing data");
      }
      return compound;
    } catch (java.io.IOException exception) {
      throw new IllegalArgumentException("Invalid custom action NBT payload", exception);
    }
  }

  /**
   * Clears the event's private payload copy after sensitive values have been extracted.
   * This does not affect the packet that Velocity may forward to a backend.
   */
  public void clearPayload() {
    if (consumed.compareAndSet(false, true)) {
      Arrays.fill(payload, (byte) 0);
    }
  }
  @Override public ForwardResult getResult() { return result; }
  @Override public void setResult(ForwardResult result) { this.result = Preconditions.checkNotNull(result, "result"); }

  @Override
  public String toString() {
    return "CustomClickActionEvent{player=" + player.getUsername() + ", action=" + action
        + ", payload=<redacted>, result=" + result + '}';
  }

  public static final class ForwardResult implements ResultedEvent.Result {
    private static final ForwardResult HANDLED = new ForwardResult(false);
    private static final ForwardResult FORWARD = new ForwardResult(true);
    private final boolean forward;
    private ForwardResult(boolean forward) { this.forward = forward; }
    @Override public boolean isAllowed() { return forward; }
    public static ForwardResult handled() { return HANDLED; }
    public static ForwardResult forward() { return FORWARD; }
    @Override public String toString() { return forward ? "forward to backend" : "handled by proxy"; }
  }
}
