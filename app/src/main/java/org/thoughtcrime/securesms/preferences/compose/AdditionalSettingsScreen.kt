package org.thoughtcrime.securesms.preferences.compose

import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import network.loki.messenger.R
import org.thoughtcrime.securesms.preferences.AdditionalSettingsActivity
import org.thoughtcrime.securesms.ui.ActionRowItem
import org.thoughtcrime.securesms.ui.CategoryCell
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.theme.LocalDimensions

@Composable
fun AdditionalSettingsScreen(
    activity: AdditionalSettingsActivity,
    viewModel: AdditionalSettingsViewModel,
    onBackPressed: () -> Unit,
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    BasePreferenceScreens(
        onBack = onBackPressed,
        title = GetString(R.string.sessionAdditional).string(),
    ) {
        item {
            CategoryCell(modifier = Modifier) {
                ActionRowItem(
                    title = annotatedStringResource(R.string.sessionBackupImport),
                    onClick = { activity.launchImportPicker() },
                    qaTag = R.string.qa_settings_item_backup_import,
                    modifier = Modifier.fillMaxWidth(),
                    subtitle = annotatedStringResource(R.string.sessionBackupImportDescription),
                )
            }
            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
        }

        item {
            CategoryCell(modifier = Modifier) {
                ActionRowItem(
                    title = annotatedStringResource(R.string.sessionBackupExport),
                    onClick = { activity.launchExportPicker() },
                    qaTag = R.string.qa_settings_item_backup_export,
                    modifier = Modifier.fillMaxWidth(),
                    subtitle = annotatedStringResource(R.string.sessionBackupExportDescription),
                )
            }
            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
        }
    }
}
