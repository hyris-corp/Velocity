/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.velocitypowered.api.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClientModInfoTest {
  @Test
  void snapshotCopiesCollectionsAndExposesImmutableValues() {
    Map<String, String> mods = new HashMap<>(Map.of("example", "1.0"));
    Set<String> loaders = new HashSet<>(Set.of("fabric"));
    Set<String> channels = new HashSet<>(Set.of("example:network"));
    ClientModInfo info = new ClientModInfo(mods, loaders, channels, "fabric", false, false);

    mods.put("later", "2.0");
    loaders.add("forge");
    channels.clear();

    assertEquals(Map.of("example", "1.0"), info.getMods());
    assertEquals(Set.of("fabric"), info.getLoaders());
    assertEquals(Set.of("example:network"), info.getChannels());
    assertThrows(UnsupportedOperationException.class, () -> info.getMods().put("other", ""));
  }
}
