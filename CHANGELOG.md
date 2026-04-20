# rareBit Android — Changelog

## Current State

### Working

**BLE Scanning**
- Filters for devices advertising "rareBit" in their name
- Detects device type (FLAG / RECEIVER / UNKNOWN) from name substring
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

**Device Detail UI**
- Loading overlay ("Connecting…" → "Checking for updates…") before content reveals
- Title card glow color + stroke driven by connection state and glow enum
- Settings card: battery, firmware version, alert toggle, delay slider
- Info card: expandable
- DFU-only devices (SMP only, no config service): settings/info cards hidden, DFU card shown immediately

**Firmware / DFU**
- GitHub `/releases` list endpoint filtered by tag prefix per device type — correctly skips RECEIVER releases when fetching FLAG firmware
- Version regex handles both 2-part (`10.0`) and 3-part (`1.9.0`) tag formats
- Release info cached in-memory per session
- DFU-only devices with UNKNOWN type fall back to FLAG firmware URL
- MCUManager `TEST_AND_CONFIRM` upgrade mode
- DFU progress: Downloading → Uploading → Progress % → Success / Error
- On success: saves installed version to SharedPreferences, navigates back to scan list automatically
- DFU card hidden on next visit if installed version matches GitHub latest

**Device Card (scan list)**
- White card / black text for pre-connect; dark card / colored glow for connected
- RSSI shown as animated ProgressBar at the card bottom, inset 10dp from sides and 8dp from bottom edge
- Signal range: -75 dBm = empty → -15 dBm = full; animates on each scan update
- 21sp device name

---

### Pending

- Alert toggle and delay slider are display-only — no BLE writes wired yet
- RECEIVER firmware URL not yet provided
- No retry if GitHub fetch fails mid-session (requires navigating away and back)
- "No firmware URL" error shown in DFU status text if fetch failed — informational only, no retry button
