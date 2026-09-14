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
- Battery diagnostic char (`23220005-…`, Flag/Receiver ≥ 2.0, read-only,
  9 bytes LE): `0–1` raw ADC · `2–3` mV (`-1` = read failed) · `4–5` errno ·
  `6` graded level · `7` bit0 docked / bit1 STAT high / bit2 sense fault ·
  `8` sample counter. Absent on 1.9/1.8/10.0 and the Relay — absence changes
  nothing

**Device Detail UI**
- Loading overlay ("Connecting…" → "Checking for updates…") before content reveals
- Title card glow color + stroke driven by connection state and glow enum
- Settings card: battery, firmware version, alert toggle, delay slider
- Short press delay slider (Flag only): raw CFG field 0–15 × 30 ms (0–450 ms),
  matching firmware `CFG_SHTPRS_DELAY_STEP_MS`
- Short Press Alert explanation follows the connected device: Flag = send a
  short press at all; Receiver / RXRLY / Relay = relay it as its own Alert 3
  (off → the normal Flag 1 / Flag 2 alert)
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
  the Relay has its own legacy Nordic DFU flow, below)
- MCUManager `CONFIRM_ONLY` upgrade mode (iOS parity; no revert-if-unconfirmed)
- DFU progress: Downloading → Uploading → Progress % → Success / Error
- Relay-flash and restore buttons confirm via dialog before flashing
- On success: clears the device's update flag, navigates back to scan list
- **Relay — legacy Nordic DFU** (`no.nordicsemi.android:dfu` 2.11.0), separate
  from SMP: `RelayDfuManager` downloads the release's `dfu_package`, verifies it
  against `manifest.json` `dfu_package_sha256`, writes `0xA8` to the DFU trigger
  char (`23220004-…`, docked only; ATT `0x03` → "Dock the Relay on USB power"),
  treats the disconnect as success, scans for the bootloader by service
  `00001530-…` (never name/address; 15 s timeout), then flashes the zip unchanged
  via `RelayDfuService` (PRN on, no notification). Offered only when the
  connected Relay exposes the trigger char.

  | | Stable | Development (hidden dev card) |
  |---|---|---|
  | Repo | `rareBit-firmware-releases` (public, no auth) | `rareBit-Relay` (private, PAT) |
  | Select | `relay-v*`, not prerelease, highest version | prerelease, `target_commitish == main`, `RELAY_*`, highest version then `-dev.<n>` |
  | Download | `browser_download_url` | asset API `url` + octet-stream + PAT |
  | Version gate | manifest `fw_version_byte` > device FWV | none — build number shown |

**Config re-apply (safety net)**
- Confirmed user CFG writes cache bits 0–5 per device address; on connect, if
  the device reports client bits 0x00 (factory-reset) and a cache exists, the
  value is written back. Firmware-side persistence is the primary fix — this
  only covers units power-cycled before that firmware reaches them
  (`docs/config-reapply-on-connect.md`)

**Battery display**
- Sense fault → "SENSE FAULT" (Yellow); failed read / non-zero errno →
  "UNAVAILABLE" (Yellow); otherwise the existing CFG-bits path. A faulted unit
  reports LOW forever, so this stops the app showing an untrue red glow
- Diagnostic detail line on the expandable Info card when the characteristic
  is present; never feeds CFG writes or the re-apply cache

**Scan List**
- Pull-to-scan: drag the list down and release to run the Find Devices action;
  spinner sits over the logo and tracks real scan state
- Advertisements carrying the watch-facing Relay service (`33210001-…`) are
  dropped — that service is for smartwatches only and an undocked Relay would
  otherwise match the name filter

**Device Card (scan list)**
- White card / black text for pre-connect; dark card / colored glow for connected
- RSSI shown as animated ProgressBar at the card bottom, inset 10dp from sides and 8dp from bottom edge
- Signal range: -75 dBm = empty → -15 dBm = full; animates on each scan update
- 21sp device name

---

### Pending

- Relay DFU Phase 2 (`docs/relay-dfu-flow.md`): bootloader-mode recovery card
  (an interrupted flash leaves the Relay advertising `1530` on every boot) and
  post-flash auto-reconnect + FWV == manifest byte confirmation (P4 wants the
  same for SMP)
- No retry if GitHub fetch fails mid-session (requires navigating away and back)
- "No firmware URL" error shown in DFU status text if fetch failed — informational only, no retry button
- No connect watchdog / BT-off handling; no post-DFU reconnect + version
  confirm — see parity audit P4
- Scan filter Phase 2 (`docs/scan-filter-consolidation.md`): drop the three
  name filters for CFG-UUID-only. **Gated** on firmware 2.0 in production *and*
  the fielded units updated — pre-2.0 units would otherwise become invisible
  and un-updatable
- Drop the info card's "introduced in firmware 2.0" note once 2.0 is the
  shipping stream (`docs/short-press-alert.md`)

---

## History

### 2026-09-13 — Short Press Alert: delay unit + device-aware copy
Per `docs/short-press-alert.md` (contract owner:
`rareBit-Flags-Receivers/docs/short-press-alert.md`; the iOS twin carries the
same two items).

- **Delay unit correction:** the slider label is the raw field × 30 ms
  (0–450 ms), not × 20. Firmware `CFG_SHTPRS_DELAY_STEP_MS = 30` is already on
  `development`, so before this a slider at 10 read "200" while the Flag waited
  300 ms. The CFG write path is unchanged; the step lives in
  `BleManager.SHORT_PRESS_DELAY_STEP_MS`.
- **Bit 0 meaning by device:** on a Flag it decides whether a short press is
  sent at all. On a Receiver / RXRLY / Relay it relays a short press as its own
  Alert 3 (the same alert whichever Flag pressed); off, the press arrives as the
  normal Flag 1 / Flag 2 alert. Both ends must be on for Alert 3 to reach the
  referee. The info card now explains whichever side is connected.
- No BLE change: the toggle already showed for every device type.

### 2026-09-13 — Relay OTA DFU (legacy Nordic DFU), dev channel first
Per `docs/relay-dfu-flow.md` (iOS twins: `relay-dfu-flow.md`,
`relay-dev-channel.md`). Phase 1 of parity audit P3.

- New `RelayDfuManager` (own coroutine scope, survives the detail screen) and
  `RelayDfuService` on the Nordic DFU library: download → SHA-256 → trigger
  `0xA8` → await disconnect → `1530` scan → flash → "Rebooting — reconnect to
  confirm" and back to the scan list. Reuses `DfuState`; step text travels as a
  separate flow so the SMP observer is untouched.
- `FirmwareRepository.fetchRelayStable()` (session-cached, unauthenticated) and
  `fetchRelayDev(pat)` (uncached) resolve `manifest.json`. `fw_version_byte`
  `0xMN` → "M.N", the same string the FW characteristic produces, so
  `isNewerVersion` works unchanged. Relay requests surface the HTTP status
  ("HTTP 404"), so a PAT without `rareBit-Relay` access says so.
  `RELAY_STABLE_EXACT_TAG` (null = highest) pins a stable tag for testing.
- Detail page, Relay only: the generic DFU card offers stable updates; the dev
  card's *Fetch dev* arms the newest `main` build ("Dev v2.0 (build 1) armed",
  button "Install build 1"). Both confirm "Keep the Relay docked. Do not unplug
  until it reboots." While the flash runs, the trigger's intentional disconnect
  no longer bounces the screen or restarts the main scan.
- `BleManager.writeDfuTrigger()` returns the ATT status (a disconnect before the
  write callback counts as success, per the contract);
  `scanForLegacyDfuBootloader()` scans by service. New optional key
  `ble.dfu_trigger_char_uuid` (unset → Relay DFU absent). Added
  `local.properties.example` listing every key.
- Receiver → RXRLY cross-grade (`relayCard` / `pendingRelayRelease`) and the SMP
  flow are untouched.
- Bench-validated: dev build flashed end to end on a docked OTAFIX Relay.

### 2026-09-11 — Options menu: direct shop link replaces reseller sub-menu
Per `docs/options-shop-link.md` (iOS mirrors it — same label and URL).

- "Buy PRO Sets" sub-menu and its two third-party reseller links (The Top Ref,
  RefsNeedLoveToo) removed; rareBit sells direct via Wix Stores now.
- New top-level **Shop** item (id 4, fourth position) opens
  `https://www.rarebitofficial.com/shop`. The menu is now flat, one popup, five
  items: rareBit Official · User Manual · Smartwatch · Shop · Support.
- Smartwatch remains a no-op, unchanged by this task.

### 2026-09-08 — Battery diagnostic + relay-service scan reject
Both per task docs (`docs/battery-diagnostic.md`,
`docs/scan-filter-consolidation.md`); iOS twins exist for each.

- New read-only battery diagnostic characteristic parsed into `BatteryDiag`
  (nullable — `null` is the normal case on pre-2.0 units and the Relay). Read
  with the connect-time queue and re-read on every CFG notification, skipping
  while a GATT op is in flight (the next notification catches it).
- Battery label/glow precedence: sense fault → SENSE FAULT/Yellow; failed read
  or errno → UNAVAILABLE/Yellow; else the untouched CFG-bits path. Yellow is
  the app's existing "unknown" glow — the label carries the distinction.
- Info card gains a diagnostic line (mV · errno · flags · sample #) when the
  characteristic is present. Config writes and the re-apply cache untouched.
- Scan Phase 1: results advertising the Relay service are dropped before
  typing, logged once per address per scan as `BleScan skip <addr>
  relay-service adv`. The four hardware filters are unchanged.
- Both new UUIDs follow the existing `local.properties` → `BuildConfig`
  pattern; they resolve to null when unset, so a missing key degrades to
  "feature absent" instead of crashing on `UUID.fromString("")`.

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

Implemented per `docs/config-reapply-on-connect.md`.

- Cached only from CFG writes confirmed with GATT status 0 (never from a device
  read — caching a volatile unit's 0x00 would make the mechanism a no-op).
  Bits 0–5 only, keyed by BLE address in SharedPreferences `device_config`;
  bits 6–7 are device-owned battery and are preserved from the live byte.
- Re-applied on connect only when the device reports client bits == 0x00, so a
  config set from a second phone is not overwritten by this one's cache.
- Sequencing note: the doc says re-apply before enabling notifications, but
  Android allows one GATT op in flight, so a write issued there would drop the
  CCC descriptor write. Re-apply instead rides `onDescriptorWrite` (link
  provably idle), falling back to an immediate call when the characteristic
  exposes no CCC descriptor. Same observable behavior.
- `BleCfg` logs `reapply <addr> 0x.. -> 0x..` / `reapply skip (persisted)` /
  `reapply skip (no cache)`.

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
