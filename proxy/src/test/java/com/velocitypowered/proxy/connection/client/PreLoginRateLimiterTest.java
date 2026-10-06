/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.velocitypowered.proxy.connection.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PreLoginRateLimiterTest {
  @Test
  void appliesAnIpWindowAcrossDifferentUsernames() {
    var limiter = new PreLoginRateLimiter();
    for (int attempt = 0; attempt < 120; attempt++)
      assertTrue(limiter.allow("player" + attempt, "198.51.100.2", 1_000));

    assertFalse(limiter.allow("another-player", "198.51.100.2", 1_001));
    assertTrue(limiter.allow("another-player", "198.51.100.2", 11_000));
  }

  @Test
  void appliesAUsernameWindowAcrossDifferentIpsCaseInsensitively() {
    var limiter = new PreLoginRateLimiter();
    for (int attempt = 0; attempt < 8; attempt++)
      assertTrue(limiter.allow("KnownName", "198.51.100." + attempt, 1_000));

    assertFalse(limiter.allow("knownname", "203.0.113.1", 1_001));
    assertTrue(limiter.allow("KNOWNNAME", "203.0.113.1", 61_000));
  }
}
