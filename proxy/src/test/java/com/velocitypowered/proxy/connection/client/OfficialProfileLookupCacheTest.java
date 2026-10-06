/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.velocitypowered.proxy.connection.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OfficialProfileLookupCacheTest {
  @Test
  void cachesPositiveAndNegativeResultsWithDifferentLifetimes() {
    var cache = new OfficialProfileLookupCache();
    cache.succeeded("PremiumName", true, 1_000);
    cache.succeeded("OfflineName", false, 1_000);

    assertEquals(Boolean.TRUE, cache.cached("premiumname", 600_999));
    assertEquals(Boolean.FALSE, cache.cached("OFFLINENAME", 60_999));
    assertNull(cache.cached("OfflineName", 61_000));
    assertNull(cache.cached("PremiumName", 601_000));
  }

  @Test
  void opensCircuitAfterRepeatedFailuresAndAllowsSingleProbeAfterCooldown() {
    var cache = new OfficialProfileLookupCache();
    for (int failure = 0; failure < 5; failure++)
      cache.failed(1_000 + failure);

    assertEquals(16_004, cache.circuitOpenUntilMillis());
    assertFalse(cache.mayQuery(16_003));
    assertTrue(cache.mayQuery(16_004));
    assertFalse(cache.mayQuery(16_005));

    cache.succeeded("Recovered", true, 16_006);
    assertEquals(0, cache.circuitOpenUntilMillis());
    assertTrue(cache.mayQuery(16_007));
  }
}
