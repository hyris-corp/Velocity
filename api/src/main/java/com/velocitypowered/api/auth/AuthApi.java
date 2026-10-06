/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.velocitypowered.api.auth;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only API contract exposed by a proxy authentication plugin. This API does not itself
 * authenticate clients; it exposes the authority's result to dependent plugins.
 */
public interface AuthApi {
  Optional<AuthIdentity> findSession(UUID playerId);

  /** Creates a read-only view backed by the active proxy session. */
  static AuthApi fromProxy(ProxyServer proxy) {
    return playerId -> proxy.getPlayer(playerId).map(Player::getAuthIdentity);
  }

  default boolean isAuthenticated(UUID playerId) {
    return findSession(playerId).map(AuthIdentity::authenticated).orElse(false);
  }

  default AccountType getAccountType(UUID playerId) {
    return findSession(playerId).map(AuthIdentity::accountType).orElse(AccountType.UNKNOWN);
  }

  default boolean isPremium(UUID playerId) {
    return isAuthenticated(playerId) && getAccountType(playerId) == AccountType.PREMIUM;
  }

  /**
   * Obtains a registered implementation from a plugin instance without exposing its internal
   * session maps. The consumer plugin should declare a dependency on the provider plugin.
   */
  static Optional<AuthApi> find(ProxyServer proxy, String providerPluginId) {
    return proxy.getPluginManager().getPlugin(providerPluginId)
        .flatMap(container -> container.getInstance())
        .filter(AuthApi.class::isInstance)
        .map(AuthApi.class::cast);
  }

}
