# rareBit Android — Changelog

## Current State

### Working

**BLE Scanning**
- Hardware-level scan filter on the rareBit Config service UUID (bootloaders
  and third-party devices never reach the callback); name check kept as a
  second gate
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
