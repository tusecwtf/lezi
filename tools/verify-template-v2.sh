#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

require_text() {
  local pattern="$1"
  local path="$2"
  if ! rg -q --fixed-strings "$pattern" "$path"; then
    echo "missing: $pattern ($path)" >&2
    exit 1
  fi
}

# One persisted switch controls one shared route graph and data model.
require_text 'val visualStyle: String = "warm"' core/model/src/main/kotlin/com/lezi/babylog/core/model/Models.kt
require_text 'setVisualStyle(style: String)' core/datastore/src/main/kotlin/com/lezi/babylog/core/datastore/SettingsStore.kt
require_text 'stringPreferencesKey("visual_style")' core/datastore/src/main/kotlin/com/lezi/babylog/core/datastore/SettingsDataSource.kt
require_text 'visualStyle = ui.visualStyle' app/src/main/kotlin/com/lezi/babylog/MainActivity.kt
require_text '"warm" to "温暖卡片"' feature/settings/src/main/kotlin/com/lezi/babylog/feature/settings/SettingsScreen.kt
require_text '"journal" to "紧凑记录簿"' feature/settings/src/main/kotlin/com/lezi/babylog/feature/settings/SettingsScreen.kt

# Both templates share one persisted, handed one-thumb interaction contract.
require_text 'val preferredHand: String = "right"' core/model/src/main/kotlin/com/lezi/babylog/core/model/Models.kt
require_text 'setPreferredHand(hand: String)' core/datastore/src/main/kotlin/com/lezi/babylog/core/datastore/SettingsStore.kt
require_text 'stringPreferencesKey("preferred_hand")' core/datastore/src/main/kotlin/com/lezi/babylog/core/datastore/SettingsDataSource.kt
require_text '"left" to "左手"' feature/settings/src/main/kotlin/com/lezi/babylog/feature/settings/SettingsScreen.kt
require_text '"right" to "右手"' feature/settings/src/main/kotlin/com/lezi/babylog/feature/settings/SettingsScreen.kt
require_text 'OneHandQuickDock(' feature/log/src/main/kotlin/com/lezi/babylog/feature/log/LogScreen.kt
require_text 'preferredHand = state.settings.preferredHand' feature/log/src/main/kotlin/com/lezi/babylog/feature/log/LogScreen.kt
require_text 'oneHandQuickActionOrder(' feature/log/src/main/kotlin/com/lezi/babylog/feature/log/LogScreen.kt
require_text 'RecordComposerRequest.Edit' feature/log/src/main/kotlin/com/lezi/babylog/feature/log/LogScreen.kt
require_text 'skipPartiallyExpanded = false' feature/log/src/main/kotlin/com/lezi/babylog/feature/log/RecordComposer.kt
require_text 'onDelete = if (draft.isEditing)' feature/log/src/main/kotlin/com/lezi/babylog/feature/log/RecordComposer.kt

# The second template has distinct record, summary, and growth chart grammar.
require_text 'RecordSummaryStrip(' core/ui/src/main/kotlin/com/lezi/babylog/core/ui/RecordPresentation.kt
require_text 'RecordSummaryStrip(' feature/log/src/main/kotlin/com/lezi/babylog/feature/log/LogScreen.kt
require_text 'JournalTimelineRail' designsystem/src/main/kotlin/com/lezi/babylog/designsystem/TimelineComponents.kt
require_text 'JournalWeekGrid' feature/summary/src/main/kotlin/com/lezi/babylog/feature/summary/SummaryScreen.kt
require_text 'GrowthMetric.HEAD' feature/growth/src/main/kotlin/com/lezi/babylog/feature/growth/GrowthScreen.kt
require_text 'WHO 儿童生长标准 0–24 月 P3/P50/P97（按性别）' \
  feature/growth/src/main/kotlin/com/lezi/babylog/feature/growth/GrowthScreen.kt

# Product code must not ship reference-product branding.
if rg -n -i 'piyolog|ぴよログ' app core designsystem domain feature sync --glob '*.kt'; then
  echo "reference-product branding found in shipped Kotlin" >&2
  exit 1
fi

# Checked-in Open Design evidence must accompany the Compose implementation.
for file in index.html styles.css components.css app.js tokens.json prd-equivalence.md scripts/static-check.mjs; do
  test -s "design/template-v2/$file" || {
    echo "missing Open Design snapshot: design/template-v2/$file" >&2
    exit 1
  }
done

echo "template-v2 equivalence contract: OK"
