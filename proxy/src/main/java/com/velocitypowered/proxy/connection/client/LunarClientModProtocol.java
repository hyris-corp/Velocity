/*
 * Copyright (C) 2026 Velocity Contributors
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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class LunarClientModProtocol {
  private static final String TYPE_PREFIX = "type.googleapis.com/";
  private static final String REQUEST_TYPE =
      TYPE_PREFIX + "lunarclient.apollo.modsetting.v1.InstalledModsRequest";
  private static final String RESPONSE_TYPE =
      TYPE_PREFIX + "lunarclient.apollo.modsetting.v1.InstalledModsResponse";
  private static final int MAX_MODS = 4096;

  private LunarClientModProtocol() {
  }

  static byte[] installedModsRequest(UUID requestId) {
    JsonObject request = new JsonObject();
    request.addProperty("@type", REQUEST_TYPE);
    request.addProperty("request_id", requestId.toString());
    return request.toString().getBytes(StandardCharsets.UTF_8);
  }

  static Optional<Response> parseInstalledModsResponse(byte[] payload) {
    try {
      JsonObject response = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8))
          .getAsJsonObject();
      String type = response.has("@type") && response.get("@type").isJsonPrimitive()
          ? response.get("@type").getAsString() : "";
      if (!isInstalledModsResponseType(type)) {
        return Optional.empty();
      }

      String requestId = response.has("request_id") && response.get("request_id").isJsonPrimitive()
          ? response.get("request_id").getAsString() : null;
      Map<String, String> mods = parseMods(response.getAsJsonArray("mod_groups"));
      return Optional.of(new Response(requestId, mods));
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }

  static boolean isInstalledModsResponseType(String type) {
    return RESPONSE_TYPE.equals(type) || RESPONSE_TYPE.substring(TYPE_PREFIX.length()).equals(type);
  }

  private static Map<String, String> parseMods(JsonArray groups) {
    if (groups == null) {
      return Map.of();
    }
    Map<String, String> mods = new LinkedHashMap<>();
    int count = 0;
    for (var groupElement : groups) {
      if (!groupElement.isJsonObject()
          || !groupElement.getAsJsonObject().has("mods")
          || !groupElement.getAsJsonObject().get("mods").isJsonArray()) {
        continue;
      }
      JsonArray entries = groupElement.getAsJsonObject().getAsJsonArray("mods");
      for (var modElement : entries) {
        if (++count > MAX_MODS) {
          return Collections.unmodifiableMap(mods);
        }
        if (!modElement.isJsonObject()) {
          continue;
        }
        JsonObject mod = modElement.getAsJsonObject();
        if (!mod.has("id") || !mod.get("id").isJsonPrimitive()) {
          continue;
        }
        String id = mod.get("id").getAsString();
        if (id.isBlank() || id.length() > 128) {
          continue;
        }
        String version = mod.has("version") && mod.get("version").isJsonPrimitive()
            ? mod.get("version").getAsString() : "";
        mods.put(id, version.length() > 128 ? version.substring(0, 128) : version);
      }
    }
    return Collections.unmodifiableMap(mods);
  }

  record Response(String requestId, Map<String, String> mods) {
    Response {
      mods = Map.copyOf(mods);
    }
  }
}
