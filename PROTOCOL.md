# Ninni BLE protocol (v3)

The **player** (old Android phone next to the baby) is a BLE peripheral running a GATT server.
The **manager** (the remote on a parent's phone) is a BLE central. Wi-Fi is not needed.

Source of truth in code: `android/protocol/src/main/java/com/ender/ninni/protocol/Protocol.kt`,
shared by both Android apps. Change the code and this file together; a manager on another
platform must follow the same rules.

## UUIDs

| What | UUID | Properties |
|---|---|---|
| Service | `8641a100-acc1-4dba-9086-98a6e53c4f74` | advertised (primary) |
| STATE | `8641a101-acc1-4dba-9086-98a6e53c4f74` | read, notify |
| COMMAND | `8641a102-acc1-4dba-9086-98a6e53c4f74` | write **with response** |
| DATA | `8641a103-acc1-4dba-9086-98a6e53c4f74` | read · v3 |

The player advertises the service UUID; the scan response carries the phone's Bluetooth name
(dropped if the name is too long). Advertising restarts after every connect/disconnect, so
several managers (e.g. an Android phone and an iPhone) can be connected at the same time.

## Connection sequence (manager)

1. Scan with a filter on the service UUID.
2. Connect. On Android request MTU 512 (iOS negotiates by itself).
3. Discover services and characteristics.
4. Subscribe to STATE notifications. The player sends the current state right away.
5. Write `auth:<pin>` to COMMAND (the 4-digit code shown on the player screen).
6. Send commands. Wait for each write response before sending the next one.

Authorization is per connection: send `auth:` again after every reconnect.

Other apps on the player phone can add or remove GATT services at any time (Spotify does after
logging in). The player then sends *Service Changed* and the manager's notification
subscription may be dropped while writes keep working. Managers rediscover and resubscribe
(Android 12+ `onServiceChanged`, iOS `didModifyServices`) and also read STATE every 15 s as a
safety net.

## STATE (UTF-8 JSON)

```json
{"pv":3,"p":1,"t":"Brahms Lullaby","a":"Baby Sleep","v":4,"m":15,"b":82,"c":1,"n":1,
 "d":215000,"o":61250,"sh":0,"rp":2,"vc":9,"sr":1799,"sf":600,
 "ar":"5f0c2a91","qi":"0be4d7c3","qa":12}
```

| Key | Meaning |
|---|---|
| `pv` | Protocol version (3; older players send 1 or 2 and none of the keys marked with a higher version) |
| `p` | 1 = playing, 0 = paused/stopped |
| `t` | Track title (missing when unknown) |
| `a` | Artist (missing when unknown) |
| `v` | Music volume step (`STREAM_MUSIC`), usually 0…15 |
| `m` | Maximum volume step on this phone |
| `b` | Battery % (-1 if unknown) |
| `c` | 1 = charging |
| `n` | 1 = notification access granted on the player (title/artist available) |
| `d` | v2 · Track length in ms (missing when unknown) |
| `o` | v2 · Playback position in ms at the moment the state was sent (missing when unknown). While `p` = 1 the manager counts on from there by itself |
| `sh` | v2 · Shuffle: 1 on, 0 off (missing when the music app does not report it) |
| `rp` | v2 · Repeat: 0 off, 1 one track, 2 all (missing when not reported) |
| `vc` | v2 · Volume limit: highest step allowed (missing = no limit) |
| `sr` | v2 · Sleep timer: seconds until the music pauses (missing = timer off). The manager counts down by itself |
| `sf` | v2 · Sleep timer: the volume fades out during the last `sf` seconds (0 = no fade) |
| `ar` | v3 · Id of the current cover art on DATA, 8 hex digits (missing = no cover) |
| `qi` | v3 · Id of the play queue on DATA (missing = the music app shares no queue) |
| `qa` | v3 · Queue item id of the track playing now (missing = unknown) |
| `x` | 1 = this notification was shortened to fit the MTU: **read STATE for the full value** |

Notifications are limited to MTU − 3 bytes. When the state does not fit, title/artist are
shortened and `x` is set; if not even that fits, the notification is just `{"x":1}`.
A read always returns the full value (long reads with offsets are supported).
The player notifies ~120 ms after any change (track, play/pause, volume, battery), and every
5 s while something counts (playing → `o`, sleep timer → `sr`).

A manager shows the volume limit and the sleep timer only when `pv` ≥ 2, and each of the
progress bar, ±15 s, shuffle and repeat only when its key is present. Cover art and the queue
need `pv` ≥ 3 and their key.

## COMMAND (UTF-8 text, case-insensitive)

| Command | Effect | Needs auth |
|---|---|---|
| `auth:1234` | Authorize this connection | – |
| `ping` | Does nothing, returns success | no |
| `play` / `pause` / `toggle` | Playback | yes |
| `next` / `prev` | Next / previous track | yes |
| `vol+` / `vol-` | One volume step up / down | yes |
| `vol:7` | Set volume step (clamped to 0…`m`, and to `vc` when set) | yes |
| `seek:93500` | v2 · Jump to this position in ms | yes |
| `fwd` / `rew` | v2 · Jump 15 s forward / back | yes |
| `shuffle:1` / `shuffle:0` | v2 · Shuffle on / off | yes |
| `repeat:0` / `1` / `2` | v2 · Repeat off / one track / all | yes |
| `cap:9` | v2 · Volume limit at step 9 (1…`m`−1; `m` or more = no limit). Lowers the volume if it is above | yes |
| `cap:off` | v2 · No volume limit | yes |
| `sleep:1800:600` | v2 · Pause the music in 1800 s, fading the volume out during the last 600 s | yes |
| `sleep:off` | v2 · Cancel the sleep timer (the volume stays where it is) | yes |
| `item:0be4d7c3:12` | v3 · Play item 12 of the queue whose id (`qi`) is `0be4d7c3` | yes |
| `get:art:0` / `get:queue:0` | v3 · Choose the DATA page to read next (0…254), see below | yes |

Volume changes never show the volume panel or beep on the player. The volume limit also holds
for the player phone's own volume keys.

Sleep timer: the fade lowers the volume in whole steps on a straight line to 0. A volume
command during the fade restarts the line from the new level. When time is up the music is
paused and, ~2 s later and only if it really stopped, the volume goes back to the level before
the fade. The player never raises the volume while music is playing.

`seek`, `fwd`/`rew`, `shuffle` and `repeat` return `0x83` when the music app does not support them.
`item` returns `0x83` when the queue has changed since the remote fetched it (`qi` differs) or
the id is not in it: music apps may renumber their queue items, e.g. Spotify after a jump or
going back. Fetch the new queue and try again. Music apps that ignore the jump get "next"
presses instead, which only reach items after the current one.
Playback commands go to the active media session (Spotify); without notification access they
are sent as media key events to the last playing app.

## DATA: cover art and queue (v3)

Bigger content is read page by page, because one attribute value is at most 512 bytes:

1. Write `get:art:0` (or `get:queue:0`) to COMMAND. Page 0 makes the player take a copy of the
   current content for this connection, so all pages of one transfer belong together.
   `0x83` = nothing to send.
2. Read DATA. The value is one page:

   | Bytes | Meaning |
   |---|---|
   | 0 | Content: 1 = cover art, 2 = queue |
   | 1 | Page index |
   | 2 | Page count (1…255) |
   | 3–6 | Content id, big-endian (the same number as `ar` / `qi`) |
   | 7… | Up to 493 bytes of content |

3. Write `get:art:1`, read DATA, and so on until the last page. Join the payloads.

The id is the CRC-32 of the whole content, so the manager can check what it joined and keep
covers it has already seen (a playlist on repeat needs each cover only once). If the content
changed between the state and the transfer, the pages carry the new id; keep the content under
that id.

- **Cover art**: a JPEG, 160 × 160 (the centre square of the music app's cover), a few kB.
- **Queue**: UTF-8 JSON, `{"t":"Ninniler","i":[[12,"Dandini dandini","Anonim"],[13,"Brahms Lullaby"]]}`:
  `t` is the queue title (may be missing), each item is `[id, title, artist]` (artist may be
  missing). At most 100 items; texts are cut at 80 characters.

## Write responses

| Code | Meaning |
|---|---|
| `0x00` | OK |
| `0x80` | Not authorized: send `auth:<pin>` first |
| `0x81` | Wrong PIN (or retried within 3 s of a wrong PIN) |
| `0x82` | Unknown command |
| `0x83` | Command failed on the player |
| `0x06` | Prepared/long writes are not supported |

After a wrong PIN every `auth:` is refused for 3 seconds. Pressing **New code** on the player
revokes all authorizations.
