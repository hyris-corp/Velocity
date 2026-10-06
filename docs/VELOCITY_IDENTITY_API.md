# Velocity Identity API for Paper Consumers

## Channel

`hyris:identity`

Use the channel only between a backend and Velocity. Send the request after the player has joined
the backend (for example, from Paper's `PlayerJoinEvent`). Do not send it from the Minecraft client.
Velocity consumes requests arriving from clients without forwarding them.

## Wire format, version 1

All integers are big-endian. UUIDs are the 16 bytes formed by the UUID most-significant and
least-significant 64-bit values, in that order. Do not include a Minecraft plugin-message channel
prefix inside the payload; Velocity has already decoded it.

### Backend request

| Offset | Size | Value |
|---:|---:|---|
| 0 | 4 | Magic `0x48595249` (`HYRI`) |
| 4 | 1 | Protocol version `1` |
| 5 | 1 | Message type `1` (identity request) |
| 6 | 16 | The requesting player's UUID |

### Velocity response

| Offset | Size | Value |
|---:|---:|---|
| 0 | 4 | Magic `0x48595249` (`HYRI`) |
| 4 | 1 | Protocol version `1` |
| 5 | 1 | Message type `2` (identity response) |
| 6 | 1 | Status |
| 7 | 16 | Authenticated UUID, or the request UUID on an error |
| 23 | 1 | Account type |
| 24 | 1 | Authenticated flag (`0` or `1`) |

Account type values: `0 = UNKNOWN`, `1 = OFFLINE`, `2 = PREMIUM`.

Status values: `0 = OK`, `1 = authentication not complete`, `2 = malformed request, UUID mismatch,
or request from the wrong backend`, `3 = unsupported protocol version`.

For any non-OK status, Paper must deny privileged/account-sensitive operations. A not-yet-authenticated
OFFLINE session can report `OFFLINE` with `authenticated=0`; an invalid request reports `UNKNOWN`.

## Example

Request, where `uu...` stands for the requesting player's 16 UUID bytes:

```text
48 59 52 49  01  01  uu uu uu uu uu uu uu uu uu uu uu uu uu uu uu uu
```

Successful PREMIUM response:

```text
48 59 52 49  01  02  00  uu uu uu uu uu uu uu uu uu uu uu uu uu uu uu uu  02  01
```

## Trust and deployment

The UUID in a request must equal the UUID Velocity has for that player. Never accept AccountType from
a client packet or from a request UUID that differs from the connected player.

Configure Velocity modern player-info forwarding with its forwarding secret, configure Paper's
Velocity forwarding with the same secret, and firewall backend ports so clients cannot connect
directly. Velocity does not answer a request from a client or a different backend connection.
