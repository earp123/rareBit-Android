# rareBit Android — Changelog

## Current State

### Working

**BLE Scanning**
- Hardware-level scan filters (OR): Config service UUID + exact device names.
  Bench finding 2026-09-02: only the Relay advertises the CFG UUID —
  Flag/Receiver firmware advertises no service UUIDs, so exact-name filters
  cover them until firmware adds the UUID (same TODO as iOS)
- Detects device type (FLAG / RECEIVER / RELAY / UNKNOWN) by exact advertised
  name, mirroring iOS `RareBitDeviceType` ("rareBit PRO Flag", "rareBit PRO
  Receiver", "rareBit Relay")
- Sorts device list by name, updates live during scan
- Returning to scan list or pressing "Find Devices" clears disconnected devices; connected cards persist until disconnect or app close

**GATT Connection**
- Transport-LE forced on API 23+
- 300ms settle delay between close() and connectGatt()
- Error 133 / non-GATT_SUCCESS status routed to disconnect handler
- Guard prevents old GATT callbacks from clobbering newly-opened connections
- Sequential read queue: battery → firmware version → config characteristic

**Characteristic Parsing**
- FW char: upper nibble = major, lower nibble = minor → "major.minor"
- Config char: top 2 MSBs → 0 = LOW/Red, 1 = MID/Blue, 2 = HIGH/Cyan, 3 = FULL/Green
- Battery level: raw percentage, falls back to config interval label
- Glow color is battery-only (unknown = Yellow, matching iOS); updates show as
  the UPDATE! badge, wired to the release check

**Device Detail UI**
- Loading overlay ("Connecting…" → "Checking for updates…") before content reveals
- Title card glow color + stroke driven by connection state and glow enum
- Settings card: battery, firmware version, alert toggle, delay slider
- Info card: expandable
- DFU-only devices (SMP only, no config service): settings/info cards hidden, DFU card shown immediately

**Firmware / DFU**
- Exact release tags per device type (`PRO_FLAG_v1.9.0`, `PRO_RX_v1.8.0`,
  `RXRLY_v10.0` — same constants as iOS) with tag-prefix fallback
- Stable fetch skips prereleases: dev builds share the stable tag prefixes
  (`PRO_FLAG_v2.0.0-dev.1`) and are newest-first, so without the guard a stale
  exact-tag constant would hand customers a dev build via the prefix fallback.
  ⚠️ iOS `FirmwareService.fetchLatestRelease` has the same fallback pattern and
  no guard — port this once the dev tagging convention is settled
- Version regex handles both 2-part (`10.0`) and 3-part (`1.9.0`) tag formats
- Release info cached in-memory per session
- Update detection compares the release version against the device's FW-version
  characteristic (device is the source of truth; unreadable FWV = offer update)
- RELAY / UNKNOWN device types never fetch SMP firmware (no FLAG fallback —
  the Relay's legacy Nordic DFU flow is a separate, pending workstream)
- MCUManager `CONFIRM_ONLY` upgrade mode (iOS parity; no revert-if-unconfirmed)
- DFU progress: Downloading → Uploading → Progress % → Success / Error
- Relay-flash and restore buttons confirm via dialog before flashing
- On success: clears the device's update flag, navigates back to scan list

**Scan List**
- Pull-to-scan: drag the list down and release to run the Find Devices action;
  spinner sits over the logo and tracks real scan state

**Device Card (scan list)**
- White card / black text for pre-connect; dark card / colored glow for connected
- RSSI shown as animated ProgressBar at the card bottom, inset 10dp from sides and 8dp from bottom edge
- Signal range: -75 dBm = empty → -15 dBm = full; animates on each scan update
- 21sp device name

---

### Pending

- Relay legacy Nordic DFU flow (trigger `0xA8`, bootloader service `1530`,
  manifest + SHA-256 from public releases repo, recovery card) — see parity
  audit P3
- No retry if GitHub fetch fails mid-session (requires navigating away and back)
- "No firmware URL" error shown in DFU status text if fetch failed — informational only, no retry button
- No connect watchdog / BT-off handling; no post-DFU reconnect + version
  confirm — see parity audit P4

---

## History

### 2026-09-04 — Pull-to-scan
- Pull the device list down and release to run the same action as Find Devices
  (clears disconnected devices, checks BT/permissions, scans). Adds
  `androidx.swiperefreshlayout:1.1.0`.
- Spinner is offset up into the logo area (`setProgressViewOffset`, negative dp;
  root `clipChildren=false`) rather than sitting at the list edge.
- Spinner tracks real scan state, so it also shows for button-started scans and
  clears when the 10s window closes; BT-off / permission paths drop it
  immediately instead of spinning forever.

### 2026-09-04 — Connect-time config re-apply (PM decision)
Safety net for units power-cycled before firmware persistence reaches them
(firmware side: `rareBit-Flags-Receivers/docs/config-persistence-in-slot.md`,
settings pages inside slot 0).

- User-set CFG bits 0–5 cached per device address (SharedPreferences
  `device_config`); bits 6–7 are device-owned battery and never cached.
- On connect, after the CCC descriptor write lands (GATT idle), the cached
  bits are compared against the device's reported byte and written back only
  when they differ — a no-op once firmware persistence works, and on devices
  this phone never configured.
- Falls back to an immediate re-apply if the CFG characteristic exposes no CCC
  descriptor (no `onDescriptorWrite` to ride).
- `BleCfg` logs `REAPPLY(...)` with device/cached/restored bytes for bench work.

### 2026-09-04 — Config persistence findings (bench, Flag v2.0.0-dev.1)
Config values reset on Flag power cycles. Diagnosed with new `BleCfg` logcat
tracing (kept in the app): writes succeed (GATT status 0) and the Flag echoes
the new byte (0xE9) via notify, but after a power cycle the initial CFG read
is factory default (0x00, then 0xC0 once battery bits apply). **The app reads
accurately — the device loses the value.**

Root cause (firmware, `common/src/config_svc.c` + `settings_guard.c` on
`development`): the settings region (0xa000–0xc000, carved from MCUboot
padding) is fprotect-locked by fielded factory bootloaders, so
`cfg_persist_enabled=false` and the config byte stays volatile; `sys_poweroff`
then wipes it. The firmware's sanctioned fallback assumes *"the mobile app
re-writes it on every connect"* — neither the Android nor the iOS app
implements that today.

Suggestions for the PM re-investigation:
1. Field persistence needs firmware storage the fielded bootloader doesn't
   lock — e.g. a settings page in app-flash territory (DFU proves it's
   writable; pick a page firmware swaps don't erase), or a UICR customer-word
   journal (~30 writes lifetime, fine for set-once prefs).
2. Either way, both apps should implement the connect-time re-apply (cache
   last user-set bits 0–5 per device, write only when the device's byte
   differs — the existing no-op write guard makes it free on persisting
   units). Covers the already-fielded volatile cohort.
3. iOS also still needs the prerelease guard on its stable firmware fetch
   (see 2026-09-04 entry below).

### 2026-09-04 — Prerelease guard on the stable firmware channel
- Bench-validated the dev channel: hidden dev card fetched
  `PRO_FLAG_v2.0.0-dev.1` from the `development` branch and flashed a Flag to
  v2.0 over SMP successfully
- Guarded `findRelease` (stable channel) against prereleases — see the
  Firmware / DFU note above; iOS needs the matching guard

### 2026-09-02 — P2 config writes, CFG scan filter, UI unification
- Config writes wired: Short Press Alert toggle and delay slider write the CFG
  byte with iOS invariants (device-reported base byte required, only target
  bits modified, no-op writes skipped, optimistic UI update)
- Full CFG byte parsed (enable bit0, delay bits5–2, battery bits7–6); controls
  populate from device state; delay slider is the raw GATT field 0–15 shown as
  ms ×20, Flag-only (iOS parity)
- CFG notifications enabled once the initial read queue drains — battery and
  config changes now stream live
- Scan filter moved to the Config service UUID at the scanner level
- UI: device cards pill-shaped (26dp radius + matching glow), detail cards
  unified at 20dp, Material3 switch, slimmer slider (no ticks/tooltip),
  stroke width density-correct, new Relay icon from design PNG
- Receiver title card states its firmware personality on connect — "Receiver
  firmware v1.x" (gray) vs "Relay firmware v10.x" (orange); relay/restore
  cards use a new radio-waves glyph instead of the Relay device icon
- Hidden developer card (10s hold on the detail title card, no visual cue):
  force-show the DFU card, or fetch a release cut against the firmware repo's
  `development` branch (matched by target_commitish + tag prefix; returns
  "not yet" gracefully until one exists) and arm it on the DFU button

### 2026-08-27 — P1 parity fixes (audit sync)
- Device typing by exact advertised name; added RELAY type with its own icon
- Update check now compares the device FW-version characteristic against the
  release (dropped SharedPreferences installed-version tracking)
- Exact release tags with prefix fallback; removed the UNKNOWN→FLAG firmware
  fallback (wrong-firmware risk for unrecognized devices)
- SMP DFU switched `TEST_AND_CONFIRM` → `CONFIRM_ONLY` (iOS parity; removes
  the revert-if-not-reconnected failure mode and the second reboot)
- Fixed read-queue corruption: write/descriptor callbacks no longer pop the
  auto-read queue
- Glow state: unknown now Yellow (was Green); update state moved from glow to
  the UPDATE! badge, which is now actually wired
- Confirmation dialogs on Relay-flash and Restore buttons (consequence copy
  matches iOS)
