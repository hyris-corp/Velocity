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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LunarClientModProtocolTest {
  @Test
  void createsInstalledModsRequestWithRequestId() {
    UUID requestId = UUID.randomUUID();
    String request = new String(LunarClientModProtocol.installedModsRequest(requestId),
        StandardCharsets.UTF_8);

    assertTrue(request.contains("lunarclient.apollo.modsetting.v1.InstalledModsRequest"));
    assertTrue(request.contains(requestId.toString()));
  }

  @Test
  void parsesInstalledModsAndVersionsFromResponse() {
    UUID requestId = UUID.randomUUID();
    byte[] payload = ("{\"@type\":\"type.googleapis.com/"
        + "lunarclient.apollo.modsetting.v1.InstalledModsResponse\","
        + "\"request_id\":\"" + requestId + "\",\"mod_groups\":[{\"mods\":["
        + "{\"id\":\"MouseTweaks\"},{\"id\":\"ExampleMod\",\"version\":\"1.2\"}]}]}")
        .getBytes(StandardCharsets.UTF_8);

    var response = LunarClientModProtocol.parseInstalledModsResponse(payload).orElseThrow();
    assertEquals(requestId.toString(), response.requestId());
    assertEquals("", response.mods().get("MouseTweaks"));
    assertEquals("1.2", response.mods().get("ExampleMod"));
  }

  @Test
  void ignoresWrongTypesMalformedJsonAndInvalidIds() {
    assertTrue(LunarClientModProtocol.parseInstalledModsResponse("{}".getBytes(StandardCharsets.UTF_8))
        .isEmpty());
    assertTrue(LunarClientModProtocol.parseInstalledModsResponse("invalid".getBytes(StandardCharsets.UTF_8))
        .isEmpty());
    assertFalse(LunarClientModProtocol.isInstalledModsResponseType("unrelated"));
    assertTrue(LunarClientModProtocol.isInstalledModsResponseType(
        "lunarclient.apollo.modsetting.v1.InstalledModsResponse"));
  }
}
