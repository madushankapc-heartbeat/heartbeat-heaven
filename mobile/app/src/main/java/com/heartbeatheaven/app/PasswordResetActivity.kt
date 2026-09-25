package com.heartbeatheaven.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PasswordResetActivity : ComponentActivity() {
    private var accessToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accessToken = parseAccessToken(intent?.data)
        showScreen()
    }

    private fun parseAccessToken(uri: Uri?): String? {
        val fragment = uri?.fragment.orEmpty()
        if (fragment.isBlank()) return null
        return fragment.split("&")
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index) to Uri.decode(part.substring(index + 1))
            }
            .toMap()["access_token"]
            ?.takeIf { it.isNotBlank() }
    }

    private fun showScreen() {
        val token = accessToken
        setContent {
            MaterialTheme {
                var password by remember { mutableStateOf("") }
                var confirmPassword by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf<String?>(null) }
                var error by remember { mutableStateOf<String?>(null) }

                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("HEARTBEAT HEAVEN", style = MaterialTheme.typography.headlineMedium)
                    Text("Reset password", style = MaterialTheme.typography.headlineSmall)

                    if (token == null) {
                        Text("This reset link is missing or has expired. Please request a new reset email from the app.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { finish() }) { Text("Back") }
                    } else {
                        Text("Create a new password for your HeartBeat Heaven account.")
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("New password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            label = { Text("Confirm password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled = !busy && password.length >= 8 && password == confirmPassword,
                            onClick = {
                                busy = true
                                message = null
                                error = null
                                lifecycleScope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        AuthApi(this@PasswordResetActivity).updatePassword(token, password)
                                    }
                                    busy = false
                                    result.onSuccess {
                                        message = it
                                        password = ""
                                        confirmPassword = ""
                                    }.onFailure {
                                        error = it.message ?: "Could not update the password. Please request a new reset link."
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (busy) "Updating..." else "Update password") }

                        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                        if (message != null) {
                            Button(
                                onClick = {
                                    startActivity(Intent(this@PasswordResetActivity, MainActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    })
                                    finish()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Go to login") }
                        }
                    }
                }
            }
        }
    }
}
