/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.velocitypowered.api.util;

import com.google.common.base.Preconditions;
import java.util.Map;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An immutable snapshot of mod and loader information advertised by a connected client.
 * Client-reported values are untrusted and should not be used as proof of client integrity.
 */
public final class ClientModInfo {
  private static final ClientModInfo EMPTY = new ClientModInfo(Map.of(), Set.of(), Set.of(), null,
      false, false);

  private final Map<String, String> mods;
  private final Set<String> loaders;
  private final Set<String> channels;
  private final @Nullable String brand;
  private final boolean lunarClient;
  private final boolean lunarScanComplete;

  public ClientModInfo(Map<String, String> mods, Set<String> loaders, Set<String> channels,
                       @Nullable String brand, boolean lunarClient, boolean lunarScanComplete) {
    this.mods = Map.copyOf(Preconditions.checkNotNull(mods, "mods"));
    this.loaders = Set.copyOf(Preconditions.checkNotNull(loaders, "loaders"));
    this.channels = Set.copyOf(Preconditions.checkNotNull(channels, "channels"));
    this.brand = brand;
    this.lunarClient = lunarClient;
    this.lunarScanComplete = lunarScanComplete;
  }

  /**
   * Returns the advertised mod IDs and versions. A blank version means the client did not provide
   * one, as is the case for some Lunar Client mod responses.
   */
  public Map<String, String> getMods() {
    return mods;
  }

  /** Returns the mod IDs from {@link #getMods()}. */
  public Set<String> getModIds() {
    return mods.keySet();
  }

  /** Returns recognized loader IDs observed in the client brand or Forge metadata. */
  public Set<String> getLoaders() {
    return loaders;
  }

  /** Returns plugin-message channels registered by the client. */
  public Set<String> getChannels() {
    return channels;
  }

  /** Returns the client brand, or {@code null} until a brand message has been received. */
  public @Nullable String getBrand() {
    return brand;
  }

  /** Returns whether Lunar Client was identified from its brand or registered channels. */
  public boolean isLunarClient() {
    return lunarClient;
  }

  /** Returns whether Velocity received a response to its Lunar installed-mods request. */
  public boolean isLunarScanComplete() {
    return lunarScanComplete;
  }

  public static ClientModInfo empty() {
    return EMPTY;
  }

  @Override
  public String toString() {
    return "ClientModInfo{" + "mods=" + mods + ", loaders=" + loaders + ", channels=" + channels
        + ", brand='" + brand + '\'' + ", lunarClient=" + lunarClient + ", lunarScanComplete="
        + lunarScanComplete + '}';
  }
}
