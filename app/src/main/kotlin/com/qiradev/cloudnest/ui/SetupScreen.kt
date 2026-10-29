package com.qiradev.cloudnest.ui

import android.net.Uri
import com.qiradev.cloudnest.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * First-run setup (specs/09 screen 1, specs/08 §2): set the unlock password —
 * or restore the archive from a manual backup instead. No unlock hash is
 * stored until the user chooses one; there is no way to skip the gate.
 */
@Composable
fun SetupScreen(
    onSetup: (String) -> Unit,
    onRestore: (Uri) -> Unit,
    busy: Boolean,
    event: BackupUiEvent?,
    onClearEvent: () -> Unit,
    scope: CoroutineScope,
) {
    val context = LocalContext.current
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    var restoring by remember { mutableStateOf(false) }

    val importSource = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onRestore(uri) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.setup_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text(stringResource(R.string.setup_password_hint)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it; error = null },
            label = { Text(stringResource(R.string.setup_confirm_hint)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { res ->
            Spacer(Modifier.height(8.dp))
            Text(stringResource(res), color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        if (busy) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = {
                    when {
                        password.isEmpty() -> error = R.string.setup_error_empty
                        password != confirm -> error = R.string.setup_error_mismatch
                        else -> onSetup(password)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.setup_set_unlock)) }
        }

        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.setup_restore_lead), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { importSource.launch(arrayOf("*/*")) }, enabled = !busy) {
            Text(stringResource(R.string.action_import_backup))
        }

        event?.let { e ->
            when (e) {
                is BackupUiEvent.BackupFailed -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.backup_invalid,
                            e.reason.substringAfter("invalid backup: "),
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                BackupUiEvent.RestoreDone -> {
                    // The ledger was replaced under this process — restart cleanly.
                    if (!restoring) {
                        restoring = true
                        onClearEvent()
                        scope.launch {
                            kotlinx.coroutines.delay(800)
                            restartApp(context)
                        }
                    }
                }
                else -> {}
            }
        }
        if (restoring) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.backup_restore_done), color = MaterialTheme.colorScheme.primary)
        }
    }
}
