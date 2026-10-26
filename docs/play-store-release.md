# Play Store release

Trello: Android → "Publish to Play Store". Branch `feature/play-store-release`.

## Facts (2026-10-26)
- Play Console account: `srall@rarebit.biz`, **Personal** account (ID `6510349407769000518`).
  Personal accounts must run a closed test with **12 opted-in testers for 14 days**
  before production access can be requested. This is the schedule's long pole.
- App record `rareBit` already exists, package **`com.rarebitofficial.android`**,
  store setup 10/11 (store listing outstanding), no bundle uploaded yet.
- `applicationId` changed from `com.example.rarebit` (rejected by Play) to
  `com.rarebitofficial.android` to match the app record. `namespace` and the
  Kotlin package stay `com.example.rarebit` — no source moves.
- Signing: `rarebit-release.jks` + `keystore.*` keys in `local.properties`
  (both git-ignored). Play App Signing holds the app signing key; the jks is the
  **upload key** — back it up off-machine.

## Build
```
.\gradlew bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```
Bump `versionCode` on every upload (Play rejects reused codes).

## Flow
1. Store listing (descriptions, 512 icon, 1024×500 feature graphic, ≥2 phone screenshots).
2. Closed testing release: upload AAB, tester list (≥12 Gmail addresses), roll out.
3. Internal sanity check on Sam's device; testers opt in via the closed-test link.
4. After 14 days with ≥12 opted-in testers: Apply for production, answer the questionnaire, submit.
