package com.qiradev.cloudnest.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.qiradev.cloudnest.R
import com.qiradev.cloudnest.engine.security.UnlockManager
import com.qiradev.cloudnest.engine.security.UnlockResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Unlock screen (specs/09 screen 2, specs/08 §2): every app open asks for the
 * password. Five wrong answers buy a 30-second wait — the button disables and
 * counts down. Background transfers never pass through here.
 */
@Composable
fun UnlockScreen(unlock: UnlockManager, onUnlocked: () -> Unit) {
    val scope = rememberCoroutineScope()
    var password by rememberSaveable { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var attemptsLeft by remember { mutableIntStateOf(5) }
    var lockedForMs by remember { mutableIntStateOf(0) }

    // Lockout countdown.
    LaunchedEffect(lockedForMs) {
        var remaining = lockedForMs
        while (remaining > 0) {
            delay(1000)
            remaining -= 1000
            lockedForMs = remaining
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(96.dp))
        Text(stringResource(R.string.unlock_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; errorRes = null },
            label = { Text(stringResource(R.string.setup_password_hint)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = lockedForMs <= 0 && !checking,
            modifier = Modifier.fillMaxWidth(),
        )
        errorRes?.let { res ->
            Spacer(Modifier.height(8.dp))
            Text(stringResource(res), color = MaterialTheme.colorScheme.error)
        }
        if (lockedForMs > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.unlock_locked_out, (lockedForMs + 999) / 1000),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(16.dp))
        if (checking) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = {
                    checking = true
                    scope.launch {
                        when (val result = unlock.verify(password)) {
                            UnlockResult.Unlocked -> onUnlocked()
                            is UnlockResult.WrongPassword -> {
                                attemptsLeft = result.attemptsLeft
                                errorRes = R.string.unlock_wrong_password
                            }
                            is UnlockResult.LockedOut -> {
                                lockedForMs = result.retryAfterMs.toInt()
                                password = ""
                            }
                            UnlockResult.NotConfigured -> {
                                errorRes = R.string.label_error_generic
                            }
                        }
                        checking = false
                    }
                },
                enabled = password.isNotEmpty() && lockedForMs <= 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.unlock_action)) }
        }
    }
}
