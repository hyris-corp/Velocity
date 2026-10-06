/*
 * Copyright (C) 2018-2023 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.connection.client;

import static com.google.common.net.UrlEscapers.urlFormParameterEscaper;
import static com.velocitypowered.proxy.VelocityServer.GENERAL_GSON;
import static com.velocitypowered.proxy.connection.VelocityConstants.EMPTY_BYTE_ARRAY;
import static com.velocitypowered.proxy.crypto.EncryptionUtils.decryptRsa;
import static com.velocitypowered.proxy.crypto.EncryptionUtils.generateServerId;

import com.google.common.base.Preconditions;
import com.google.common.primitives.Longs;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.crypto.IdentifiedKey;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.crypto.IdentifiedKeyImpl;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.packet.EncryptionRequestPacket;
import com.velocitypowered.proxy.protocol.packet.EncryptionResponsePacket;
import com.velocitypowered.proxy.protocol.packet.LoginPluginResponsePacket;
import com.velocitypowered.proxy.protocol.packet.ServerLoginPacket;
import com.velocitypowered.proxy.util.VelocityProperties;
import io.netty.buffer.ByteBuf;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Handles authenticating the player to Mojang's servers.
 */
public class InitialLoginSessionHandler implements MinecraftSessionHandler {

  private static final Logger logger = LogManager.getLogger(InitialLoginSessionHandler.class);
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final String MOJANG_HASJOINED_URL =
      System.getProperty("mojang.sessionserver",
              "https://sessionserver.mojang.com/session/minecraft/hasJoined")
          .concat("?username=%s&serverId=%s");
  private static final String MOJANG_PROFILE_URL = System.getProperty("mojang.profileapi",
      "https://api.minecraftservices.com/minecraft/profile/lookup/name");
  private static final String MOJANG_FALLBACK_PROFILE_URL = System.getProperty("mojang.profileapi.fallback",
      "https://api.mojang.com/users/profiles/minecraft");
  private static final Semaphore MOJANG_PROFILE_LOOKUP_SLOTS = new Semaphore(32);
  private static final OfficialProfileLookupCache OFFICIAL_PROFILE_CACHE = new OfficialProfileLookupCache();
  private static final PreLoginRateLimiter PRE_LOGIN_RATE_LIMITER = new PreLoginRateLimiter();
  private static final ConcurrentHashMap<String, CompletableFuture<Boolean>> PROFILE_LOOKUPS_IN_FLIGHT =
      new ConcurrentHashMap<>();

  private final VelocityServer server;
  private final MinecraftConnection mcConnection;
  private final LoginInboundConnection inbound;
  private @MonotonicNonNull ServerLoginPacket login;
  private byte[] verify = EMPTY_BYTE_ARRAY;
  private LoginState currentState = LoginState.LOGIN_PACKET_EXPECTED;
  private final boolean forceKeyAuthentication;

  InitialLoginSessionHandler(VelocityServer server, MinecraftConnection mcConnection,
                             LoginInboundConnection inbound) {
    this.server = Preconditions.checkNotNull(server, "server");
    this.mcConnection = Preconditions.checkNotNull(mcConnection, "mcConnection");
    this.inbound = Preconditions.checkNotNull(inbound, "inbound");
    this.forceKeyAuthentication = VelocityProperties.readBoolean(
            "auth.forceSecureProfiles", server.getConfiguration().isForceKeyAuthentication());
  }

  @Override
  public boolean handle(ServerLoginPacket packet) {
    assertState(LoginState.LOGIN_PACKET_EXPECTED);
    this.currentState = LoginState.LOGIN_PACKET_RECEIVED;
    IdentifiedKey playerKey = packet.getPlayerKey();
    if (playerKey != null) {
      if (playerKey.hasExpired()) {
        inbound.disconnect(
            Component.translatable("multiplayer.disconnect.invalid_public_key_signature"));
        return true;
      }

      boolean isKeyValid;
      if (playerKey.getKeyRevision() == IdentifiedKey.Revision.LINKED_V2
          && playerKey instanceof final IdentifiedKeyImpl keyImpl) {
        isKeyValid = keyImpl.internalAddHolder(packet.getHolderUuid());
      } else {
        isKeyValid = playerKey.isSignatureValid();
      }

      if (!isKeyValid) {
        inbound.disconnect(Component.translatable("multiplayer.disconnect.invalid_public_key"));
        return true;
      }
    } else if (mcConnection.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_19)
        && forceKeyAuthentication
        && mcConnection.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_19_3)) {
      inbound.disconnect(Component.translatable("multiplayer.disconnect.missing_public_key"));
      return true;
    }
    inbound.setPlayerKey(playerKey);
    this.login = packet;

    String clientIp = mcConnection.getRemoteAddress() instanceof InetSocketAddress address
            ? address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress()
            : String.valueOf(mcConnection.getRemoteAddress());
    if (clientIp == null || clientIp.isBlank())
      clientIp = "unknown";
    if (!PRE_LOGIN_RATE_LIMITER.allow(login.getUsername(), clientIp, System.currentTimeMillis())) {
      inbound.disconnect(Component.text("Muitas tentativas de conexão. Aguarde um pouco e tente novamente.",
          NamedTextColor.RED));
      return true;
    }

    final PreLoginEvent event = new PreLoginEvent(inbound, login.getUsername(), login.getHolderUuid());
    server.getEventManager().fire(event).thenRunAsync(() -> {
      if (mcConnection.isClosed()) {
        // The player was disconnected
        return;
      }

      PreLoginComponentResult result = event.getResult();
      Optional<Component> disconnectReason = result.getReasonComponent();
      if (disconnectReason.isPresent()) {
        // The component is guaranteed to be provided if the connection was denied.
        inbound.disconnect(disconnectReason.get());
        return;
      }

        inbound.loginEventFired(() -> {
          if (mcConnection.isClosed()) {
            return;
          }
          if (!shouldLookupOfficialProfile(server.getConfiguration().isOnlineMode(), result)) {
            continueLogin(result);
            return;
          }

          // In mixed mode, profile existence only selects the required challenge. It is never
          // accepted as proof; a found profile must still pass Velocity's native hasJoined flow.
          hasOfficialProfile(login.getUsername()).whenComplete((exists, failure) -> {
            if (mcConnection.isClosed()) {
              return;
            }
            if (failure != null) {
              logger.warn("Unable to resolve official profile for {}", login.getUsername(), failure);
              mcConnection.eventLoop().execute(() -> inbound.disconnect(
                  Component.translatable("hyris.error.authservers-down")));
              return;
            }

            PreLoginComponentResult selectedMode = selectMixedMode(exists);
            if (result.isForceOfflineMode() && exists) {
              logger.warn("Ignoring forced offline mode for reserved Minecraft profile {}",
                  login.getUsername());
            }
            logger.info("Selected {} login challenge for {} from official profile lookup; profile existence is not authentication",
                exists ? "PREMIUM" : "OFFLINE", login.getUsername());
            continueLogin(selectedMode);
          });
        });
    }, mcConnection.eventLoop()).exceptionally((ex) -> {
      logger.error("Exception in pre-login stage", ex);
      return null;
    });

    return true;
  }

  static PreLoginComponentResult selectMixedMode(boolean officialProfileExists) {
    return officialProfileExists ? PreLoginComponentResult.forceOnlineMode()
        : PreLoginComponentResult.forceOfflineMode();
  }

  static boolean shouldLookupOfficialProfile(boolean proxyOnlineMode, PreLoginComponentResult result) {
    return !proxyOnlineMode && !result.isOnlineModeAllowed() && !result.isForceOfflineMode();
  }

  private java.util.concurrent.CompletableFuture<Boolean> hasOfficialProfile(String username) {
    long now = System.currentTimeMillis();
    Boolean cached = OFFICIAL_PROFILE_CACHE.cached(username, now);
    if (cached != null)
      return CompletableFuture.completedFuture(cached);

    String key = username.trim().toLowerCase(Locale.ROOT);
    CompletableFuture<Boolean> lookup = new CompletableFuture<>();
    CompletableFuture<Boolean> existing = PROFILE_LOOKUPS_IN_FLIGHT.putIfAbsent(key, lookup);
    if (existing != null)
      return existing;
    if (!OFFICIAL_PROFILE_CACHE.mayQuery(now)) {
      PROFILE_LOOKUPS_IN_FLIGHT.remove(key, lookup);
      return CompletableFuture.failedFuture(new IllegalStateException("Mojang profile lookup circuit is open"));
    }
    fetchOfficialProfile(username).whenComplete((exists, failure) -> {
      PROFILE_LOOKUPS_IN_FLIGHT.remove(key, lookup);
      if (failure == null) {
        OFFICIAL_PROFILE_CACHE.succeeded(username, exists, System.currentTimeMillis());
        lookup.complete(exists);
      } else {
        OFFICIAL_PROFILE_CACHE.failed(System.currentTimeMillis());
        lookup.completeExceptionally(failure);
      }
    });
    return lookup;
  }

  private java.util.concurrent.CompletableFuture<Boolean> fetchOfficialProfile(String username) {
    if (!MOJANG_PROFILE_LOOKUP_SLOTS.tryAcquire()) {
      return java.util.concurrent.CompletableFuture.failedFuture(
          new IllegalStateException("Mojang profile lookup capacity is exhausted"));
    }
    final HttpClient client;
    try {
      client = server.createHttpClient();
    } catch (RuntimeException exception) {
      MOJANG_PROFILE_LOOKUP_SLOTS.release();
      return java.util.concurrent.CompletableFuture.failedFuture(exception);
    }
    final HttpRequest request;
    try {
      request = profileRequest(MOJANG_PROFILE_URL, username);
    } catch (RuntimeException exception) {
      client.close();
      MOJANG_PROFILE_LOOKUP_SLOTS.release();
      return java.util.concurrent.CompletableFuture.failedFuture(exception);
    }
    try {
      return client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
          .thenCompose(response -> {
            if (shouldFallbackProfileLookup(response.statusCode())) {
              final HttpRequest fallbackRequest;
              try {
                fallbackRequest = profileRequest(MOJANG_FALLBACK_PROFILE_URL, username);
              } catch (RuntimeException exception) {
                return CompletableFuture.failedFuture(exception);
              }
              return sendProfileFallback(client, fallbackRequest);
            }
            return profileExistsResult(response.statusCode());
          })
          .whenComplete((ignored, failure) -> {
            try {
              client.close();
            } finally {
              MOJANG_PROFILE_LOOKUP_SLOTS.release();
            }
          });
    } catch (RuntimeException exception) {
      try {
        client.close();
      } finally {
        MOJANG_PROFILE_LOOKUP_SLOTS.release();
      }
      return java.util.concurrent.CompletableFuture.failedFuture(exception);
    }
  }

  private HttpRequest profileRequest(String baseUrl, String username) {
    return HttpRequest.newBuilder(URI.create(baseUrl + "/" + urlFormParameterEscaper().escape(username)))
        .timeout(Duration.ofSeconds(5))
        .header("User-Agent", server.getVersion().getName() + "/" + server.getVersion().getVersion())
        .GET()
        .build();
  }

  private CompletableFuture<Boolean> sendProfileFallback(HttpClient client, HttpRequest request) {
    return client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
        .thenCompose(response -> {
          if (shouldRetryProfileStatus(response.statusCode()))
            return retryProfileFallback(client, request, 2);
          return profileExistsResult(response.statusCode());
        });
  }

  private CompletableFuture<Boolean> retryProfileFallback(HttpClient client, HttpRequest request, int retriesLeft) {
    if (retriesLeft <= 0)
      return CompletableFuture.failedFuture(new IllegalStateException("Profile APIs remain unavailable"));
    final long delay = retriesLeft == 2 ? 250 : 750;
    return CompletableFuture.runAsync(() -> { }, CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS))
        .thenCompose(ignored -> client.sendAsync(request, HttpResponse.BodyHandlers.discarding()))
        .thenCompose(response -> {
          if (shouldRetryProfileStatus(response.statusCode()))
            return retryProfileFallback(client, request, retriesLeft - 1);
          return profileExistsResult(response.statusCode());
        });
  }

  private static CompletableFuture<Boolean> profileExistsResult(int statusCode) {
    try {
      return CompletableFuture.completedFuture(officialProfileExistsForStatus(statusCode));
    } catch (IllegalStateException exception) {
      return CompletableFuture.failedFuture(exception);
    }
  }

  static boolean shouldFallbackProfileLookup(int statusCode) {
    return shouldRetryProfileStatus(statusCode);
  }

  private static boolean shouldRetryProfileStatus(int statusCode) {
    return statusCode == 403 || statusCode == 429 || statusCode >= 500;
  }

  static boolean officialProfileExistsForStatus(int statusCode) {
    if (statusCode == 200) {
      return true;
    }
    if (statusCode == 204 || statusCode == 404) {
      return false;
    }
    throw new IllegalStateException("Unexpected Mojang profile API status " + statusCode);
  }

  private void continueLogin(PreLoginComponentResult result) {
    mcConnection.eventLoop().execute(() -> {
      if (mcConnection.isClosed()) {
        return;
      }
      if (!result.isForceOfflineMode()
          && (server.getConfiguration().isOnlineMode() || result.isOnlineModeAllowed())) {
        // Ask the client to encrypt, then validate its session with Mojang's hasJoined endpoint.
        EncryptionRequestPacket request = generateEncryptionRequest();
        this.verify = Arrays.copyOf(request.getVerifyToken(), 4);
        mcConnection.write(request);
        this.currentState = LoginState.ENCRYPTION_REQUEST_SENT;
      } else {
        mcConnection.setActiveSessionHandler(StateRegistry.LOGIN,
            new AuthSessionHandler(server, inbound,
                GameProfile.forOfflinePlayer(login.getUsername()), false, null));
      }
    });
  }

  @Override
  public boolean handle(LoginPluginResponsePacket packet) {
    this.inbound.handleLoginPluginResponse(packet);
    return true;
  }

  @Override
  public boolean handle(EncryptionResponsePacket packet) {
    assertState(LoginState.ENCRYPTION_REQUEST_SENT);
    this.currentState = LoginState.ENCRYPTION_RESPONSE_RECEIVED;
    ServerLoginPacket login = this.login;
    if (login == null) {
      throw new IllegalStateException("No ServerLogin packet received yet.");
    }

    if (verify.length == 0) {
      throw new IllegalStateException("No EncryptionRequest packet sent yet.");
    }

    try {
      KeyPair serverKeyPair = server.getServerKeyPair();
      if (inbound.getIdentifiedKey() != null) {
        IdentifiedKey playerKey = inbound.getIdentifiedKey();
        if (!playerKey.verifyDataSignature(packet.getVerifyToken(), verify,
            Longs.toByteArray(packet.getSalt()))) {
          throw new IllegalStateException("Invalid client public signature.");
        }
      } else {
        byte[] decryptedVerifyToken = decryptRsa(serverKeyPair, packet.getVerifyToken());
        if (!MessageDigest.isEqual(verify, decryptedVerifyToken)) {
          throw new IllegalStateException("Unable to successfully decrypt the verification token.");
        }
      }

      byte[] decryptedSharedSecret = decryptRsa(serverKeyPair, packet.getSharedSecret());

      // Go ahead and enable encryption. Once the client sends EncryptionResponse, encryption
      // is enabled.
      mcConnection.enableEncryption(decryptedSharedSecret);

      String serverId = generateServerId(decryptedSharedSecret, serverKeyPair.getPublic());
      String playerIp = ((InetSocketAddress) mcConnection.getRemoteAddress()).getHostString();
      String url = String.format(MOJANG_HASJOINED_URL,
          urlFormParameterEscaper().escape(login.getUsername()), serverId);

      if (server.getConfiguration().shouldPreventClientProxyConnections()) {
        url += "&ip=" + urlFormParameterEscaper().escape(playerIp);
      }

      final HttpRequest httpRequest = HttpRequest.newBuilder()
              .setHeader("User-Agent",
                      server.getVersion().getName() + "/" + server.getVersion().getVersion())
              .uri(URI.create(url))
              .build();
      //noinspection resource
      final HttpClient httpClient = server.createHttpClient();
      sendHasJoinedWithRetry(httpClient, httpRequest, 3)
          .whenCompleteAsync((response, throwable) -> {
            if (mcConnection.isClosed()) {
              // The player disconnected after we authenticated them.
              return;
            }

            if (throwable != null) {
              logger.error("Unable to authenticate player", throwable);
              inbound.disconnect(Component.translatable("hyris.error.authservers-down"));
              return;
            }

            if (response.statusCode() == 200) {
              final GameProfile profile = GENERAL_GSON.fromJson(response.body(),
                  GameProfile.class);
              // Not so fast, now we verify the public key for 1.19.1+
              if (inbound.getIdentifiedKey() != null
                  && inbound.getIdentifiedKey().getKeyRevision() == IdentifiedKey.Revision.LINKED_V2
                  && inbound.getIdentifiedKey() instanceof final IdentifiedKeyImpl key) {
                if (!key.internalAddHolder(profile.getId())) {
                  inbound.disconnect(
                      Component.translatable("multiplayer.disconnect.invalid_public_key"));
                }
              }
              // All went well, initialize the session.
              mcConnection.setActiveSessionHandler(StateRegistry.LOGIN,
                  new AuthSessionHandler(server, inbound, profile, true, serverId));
            } else if (response.statusCode() == 204) {
              // Apparently an offline-mode user logged onto this online-mode proxy.
              inbound.disconnect(
                  Component.translatable("velocity.error.online-mode-only", NamedTextColor.RED));
            } else {
              // Something else went wrong
              logger.error(
                  "Got an unexpected error code {} whilst contacting Mojang to log in {} ({})",
                  response.statusCode(), login.getUsername(), playerIp);
              if (response.statusCode() == 403) {
                inbound.disconnect(Component.translatable("velocity.error.online-mode-only"));
              } else {
                inbound.disconnect(Component.translatable("hyris.error.authservers-down-status",
                    Component.text(response.statusCode())));
              }
            }
          }, mcConnection.eventLoop())
          .whenComplete((ignored, throwable) -> {
            httpClient.close();
          });
    } catch (GeneralSecurityException e) {
      logger.error("Unable to enable encryption", e);
      mcConnection.close(true);
    }
    return true;
  }

  private CompletableFuture<HttpResponse<String>> sendHasJoinedWithRetry(HttpClient client,
                                                                          HttpRequest request,
                                                                          int attemptsLeft) {
    return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
      if (!shouldRetryProfileStatus(response.statusCode()) || attemptsLeft <= 1)
        return CompletableFuture.completedFuture(response);
      final long delay = attemptsLeft == 3 ? 250 : 750;
      return CompletableFuture.runAsync(() -> { }, CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS))
          .thenCompose(ignored -> sendHasJoinedWithRetry(client, request, attemptsLeft - 1));
    });
  }

  private EncryptionRequestPacket generateEncryptionRequest() {
    byte[] verify = new byte[4];
    SECURE_RANDOM.nextBytes(verify);

    EncryptionRequestPacket request = new EncryptionRequestPacket();
    request.setPublicKey(server.getServerKeyPair().getPublic().getEncoded());
    request.setVerifyToken(verify);
    return request;
  }

  @Override
  public void handleUnknown(ByteBuf buf) {
    mcConnection.close(true);
  }

  @Override
  public void disconnected() {
    this.inbound.cleanup();
  }

  private void assertState(LoginState expectedState) {
    if (this.currentState != expectedState) {
      if (MinecraftDecoder.DEBUG) {
        logger.error("{} Received an unexpected packet requiring state {}, but we are in {}",
            inbound,
            expectedState, this.currentState);
      }
      mcConnection.close(true);
    }
  }

  private enum LoginState {
    LOGIN_PACKET_EXPECTED,
    LOGIN_PACKET_RECEIVED,
    ENCRYPTION_REQUEST_SENT,
    ENCRYPTION_RESPONSE_RECEIVED
  }
}
