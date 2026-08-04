# Design notes — 09 family lazy members/devices

## Public seams under test

Observed without reaching private Compose helpers. Expected values from ticket 09
/ growth lazy history pattern (ticket 08) / members-row remediation tags.

**Test ownership (AGENTS / tech.md §2.1):** do **not** gate LazyColumn /
`items` / `animateItem` / chrome symbol presence via product-less source-layout
StructureTests. JVM unit coverage for this ticket is the pure roster flatten
helper only; revoke/remove/rename/QR domain stays on existing host/policy tests;
semantics/tags stay on `FamilyMembersDevicesPageDeviceTest` (androidTest).

1. **Members + devices are one lazy structure with stable keys** (implementation)
   - `FamilyMembersListSheet` body scrolls via `LazyColumn` (not
     `Column` + `verticalScroll` materializing every row).
   - Member header rows use stable key `member:<membershipId>` and
     `contentType = "member_row"`.
   - Device rows use stable key `device:<deviceId>` and
     `contentType = "device_row"`.
   - Empty-devices placeholder (authorized empty list) uses
     `empty_devices:<membershipId>` / `contentType = "empty_devices"`.
   - Pending login / rename sections (owner) use request-id keys when listed.

2. **Item animation for visible insert/remove** (implementation)
   - Roster member/device/empty-device row modifiers use foundation
     `Modifier.animateItem()` inside the lazy item scope — same public API as
     growth history / timeline / search.

3. **Privacy projection preserved** (pure + UI)
   - Non-owner viewers only get device sections for self; other members produce
     **no** device lazy items (not an empty-devices row).
   - Authorized viewers (owner: every member; ordinary: self) always see device
     rows or `empty_devices:<id>` — authorized `devices == null` is coerced to
     empty list (not a silent section drop).
   - `Member` header items store `member.copy(devices = null)` so header
     equality ignores device-list churn rendered as sibling items.
   - `Device` items carry `memberIsSelf` from flatten time (no sheet re-scan).

4. **Overflow menus, destructive colors, test tags, semantics preserved**
   - `members_manage_menu`, `member_overflow_menu`, `device_overflow_menu`,
     `member_action_*`, `device_action_*` tags remain.
   - Destructive labels keep error color; device semantics keep
     name / 这台设备 / activity line.
   - Domain callbacks (remove, rename, QR, revoke) unchanged.
   - Action eligibility is pure helpers (`canCreateMemberLoginQr`,
     `canRenameFamilyMemberRow`, `canRenameFamilyDeviceRow`,
     `canRevokeFamilyDeviceRow`, plus existing `canRemoveFamilyMember`).

5. **No membership / QR / device revoke domain changes**
   - Host, SyncPort, and dialog confirm flows are out of scope for this ticket.

## Pure helper (unit seam)

- `familyRosterLazyItems(members, viewerIsOwner)`:
  - Privacy + authorized null→empty coercion; flattens header + devices.
  - Covered by `FamilyLazyMembersContractTest` (owner devices/empty/null,
    ordinary privacy + self empty, action-policy helpers).

## Out of scope

- Growth history (ticket 08), summary/record density, shell motion
- CareLog writes, sync wire, Room schema
- Product-less StructureTests that read FamilyMembersListUi.kt source text
