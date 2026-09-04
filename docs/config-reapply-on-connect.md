# Task: Connect-time config re-apply (safety net for volatile-config units)

Source: CHANGELOG 2026-09-04 "Config persistence findings". Decision (Sam,
2026-09-04): firmware moves its settings pages inside slot 0 so fielded units
persist config after their next OTA (`rareBit-Flags-Receivers`
`docs/config-persistence-in-slot.md`); **both mobile apps** add this re-apply as
the safety net for units power-cycled before that firmware reaches them.
Trello: "Polish App, Sync w/ iOS Work" (Android list). iOS mirrors this doc.

---

## Behavior

1. **Cache on write.** After every user-initiated CFG write that returns GATT
   status 0, store `byte & 0x3F` (bits 0–5 only) per device, keyed by the device's
   BLE address. Persist across app launches (SharedPreferences or DataStore —
   whichever the app already uses; keep it small).
2. **Re-apply on connect.** After the initial read queue drains (battery → FWV →
   CFG) and *before* CFG notifications are enabled:
   - if a cached value exists **and** `cached != 0x00`
   - **and** the device reports bits 0–5 == `0x00` (looks factory-reset)
   → write `(deviceByte & 0xC0) | cached` through the existing write path.
   Otherwise do nothing.
3. Never cache from a device read — only from successful user writes. Otherwise a
   volatile unit's `0x00` would be cached and the whole mechanism is a no-op.
4. UI populates from the re-applied value (existing notify/optimistic path);
   no dialog, no toast. One `BleCfg` log line: `reapply <addr> 0x.. -> 0x..` or
   `reapply skip (persisted / no cache)`.

The "only when device reads factory default" rule was chosen over "write when
different" so a config set from a second phone is not silently overwritten by
this one's cache. Cost: a user who deliberately sets everything to 0 on phone B
gets phone A's old value back on the next A-connect. Accepted as low risk
(single-referee ownership); revisit if it bites.

## Tasks

1. **Make:** cache-on-write + re-apply-on-connect as above. Keep it inside the
   existing config write/read code (`BleCfg` tagged), no new screens.
2. **Test (volatile unit):** bench Flag on `PRO_FLAG_v2.0.0-dev.1` (factory 1.9
   bootloader, known volatile). Set short press on + delay → power-cycle the Flag
   → reconnect → detail view shows the setting and a Flag short press alerts a
   Receiver. Kill the app between steps to prove the cache survives.
3. **Test (persisting unit):** SWD-flashed dev board. Set config, power cycle,
   reconnect → log shows `reapply skip (persisted)`, no write issued.
4. **Assess:** logcat `BleCfg` for the two runs; confirm no write is issued when
   the cache is empty (fresh install) and that the battery bits 6–7 are untouched.
5. **CHANGELOG.md:** entry under History + "Working"; note this is the safety net
   and that firmware-side persistence is the primary fix.
