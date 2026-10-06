/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.velocitypowered.proxy.connection.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class PreLoginRateLimiter {
  private static final int IP_LIMIT = 120;
  private static final long IP_WINDOW_MILLIS = 10_000;
  private static final int USERNAME_LIMIT = 8;
  private static final long USERNAME_WINDOW_MILLIS = 60_000;

  private final Cache<String, Window> ipWindows = Caffeine.newBuilder()
      .expireAfterAccess(2, TimeUnit.MINUTES)
      .maximumSize(100_000)
      .build();
  private final Cache<String, Window> usernameWindows = Caffeine.newBuilder()
      .expireAfterAccess(2, TimeUnit.MINUTES)
      .maximumSize(100_000)
      .build();

  boolean allow(String username, String ipAddress, long nowMillis) {
    boolean allowedByIp = ipWindows.get(ipAddress, ignored -> new Window())
        .allow(nowMillis, IP_WINDOW_MILLIS, IP_LIMIT);
    boolean allowedByName = usernameWindows.get(username.trim().toLowerCase(Locale.ROOT),
            ignored -> new Window())
        .allow(nowMillis, USERNAME_WINDOW_MILLIS, USERNAME_LIMIT);
    return allowedByIp && allowedByName;
  }

  private static final class Window {
    private final Deque<Long> attempts = new ArrayDeque<>();

    private synchronized boolean allow(long nowMillis, long windowMillis, int maximum) {
      while (!attempts.isEmpty() && nowMillis - attempts.peekFirst() >= windowMillis)
        attempts.removeFirst();
      if (attempts.size() >= maximum)
        return false;
      attempts.addLast(nowMillis);
      return true;
    }
  }
}
