/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.velocitypowered.proxy.connection.client;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class OfficialProfileLookupCache {
  private static final long POSITIVE_TTL_MILLIS = 10 * 60 * 1000L;
  private static final long NEGATIVE_TTL_MILLIS = 60 * 1000L;
  private static final long CIRCUIT_OPEN_MILLIS = 15 * 1000L;
  private static final int FAILURE_THRESHOLD = 5;
  private static final int MAXIMUM_ENTRIES = 10_000;

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  private final AtomicBoolean halfOpenProbe = new AtomicBoolean();
  private int consecutiveFailures;
  private long circuitOpenUntilMillis;

  Boolean cached(String username, long nowMillis) {
    String key = key(username);
    Entry entry = entries.get(key);
    if (entry == null)
      return null;
    if (entry.expiresAtMillis() <= nowMillis) {
      entries.remove(key, entry);
      return null;
    }
    return entry.exists();
  }

  synchronized boolean mayQuery(long nowMillis) {
    if (circuitOpenUntilMillis == 0)
      return true;
    if (nowMillis < circuitOpenUntilMillis)
      return false;
    return halfOpenProbe.compareAndSet(false, true);
  }

  synchronized void succeeded(String username, boolean exists, long nowMillis) {
    long ttl = exists ? POSITIVE_TTL_MILLIS : NEGATIVE_TTL_MILLIS;
    entries.put(key(username), new Entry(exists, nowMillis + ttl));
    if (entries.size() > MAXIMUM_ENTRIES)
      entries.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= nowMillis);
    if (entries.size() > MAXIMUM_ENTRIES)
      entries.keySet().stream().findAny().ifPresent(entries::remove);
    consecutiveFailures = 0;
    circuitOpenUntilMillis = 0;
    halfOpenProbe.set(false);
  }

  synchronized void failed(long nowMillis) {
    halfOpenProbe.set(false);
    consecutiveFailures++;
    if (consecutiveFailures >= FAILURE_THRESHOLD)
      circuitOpenUntilMillis = nowMillis + CIRCUIT_OPEN_MILLIS;
  }

  synchronized long circuitOpenUntilMillis() {
    return circuitOpenUntilMillis;
  }

  private static String key(String username) {
    return username.trim().toLowerCase(Locale.ROOT);
  }

  private record Entry(boolean exists, long expiresAtMillis) {
  }
}
