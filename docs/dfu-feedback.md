# DFU feedback & auto-reconnect

Branch `feature/dfu-feedback`. Trello: Android → "DFU Feedback & Auto-Reconnect".
UI layout is untouched — this is mechanism and timing only. Reuse the existing
status text + progress bar on each card.

## Problem (audit of `DfuManager` / `DeviceDetailFragment`, main @ 66d52a3)

SMP path (Flag / Receiver / RXRLY, incl. the Relay-flash and Restore cards):

1. **Mid-flash bounce.** `BleManager` keeps its own GATT open while
   `McuMgrBleTransport` runs. When the device resets for the MCUboot swap,
   that link drops → the fragment's device observer sees `!isConnected` →
   `startScan()` + `navigateUp()` *before* `onUpgradeCompleted`. The Relay
   path already guards this with `relayDfuManager.isActive`; SMP has nothing.
2. **Progress disappears on rotation / navigate-back.** `dfuStartedByThisFragment`
   and `activeDfu` are fragment-local; a re-created fragment hides a running
   flash. The download also runs in `viewLifecycleOwner.lifecycleScope`, so
   leaving the screen cancels it and leaves the manager stuck in `Downloading`
   (only `Success`/`Error` are reset in `onViewCreated`).
3. **Dead phases.** `onStateChanged` is discarded. Validate shows as
   "Uploading…"; confirm + reset show as a frozen "100%" for the swap duration
   (`setEstimatedSwapTime(3000)` is a hint to the library, not what the device
   takes). `Success` calls `navigateUp()` on the same frame, so "Rebooting…"
   is never seen.
4. **No confirmation.** Nothing verifies the device came back on the new
   version (parity audit P4). Same gap on the Relay path.

## Design

### 1. One app-scoped flow per manager

`DfuManager` mirrors `RelayDfuManager`: own `CoroutineScope(Main + SupervisorJob)`,
owns the target `address`, exposes `isActive`, and runs download → upgrade
→ reconnect → verify as one job. The fragment only renders; it never holds
flow state. Delete `dfuStartedByThisFragment`. Keep `activeDfu` only to pick
which card's views to drive — derive it from a `target: DfuTarget` field on
the manager (`UPDATE` / `RELAY_FLASH` / `RESTORE`) so a re-created fragment
routes correctly.

Replace the shared `DfuState` with a richer one both managers emit:

```kotlin
sealed class DfuState {
    object Idle : DfuState()
    data class Running(val stage: String, val percent: Int? = null) : DfuState() // null = indeterminate
    data class Done(val message: String) : DfuState()   // terminal, verified
    data class Error(val message: String) : DfuState()
}
```

Drop `RelayDfuManager.stage` — the step text moves into `Running.stage`.

### 2. Stage mapping (SMP)

Map `onStateChanged(newState)` to stage text. Verify the actual order for
mcumgr-android **2.7.4** in `CONFIRM_ONLY` mode against the library source
before wiring — do not trust this table blindly:

| Library state | `Running.stage` | percent |
|---|---|---|
| (download) | `Downloading…` | bytes/content-length if the header is present, else null |
| `VALIDATE` | `Checking device…` | null |
| `UPLOAD` | `Uploading…` | from `onUploadProgressChanged` |
| `CONFIRM` | `Confirming…` | null |
| `RESET` | `Rebooting — swapping image…` | null |
| `onUpgradeCompleted` | `Rebooting — swapping image…` (unchanged) | null |

After `onUpgradeCompleted`, close the transport and move to step 4.

Relay path keeps its existing steps; `Flashing…` carries the percent.

### 3. Disconnect guard

In the fragment's device observer, treat a disconnect as expected while
`dfuManager.isActive && dfuManager.address == deviceAddress` — exactly the
existing Relay guard. Do not start the main scan. In `BleManager`, the
disconnect handler already resets the device fields; that is fine — the
manager, not the device row, is the source of truth while a DFU runs.

### 4. Reconnect + verify (both paths)

After the flash completes (SMP: `onUpgradeCompleted`; Relay: `onDfuCompleted`):

1. `Running("Rebooting — waiting for device…")`.
2. Scan for the same address (new `BleManager.awaitDevice(address, timeoutMs)`
   — a dedicated scan callback like `scanForLegacyDfuBootloader`, filtered by
   address, main scan stays off). Timeout **45 s** (SMP swap of a full image
   can run 10–30 s on nRF52; Relay reboots fast).
3. `bleManager.connect(device)`; `Running("Reconnecting…")`. Wait for
   `firmwareVersion` to be non-empty on the device row (timeout 20 s).
4. Compare with `FirmwareRepository.isNewerVersion`:
   - equal → `Done("Updated to v<version>")`, `setHasUpdate(address, false)`
   - device still older → `Error("Device reports v<old> — update did not apply")`
   - newer than expected (dev builds) → treat as `Done`.
5. Any timeout → `Error("Didn't reconnect — reconnect from the scan list to confirm")`.
   This is not a flash failure; say so in the text.

Expected version: SMP = `pendingRelease.version`; Relay = manifest
`fw_version_byte` → "M.N" (already produced by `RelayReleaseInfo`).

RXRLY cross-grade / Restore: the expected string is the target release's
version (10.x or 1.x) — the title-card personality label updates on its own.

### 5. Terminal state hold

On `Done` or `Error` the fragment shows the text, hides the bar, re-enables
the button, and **stays on the detail screen** — the device is now connected
and its cards populate normally. Remove the `navigateUp()` + `scheduleScan`
on success. Reset the manager to `Idle` only when the user leaves the screen
or starts another DFU (keep the existing `onViewCreated` reset for stale
terminal states).

### 6. Fragment observer

One collector per manager, no `dfuStartedByThisFragment` guard: render
whatever the manager says, routed by `manager.target`. `Running` → text =
stage (+ ` n%` when percent != null), bar visible, indeterminate iff percent
== null, button disabled. `Done`/`Error` → text, bar gone, button enabled.
Rotation or back-and-forth now resumes the live view.

## Out of scope

- Any layout / colour change.
- Retry button on fetch failure (still Pending in CHANGELOG).
- Bootloader-recovery card for an interrupted Relay flash (Phase 2, separate).

## Test (bench)

1. Flag or Receiver, stable SMP update: every stage visible in order, no
   bounce to the scan list, screen ends on "Updated to vX.Y" with the device
   connected and the Settings card populated.
2. Rotate the phone during upload, and separately press back and re-open the
   device during upload: progress bar and percent resume.
3. Relay dev build (docked OTAFIX): same as 1 via the legacy path, ending on
   "Updated to v2.0".
4. Pull the device's power during the swap: `Error("Didn't reconnect…")`
   within ~45 s, button re-enabled, no crash.
5. Logcat (`DfuManager` / `RelayDfu` tags) timestamps vs. what the screen
   shows at each step — note any stage that lags the device by >1 s.

Update `CHANGELOG.md` (Firmware / DFU + Pending) on completion.
