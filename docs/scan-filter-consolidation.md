# Task: Scan filter consolidation — never list the watch-facing Relay service

Source: Trello Firmware "Config Service in ad packet"
(`rareBit-Flags-Receivers/docs/cfg-uuid-in-docked-adv.md`). Decision (Sam,
2026-09-08): phones must never connect to the Relay service (`33210001-…`);
it is for smartwatches only. iOS twin: `rareBit-App-iOS/docs/scan-filter-consolidation.md`.

---

## Problem

The hardware name filter `"rareBit Relay"` matches the **undocked** Relay /
RXRLY advertisement (Relay service UUID in the AD, name in the scan response),
so the app lists it and a tap connects to the watch-facing service. iOS already
rejects those in `didDiscover`; Android does not.

## Phase 1 — now, no firmware dependency

- Add `RELAY_SERVICE_UUID` (`33210001-28d5-4b7b-bad0-7dee1eee1b6d`) the same way
  the CFG UUIDs are defined (`local.properties` → `BuildConfig`).
- In `onScanResult`, drop any result whose `scanRecord.serviceUuids` contains
  it. Log once per address per scan: `BleScan skip <addr> relay-service adv`.
- Keep the four hardware filters exactly as they are.

## Phase 2 — gated on the fleet

Once every fielded Flag / Receiver / Relay advertises the CFG UUID while
docked (firmware 2.0 in production **and** units updated — Sam's call):

- Drop the three name filters; keep only `setServiceUuid(CFG_SERVICE_UUID)`.
- Device typing by exact name stays — the name arrives in the scan response
  (active scan, already the case with `SCAN_MODE_LOW_LATENCY`).
- Cost: pre-2.0 units become invisible to the app and can no longer be
  updated from it. That is why this is gated, not bundled with Phase 1.

## Tasks

1. **Make (Phase 1):** relay-service reject in `onScanResult`.
2. **Test:** undocked RXRLY (or Relay) advertising beside a docked Flag → only
   the Flag is listed. Dock the Relay → it appears (name-only on 1.9, CFG UUID
   on 2.0).
3. **Assess:** `BleScan` shows the skip line for the relay address; connect /
   config / DFU flows unchanged.
4. **CHANGELOG.md:** entry under History + "Working"; add Phase 2 as a Pending
   line with its gate.
