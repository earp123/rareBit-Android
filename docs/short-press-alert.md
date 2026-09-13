# Short Press Alert — copy + delay unit (13 Sep 2026)

Scope: `rareBit-Android` — device detail settings card and info card only. No BLE
change: Android has no watch-facing client, and the Short Press Alert toggle
already shows for every device type. Contract owner:
`rareBit-Flags-Receivers/docs/short-press-alert.md`; iOS twin carries the same two
items.

## Contract (what the toggle now means)

- **Flag** bit 0: send a short press (type 2) at all. Unchanged.
- **Receiver / RXRLY / Relay** bit 0: relay a short press as its own alert
  (Alert 3, same alert whichever flag pressed). Off → a short press arrives as the
  normal Flag 1 / Flag 2 alert. Was a persisted no-op before firmware
  `feature/short-press-alert`.
- Both the Flag and the receiving device must be on for Alert 3 to reach the
  referee.

## Change

1. Delay slider label: raw field × **30**, not × 20 — the firmware step is
   `CFG_SHTPRS_DELAY_STEP_MS = 30` (0–450 ms). CFG write path untouched.
2. Info card: rewrite the Short Press Alert text to the wording above, device-type
   aware (Flag copy vs Receiver/Relay copy). Drop any "not in use until 2.0" framing
   once 2.0 is the shipping stream.

## Test (Sam)

1. Flag connected, slider at 10 → label reads 300.
2. Receiver and Relay detail: toggle present, info copy matches the contract;
   toggling writes bit 0 and reads back (existing `BleCfg` log).

## Assess

- CHANGELOG on merge: delay unit correction; note bit 0 meaning on
  Receiver / Relay.
