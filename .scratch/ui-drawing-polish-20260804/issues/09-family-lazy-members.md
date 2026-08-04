# 09 — Family: lazy members/devices

**What to build:** Family members and devices lists scroll as lazy lists with
stable keys and item animation; overflow menus, destructive styling, and
meta lines from prior remediation remain correct.

**Blocked by:** 08 — Growth: lazy history + surface polish.

**Status:** done

- [x] Members list is lazy with stable keys
- [x] Devices rows are lazy with stable keys (or one lazy structure covering both)
- [x] Item animation for visible insert/remove
- [x] Overflow menus, destructive colors, test tags, semantics preserved
- [x] Membership/QR/device revoke domain behavior unchanged
- [x] `./gradlew :feature:family:test :app:assembleDebug` green
