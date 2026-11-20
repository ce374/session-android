package org.thoughtcrime.securesms.preferences

import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.preferences.compose.AdditionalSettingsScreen
import org.thoughtcrime.securesms.preferences.compose.AdditionalSettingsViewModel

@AndroidEntryPoint
class AdditionalSettingsActivity : FullComposeScreenLockActivity() {

    private val viewModel: AdditionalSettingsViewModel by viewModels()

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.handleBackupImport(it) }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
        uri?.let { viewModel.handleBackupExport(it) }
    }

    private val exportJsonLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
        uri?.let { viewModel.handleBackupExportJson(it) }
    }

    fun launchImportPicker() {
        runCatching { importLauncher.launch(arrayOf("*/*")) }
    }

    fun launchExportPicker() {
        val name = "session-backup-${System.currentTimeMillis()}.zip"
        runCatching { exportLauncher.launch(name) }
    }

    fun launchExportJsonPicker() {
        val name = "session-backup-${System.currentTimeMillis()}.json"
        runCatching { exportJsonLauncher.launch(name) }
    }

    @Composable
    override fun ComposeContent() {
        AdditionalSettingsScreen(
            activity = this,
            viewModel = viewModel,
            onBackPressed = ::finish,
        )
    }
}
