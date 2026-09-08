# Task: Surface battery read failures and sense faults

Source: rareBit-Flags-Receivers CHANGELOG 4 Sep 2026 — "Battery diagnostic
characteristic" + "Battery sense-fault indication". Decision (Sam,
2026-09-08): propagate to **both** apps. iOS twin:
`rareBit-App-iOS/docs/battery-diagnostic.md` — keep the rules identical.
Trello: iOS "Improve Battery Level Feature"; Android card pending.

---

## Why

The CFG byte's battery bits (7–6) have no "unknown" value. A unit whose ADC
read fails, or whose sense divider is the wrong part (a 680R-for-68k1 batch
was confirmed on the bench), reports **LOW forever** and the app shows a red
glow that is not true. Firmware 2.0 (dev stream) now exposes what it actually
measured, so the app can stop calling a faulted unit a flat one.

## Characteristic

`23220005-38d5-4b7b-bad0-7dee1eee1b6d` — CFG service, **read-only, no notify**,
9 bytes little-endian, appended last (CFG / FWV handles unchanged):

| Bytes | Meaning |
|-------|---------|
| 0–1 | raw ADC counts (int16) |
| 2–3 | millivolts at the divider tap (int16); **`-1` (0xFFFF) = read failed** |
| 4–5 | errno from the last attempt (int16); 0 = OK |
| 6 | graded level 0 LOW / 1 MID / 2 HIGH / 3 FULL (same value as CFG bits 7–6) |
| 7 | bit0 USB docked · bit1 charger STAT high · **bit2 sense fault** |
| 8 | sample counter, wraps |

Present on Flag / Receiver ≥ 2.0 only. **Absent** on fielded 1.9 / 1.8 / 10.0
and on the Relay (its ADC is still a stub) — absence is the normal case for a
while and must change nothing.

Firmware caveat worth knowing, not acting on: STAT high also reads high when
nothing drives the pin; sense fault is only unambiguous undocked. We only see
the device docked (config mode), so the app shows what firmware reports and
does no extra inference.

## Behavior

1. **Constant.** Follow the existing pattern: `local.properties`
   `ble.batt_diag_char_uuid` → `buildConfigField("BLE_BATT_DIAG_CHAR_UUID")` →
   `BleManager.BATT_DIAG_CHAR_UUID`. Add to `CHAR_NAMES` ("Battery Diagnostic").
2. **Read.** Add it to the `autoRead` set in `onServicesDiscovered` — it lands
   last in the queue (after CFG) because discovery order follows the GATT
   table. Re-read on every CFG notification (battery bits changed); if a GATT
   op is already in flight, skip — the next notify catches it.
3. **Parse → `BatteryDiag` on `BleDevice`** (nullable; `null` = characteristic
   absent): `raw`, `mv`, `errno`, `level`, `docked`, `statHigh`, `senseFault`,
   `count`. One log line per read:
   `BleBatt diag(<addr>) mv=.. err=.. lvl=.. flags=0x.. n=..`.
4. **Derived battery display, in this precedence:**
   - `senseFault` → label **SENSE FAULT**, glow `YELLOW`
   - else `mv == -1 || errno != 0` → label **UNAVAILABLE**, glow `YELLOW`
   - else → existing CFG-bits path (LOW / MID / HIGH / FULL, Red / Blue / Cyan / Green)
   - `diag == null` → existing path, untouched (legacy units, Relay)

   Yellow is already the app's "unknown" glow — no new colour; the label
   carries the distinction. Put the override in `BleDevice.glowState` and the
   `batteryValue.text` `when` in `DeviceDetailFragment`.
5. **Info card.** When `diag != null`, add one "Battery diagnostic" line to the
   expandable Info card: `1043 mV · errno 0 · docked · STAT high · #37`. Not on
   the Settings card.
6. **Writes untouched.** The diag never feeds the CFG write base or the
   re-apply cache; bits 6–7 handling stays exactly as it is.

## Tasks

1. **Make:** 1–6 above inside `BleManager`, `BleDevice`, `DeviceDetailFragment`;
   no new screens.
2. **Test (healthy 2.0 unit):** docked Flag on a 2.0 dev build → Info line
   shows ~1000–1070 mV, errno 0, docked; label and glow match the CFG bits
   exactly as before.
3. **Test (fault paths, temporary bench firmware):** (a) `adc_read()` forced
   to return `-EIO` → **UNAVAILABLE** / Yellow; (b) `SENSE_FAULT_MAX_MV`
   raised to 2000 → **SENSE FAULT** / Yellow. Revert both hacks. (Firmware's
   red-4x blink is undocked-only, so the LED will not confirm (b) on the dock.)
4. **Test (absence):** fielded 1.9 Flag → no Info line, label / glow
   unchanged, no `BleBatt` errors.
5. **Assess + CHANGELOG.md:** logcat `BleBatt` for the four runs; confirm no
   CFG write was issued as a side effect. Entry under History + "Working" +
   the parsing table under Characteristic Parsing.
