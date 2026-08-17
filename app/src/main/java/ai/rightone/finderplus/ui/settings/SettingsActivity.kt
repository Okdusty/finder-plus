package ai.rightone.finderplus.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * DESIGN-ONLY STUB — the dialog-themed onboarding/settings sheet is specified in docs/ui/WIREFRAMES.md
 * §3 and binds to [ai.rightone.finderplus.ui.contract.SettingsUiState] / SettingsViewModel. Rendering
 * is the next phase.
 *
 * Implementation plan (next phase, Compose):
 *  - First run: permission gate (READ_MEDIA_* + POST_NOTIFICATIONS) + speech-model picker -> "Build index".
 *  - Settings: index stats, model download manager, indexing prefs, rebuild/wipe.
 *  - Bind a SettingsViewModel over ModelManager + IndexOrchestrator + prefs store.
 */
@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO(ui): render onboarding/settings per docs/ui/WIREFRAMES.md §3.
    }
}
