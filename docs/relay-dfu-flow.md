# Task: Relay OTA DFU flow (Android) — legacy Nordic DFU, dev channel first

Source of truth: `rareBit-Relay/CHANGELOG.md` "OTA DFU" (mobile integration
spec) and "Development release channel (2026-09-14)"; iOS twin is
`rareBit-App-iOS/docs/relay-dfu-flow.md` + `docs/relay-dev-channel.md`.
Decision (Sam, 2026-09-13): both mobile apps reach Relay dev builds through
the hidden dev DFU card. Android has **no Relay DFU flow at all** today
(CHANGELOG "Pending", parity audit P3) — this doc builds it, with the dev
source as the first consumer and the stable source on the same path.
Trello: Android card TBD.
Scope: `FirmwareRepository`, a new `RelayDfuManager`, `DeviceDetailFragment`
(Relay device type only), `BleManager` (one write + one scan), gradle deps,
`local.properties` keys. Flag / Receiver / RXRLY SMP flow untouched.

**Fail-fast order:** trigger → bootloader → flash → FWV confirm on a docked
OTAFIX Relay from a dev build is the whole point. Recovery card and post-flash
niceties are Phase 2 and can wait.

---

## Where Android stands (read before touching anything)

- `FirmwareRepository` reads only the private `rareBit-Flags-Receivers` repo
  with `BuildConfig.GITHUB_PAT`, takes the first `.bin` asset, no
  `manifest.json`, no SHA-256. `DeviceType.RELAY` has no `TagSpec`, so the
  Relay detail page shows no DFU card and *Fetch dev* says "No dev release".
- `DfuManager` is SMP/McuManager only (`mcumgr-ble` 2.7.4). The Relay is
  **not** SMP — it needs Nordic's DFU library (`no.nordicsemi.android:dfu`),
  which is not a dependency yet.
- Naming trap: `relayCard` / `relayButton` / `pendingRelayRelease` /
  `ActiveDfu.RELAY` in `DeviceDetailFragment` are the **Receiver → RXRLY
  cross-grade**, not the Relay device. Do not reuse them. Name the new pieces
  `relayFw…` or similar.
- UUIDs come from `local.properties` → `BuildConfig` and resolve to `null`
  when unset ("feature absent", no crash). Follow that for the trigger char.
- `BleManager.writeCharacteristic(address, service, char, bytes)` exists and
  reports through `onCharacteristicWrite` (status is the ATT error code).
- Scan filters are CFG service UUID + three exact names, so a bootloader-mode
  Relay (service `1530`, board-specific name) is invisible to the main scan.
  That's fine for Phase 1: the flash scans for `1530` itself.

## Firmware contract

Two sources, one flow. The Relay never fetches from `rareBit-Flags-Receivers`.

| | Stable | Development |
|---|---|---|
| Repo | `earp123/rareBit-firmware-releases` (public, no auth) | `earp123/rareBit-Relay` (private, PAT) |
| Select | tag prefix `relay-v`, highest version, `prerelease == false` | `prerelease == true && target_commitish == "main" && tag.startsWith("RELAY_")`, highest `-dev.<n>` by parsing the suffix |
| Example tag | `relay-v2.0` | `RELAY_v2.0-dev.1` |
| Asset download | `browser_download_url` | asset API `url` + `Accept: application/octet-stream` + PAT |
| Version gate | offer when `fw_version_byte` > device FWV | **none** — developer chose it |

Both carry `manifest.json`:

```json
{ "product": "relay", "tag": "v2.0", "release_tag": "RELAY_v2.0-dev.1",
  "fw_version_byte": "0x20", "dfu_package": "rareBit-Relay-v2.0-dev.1-dfu.zip",
  "uf2": "…", "dfu_package_sha256": "…", "commit": "…", "built_at": "…",
  "channel": "development", "branch": "main", "build": 1, "run_url": "…" }
```

(`channel` / `branch` / `build` / `run_url` are dev-only; stable manifests
omit them.) Flash `dfu_package` after its SHA-256 matches
`dfu_package_sha256`. `fw_version_byte` `0xMN` → `"M.N"` — the same string
the FW characteristic parser produces, so `isNewerVersion` works unchanged.
The version byte is pinned per stream (`0x20` today); dev builds are told
apart by `build`.

PAT: same `github.pat` in `local.properties` — it must have Contents: read on
`rareBit-Relay` as well (Sam extends the token's repository list; no new
key). `Authorization: token <pat>` as today is accepted for fine-grained
tokens.

## GATT + flash (config service, USB-docked only)

- DFU trigger char `23220004-38d5-4b7b-bad0-7dee1eee1b6d` (write, 1 byte).
  New `local.properties` key `ble.dfu_trigger_char_uuid` →
  `BuildConfig.BLE_DFU_TRIGGER_CHAR_UUID`, optional-UUID pattern.
- Write `0xA8` → write response → device reboots into the bootloader ~500 ms
  later. **The disconnect is the success signal.** ATT `0x03` Write Not
  Permitted = not docked ("Dock the Relay on USB power"); `0x13` wrong
  byte; `0x0D` wrong length.
- Bootloader advertises Nordic legacy DFU service
  `00001530-1212-EFDE-1523-785FEABCD123` (public Nordic UUID — a code
  constant is fine). Scan by that service UUID, **never** by name or by the
  app's address (OTAFIX uses a board-specific name and may change address).
  Take the first `1530` advertiser; 15 s timeout → "Relay did not reappear in
  update mode".
- Flash with `no.nordicsemi.android:dfu` (2.x): `DfuServiceInitiator(address)
  .setZip(uri)` — zip unchanged, library auto-detects legacy; PRN default on;
  `setForeground(false)` is acceptable for a dev tool, else create the
  notification channel. Needs a `DfuService` subclass declared in the
  manifest and `DfuProgressListenerHelper` for progress.
- On completion the bootloader reboots into the new app; the device is still
  docked, so it re-advertises config mode. Phase 1: show "Rebooting — reconnect
  to confirm" and return to the scan list (matches the SMP flow today).
  Phase 2: auto-reconnect + FWV == manifest byte (P4 also wants this for SMP).
- Prerequisite: OTAFIX bootloader on the unit. Factory 0.6.1 hangs at 100 %
  and leaves the app invalid. Test only on provisioned Relays.

## Behavior

1. **Repository.** `FirmwareRepository.fetchRelayStable()` /
   `fetchRelayDev(pat)` per the table, both returning a
   `RelayReleaseInfo(version, build?, channel, zipAssetUrl, sha256)` (or
   fold into `ReleaseInfo` — pick the smaller change). Stable is
   session-cached like the others; dev is never cached. Log
   `FirmwareRepo relay <tag> -> <byte> build <n>`.
2. **`RelayDfuManager`** (new, sibling of `DfuManager`, same `DfuState`):
   download (channel-aware) → SHA-256 → write `0xA8` → await disconnect →
   `1530` scan → DFU library → `Success`/`Error`. Map ATT `0x03` to the
   dock message. Expose `cancel()`.
3. **Detail page, `DeviceType.RELAY`.** Stable: the existing generic
   `dfuCard` shows when `hasUpdate` (fetch = `fetchRelayStable`), button
   `Update to v2.x`, install runs `RelayDfuManager`, not `DfuManager`. Dev:
   the existing dev card's *Fetch dev* calls `fetchRelayDev` for this type,
   arms it, relabels the button `Install build <n>`, forces `dfuCard`
   visible, and install runs the same `RelayDfuManager` with no version
   check. Armed dev release clears on disconnect / leaving the fragment.
   `DEV_BRANCH_LABEL` becomes per-type (`main` for the Relay).
4. **Confirmation dialog** before the flash, same shape as the RXRLY one:
   "Keep the Relay docked. Do not unplug until it reboots."
5. **Untouched:** SMP flow, scan filters, the RXRLY cross-grade cards,
   `DfuManager`.

## Tasks

1. **Make:** 1–5 above; add the `dfu` dependency + `DfuService` + manifest
   entry + `local.properties` key. Update `local.properties.example` (or the
   README key list) with `ble.dfu_trigger_char_uuid`.
2. **Test (dev, the real one):** docked OTAFIX Relay on `relay-v2.0` → detail
   → hold 10 s → *Fetch dev* → `Dev v2.0 (build 1) armed` → Install →
   dialog → `0xA8` accepted → disconnect → `1530` found → upload to 100 % →
   reboot → reconnect from the scan list → FWV `2.0`. Logcat `RelayDfu`
   lines clean.
3. **Test (stable):** same Relay, no hold → `fetchRelayStable` resolves
   `relay-v2.0` from the public repo, no update offered on a 2.0 unit;
   temporarily point the exact tag at `relay-v1.10` → card appears →
   install path identical.
4. **Test (negatives):** undocked Relay → trigger write returns `0x03` →
   dock message, nothing else happens. PAT lacking `rareBit-Relay` → *Dev
   fetch failed* with the HTTP code; stable path unaffected. Trigger UUID
   unset in `local.properties` → Relay DFU card absent, no crash.
5. **Assess + CHANGELOG.md:** move "Relay legacy Nordic DFU flow" out of
   Pending; History entry; Firmware/DFU section gains the Relay row and the
   dev-source table. Note Phase 2 (bootloader-mode recovery card, post-flash
   auto-reconnect) as the remaining gap.
