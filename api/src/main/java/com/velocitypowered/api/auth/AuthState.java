/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.velocitypowered.api.auth;

/** State of the current authentication session for one connected player. */
public enum AuthState {
  CONNECTING,
  RESOLVING_IDENTITY,
  PREMIUM_AUTHENTICATING,
  OFFLINE_AUTHENTICATING,
  REQUIRES_REGISTRATION,
  REQUIRES_LOGIN,
  LINKING_OFFLINE_ACCOUNT,
  CREDENTIALS_VERIFIED,
  AUTHENTICATED,
  FAILED,
  CLOSED
}
