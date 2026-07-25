#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

require_text() {
  local pattern="$1"
  local path="$2"
  if ! rg -q --fixed-strings "$pattern" "$path"; then
    echo "missing remediation: $pattern ($path)" >&2
    exit 1
  fi
}

reject_text() {
  local pattern="$1"
  shift
  if rg -n --fixed-strings "$pattern" "$@"; then
    echo "stale implementation remains: $pattern" >&2
    exit 1
  fi
}

# One shared add/edit surface with partial expansion, calendar-aware clock,
# and an edit-only delete action.
test ! -e feature/log/src/main/kotlin/com/lezi/babylog/feature/log/RecordEditScreen.kt
require_text 'RecordComposerRequest.Edit' app/src/main/kotlin/com/lezi/babylog/MainActivity.kt
require_text 'skipPartiallyExpanded = false' \
  feature/log/src/main/kotlin/com/lezi/babylog/feature/log/RecordComposer.kt
require_text 'onDelete = if (draft.isEditing)' \
  feature/log/src/main/kotlin/com/lezi/babylog/feature/log/RecordComposer.kt
require_text 'DatePickerDialog(' \
  designsystem/src/main/kotlin/com/lezi/babylog/designsystem/ClockDial.kt
require_text 'formatClockDate(selectedDate)' \
  designsystem/src/main/kotlin/com/lezi/babylog/designsystem/ClockDial.kt
require_text 'sessionGate.deliver(session)' \
  feature/log/src/main/kotlin/com/lezi/babylog/feature/log/RecordComposer.kt
require_text 'actionsEnabled = !saving && !deleting' \
  feature/log/src/main/kotlin/com/lezi/babylog/feature/log/QuickRecordSheet.kt

# Source-review safety and architecture gates.
require_text 'android:allowBackup="false"' app/src/main/AndroidManifest.xml
reject_text 'fallbackToDestructiveMigration' \
  core/database/src/main/kotlin/com/lezi/babylog/core/database
require_text 'MIGRATION_1_2' \
  core/database/src/main/kotlin/com/lezi/babylog/core/database/DatabaseModule.kt
require_text 'isMinifyEnabled = true' app/build.gradle.kts
require_text 'isShrinkResources = true' app/build.gradle.kts
if rg -n 'project\(":feature:|project\(":core:database"\)' feature/*/build.gradle.kts; then
  echo "forbidden feature dependency remains" >&2
  exit 1
fi

# UI/ops review gates.
require_text 'MaterialTheme.colorScheme.surface' app/src/main/kotlin/com/lezi/babylog/AppHeader.kt
require_text 'SystemBarStyle.dark(navigationScrim)' \
  app/src/main/kotlin/com/lezi/babylog/MainActivity.kt
require_text 'showContextHeader' app/src/main/kotlin/com/lezi/babylog/MainActivity.kt
require_text 'EnterTransition.None' app/src/main/kotlin/com/lezi/babylog/MainActivity.kt
require_text 'LeziStoolColorMark' \
  feature/log/src/main/kotlin/com/lezi/babylog/feature/log/QuickRecordPurposeFields.kt
require_text '家庭同步服务暂未连接，请稍后重试' \
  feature/family/src/main/kotlin/com/lezi/babylog/feature/family/FamilyScreen.kt
require_text '类型 / 详情 / 备注' \
  feature/search/src/main/kotlin/com/lezi/babylog/feature/search/SearchScreen.kt
require_text '高于同月龄参考范围' \
  core/model/src/main/kotlin/com/lezi/babylog/core/model/GrowthMeasurementFacts.kt
require_text '不足1分' core/ui/src/main/kotlin/com/lezi/babylog/core/ui/RecordPresentation.kt
require_text '丢弃本次计时？' \
  feature/timer/src/main/kotlin/com/lezi/babylog/feature/timer/TimerScreen.kt
require_text 'Settings.Global.BOOT_COUNT' \
  feature/timer/src/main/kotlin/com/lezi/babylog/feature/timer/TimerScreen.kt
require_text 'if (savedBootCount != nowBootCount)' \
  feature/timer/src/main/kotlin/com/lezi/babylog/feature/timer/TimerScreen.kt
require_text 'if (savedBootCount == null && nowBootCount != null)' \
  feature/timer/src/main/kotlin/com/lezi/babylog/feature/timer/TimerScreen.kt
require_text 'Widget rendering only reads the persisted snapshot' \
  feature/widget/src/main/kotlin/com/lezi/babylog/feature/widget/CareWidget.kt
require_text 'WidgetComposerContract.createIntent' \
  feature/widget/src/main/kotlin/com/lezi/babylog/feature/widget/CareWidget.kt

for source in \
  feature/log/src/main/kotlin/com/lezi/babylog/feature/log/QuickRecordSheet.kt \
  feature/log/src/main/kotlin/com/lezi/babylog/feature/log/QuickRecordPurposeFields.kt \
  feature/log/src/main/kotlin/com/lezi/babylog/feature/log/QuickRecordSheetControls.kt \
  designsystem/src/main/kotlin/com/lezi/babylog/designsystem/Components.kt \
  designsystem/src/main/kotlin/com/lezi/babylog/designsystem/TimelineComponents.kt \
  designsystem/src/main/kotlin/com/lezi/babylog/designsystem/ActionStateComponents.kt \
  designsystem/src/main/kotlin/com/lezi/babylog/designsystem/PageComponents.kt; do
  lines="$(wc -l < "$source")"
  if (( lines > 750 )); then
    echo "oversized UI source remains: $source ($lines lines)" >&2
    exit 1
  fi
done

echo "current-scope source and UI remediation gates: OK"
echo "V2 network/sync findings remain deferred by the originating review."
