/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.velocitypowered.api.auth;

import java.util.Objects;
import java.util.UUID;

/** Read-only identity snapshot created by the proxy authentication authority. */
public record AuthIdentity(UUID playerId, UUID authenticatedUuid, String username,
                           AccountType accountType, AuthState state) {
  public AuthIdentity {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(username, "username");
    Objects.requireNonNull(accountType, "accountType");
    Objects.requireNonNull(state, "state");
  }

  public boolean authenticated() {
    return state == AuthState.AUTHENTICATED;
  }
}
