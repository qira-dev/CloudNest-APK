package com.qiradev.cloudnest.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qiradev.cloudnest.AppServices
import com.qiradev.cloudnest.R
import com.qiradev.cloudnest.data.entity.Providers
import kotlinx.coroutines.launch

/**
 * Connect wizard (specs/09 screen 10, specs/04 §2): provider pick → consent
 * explanation → browser sign-in → redirect back into the app (MainActivity
 * forwards `cloudnest://oauth`). SKELETON by plan: the Drive flow is fully
 * wired, but `GoogleDriveConfig.clientId` stays empty until the owner
 * registers the Android OAuth client — until then starting a connection fails
 * honestly with the "not configured" message, and the other providers show as
 * not yet available (specs/04 orders Drive first).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectWizardScreen(
    onComplete: (accountId: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppServices.db(context) }
    var connecting by rememberSaveable { mutableStateOf(false) }
    var messageRes by rememberSaveable { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_connect)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                stringResource(R.string.connect_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            ProviderCard(
                provider = Providers.GOOGLE_DRIVE,
                available = true,
                onClick = {
                    connecting = true
                    messageRes = null
                    scope.launch {
                        try {
                            val url = AppServices.connectionManager(db).startConnection(Providers.GOOGLE_DRIVE)
                            // Hand off to the browser/external app; the provider
                            // redirects back via cloudnest://oauth (specs/04 §2).
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }.onFailure {
                                messageRes = R.string.connect_no_browser
                            }
                        } catch (e: Exception) {
                            // Empty clientId (owner registration pending) lands here.
                            messageRes = R.string.connect_not_configured
                        } finally {
                            connecting = false
                        }
                    }
                },
            )
            listOf(Providers.DROPBOX, Providers.ONEDRIVE, Providers.PCLOUD, Providers.MEGA).forEach { provider ->
                ProviderCard(provider = provider, available = false, onClick = {})
            }

            if (connecting) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.connect_opening))
                }
            }
            messageRes?.let { res ->
                Spacer(Modifier.height(16.dp))
                Text(res.let { stringResource(it) }, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ProviderCard(provider: String, available: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), onClick = onClick, enabled = available) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(providerNameRes(provider)),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(if (available) R.string.connect_provider_ready else R.string.connect_provider_coming),
                style = MaterialTheme.typography.bodySmall,
                color = if (available) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
