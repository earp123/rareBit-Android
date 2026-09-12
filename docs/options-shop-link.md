# Task: Options menu — replace reseller links with the rareBit shop (Android)

Source: Sam, 2026-09-11 — `rarebitofficial.com/shop` (Wix Stores) is live and
rareBit sells direct now. Both mobile apps drop the third-party reseller links
and the "Buy PRO Sets" sub-menu that only existed to hold them. iOS mirrors
this doc (`rareBit-App-iOS/docs/options-shop-link.md`) — keep the label and URL
identical. Trello: "New Options Links" (Android list). Branch `feature/options-links`.

Scope: `ui/ScanListFragment.kt`, the `optionsButton` `PopupMenu` only.

---

## Change

Current menu (`optionsButton.setOnClickListener`, ~line 96):

```
rareBit Official
User Manual
Smartwatch            (no-op — untouched by this task)
Buy PRO Sets ▸
    The Top Ref       → thetopref.com/…         (id 41)
    RefsNeedLoveToo   → refsneedlovetoo.com/…   (id 42)
Support
```

Target — flat, one level, no second popup:

```
rareBit Official
User Manual
Smartwatch
Shop                  → https://www.rarebitofficial.com/shop   (id 4)
Support
```

1. Replace `addSubMenu(0, 4, 3, "Buy PRO Sets").apply { … }` with a plain
   `add(0, 4, 3, "Shop")`. Delete the two nested `add(0, 41, …)` /
   `add(0, 42, …)` lines.
2. In `setOnMenuItemClickListener`, delete the `41 ->` and `42 ->` branches and
   add `4 -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.rarebitofficial.com/shop")))`.
   Item id 4 was never handled before (the sub-menu opened its own popup
   instead) — that second popup goes away with it.
3. Nothing else in the menu changes.

## Tasks

1. **Make:** the edit above. After it, `grep -ri "refsneedlovetoo\|thetopref"`
   across the repo returns nothing.
2. **Test:** build and run on a phone. Tap **Options** → one popup, five items,
   Shop is fourth, no nested popup. Tap Shop → browser opens
   `rarebitofficial.com/shop` and the store page loads. Tap each remaining item
   once to confirm it still opens its destination (Smartwatch stays a no-op).
3. **Assess:** menu order matches the target above; no leftover reseller
   strings anywhere in the project.
4. **CHANGELOG.md:** one History entry (`2026-09-xx — Options menu: direct shop
   link replaces reseller sub-menu`) and adjust the Working section if it lists
   the menu items.
