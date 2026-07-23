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

# The second template has distinct record, summary, and growth chart grammar.
require_text 'JournalSummaryStrip' feature/log/src/main/kotlin/com/lezi/babylog/feature/log/LogScreen.kt
require_text 'JournalTimelineRail' designsystem/src/main/kotlin/com/lezi/babylog/designsystem/Components.kt
require_text 'JournalWeekGrid' feature/summary/src/main/kotlin/com/lezi/babylog/feature/summary/SummaryScreen.kt
require_text 'GrowthMetric.HEAD' feature/growth/src/main/kotlin/com/lezi/babylog/feature/growth/GrowthScreen.kt
require_text 'WHO 示例 P3/P50/P97' feature/growth/src/main/kotlin/com/lezi/babylog/feature/growth/GrowthScreen.kt

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
