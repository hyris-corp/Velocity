/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.velocitypowered.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthApiTest {
  @Test
  void onlyReportsPremiumWhenCurrentVelocitySessionIsAuthenticated() {
    UUID id = UUID.randomUUID();
    AuthApi premiumApi = apiFor(identity(id, AccountType.PREMIUM, AuthState.AUTHENTICATED));
    assertTrue(premiumApi.isAuthenticated(id));
    assertEquals(AccountType.PREMIUM, premiumApi.getAccountType(id));
    assertTrue(premiumApi.isPremium(id));

    AuthApi pendingOfflineApi = apiFor(identity(id, AccountType.OFFLINE, AuthState.OFFLINE_AUTHENTICATING));
    assertFalse(pendingOfflineApi.isAuthenticated(id));
    assertEquals(AccountType.OFFLINE, pendingOfflineApi.getAccountType(id));
    assertFalse(pendingOfflineApi.isPremium(id));
  }

  private static AuthApi apiFor(AuthIdentity identity) {
    Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
        new Class<?>[]{Player.class}, (instance, method, arguments) ->
            method.getName().equals("getAuthIdentity") ? identity : null);
    ProxyServer proxy = (ProxyServer) Proxy.newProxyInstance(ProxyServer.class.getClassLoader(),
        new Class<?>[]{ProxyServer.class}, (instance, method, arguments) ->
            method.getName().equals("getPlayer") ? Optional.of(player) : null);
    return AuthApi.fromProxy(proxy);
  }

  private static AuthIdentity identity(UUID id, AccountType type, AuthState state) {
    return new AuthIdentity(id, id, "TestPlayer", type, state);
  }
}
