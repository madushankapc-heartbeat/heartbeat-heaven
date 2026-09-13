package com.heartbeatheaven.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AccountScreen() {
    val context = LocalContext.current
    val api = remember { AuthApi(context) }
    var session by remember { mutableStateOf<AuthSession?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var signup by remember { mutableStateOf(false) }
    var phoneMode by remember { mutableStateOf(false) }
    var resetMode by remember { mutableStateOf(false) }
    var phoneVerifyMode by remember { mutableStateOf(false) }
    var identifier by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("male") }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        session = withContext(Dispatchers.IO) { api.currentSession() }
        loading = false
    }
    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Profile", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Your email and mobile number stay private. Other users see your username only.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (session != null) {
            val p = session!!.profile
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.username, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Gender: ${p.gender.replaceFirstChar { it.uppercase() }}")
                    Text(p.email ?: p.phone ?: "Private login identifier")
                    Text("Your login contact is never shown publicly.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            Thread {
                                api.signOut()
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    session = null
                                    busy = false
                                    message = "You are signed out."
                                }
                            }.start()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Log out") }
                }
            }
        } else if (resetMode) {
            Text("Reset password", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Enter the email address linked to your account. We will send a secure password reset link.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                identifier,
                { identifier = it },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                enabled = !busy && identifier.contains("@"),
                onClick = {
                    busy = true; message = null; error = null
                    Thread {
                        val result = api.requestPasswordReset(identifier)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            busy = false
                            result.onSuccess { message = it }.onFailure { error = it.message ?: "Could not send reset email." }
                        }
                    }.start()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Please wait..." else "Send reset link") }
            TextButton(onClick = { resetMode = false; message = null; error = null }) { Text("Back to login") }
        } else if (phoneVerifyMode) {
            Text("Verify mobile", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Enter the 6-digit code sent to your mobile number.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                otp,
                { otp = it.filter(Char::isDigit).take(6) },
                label = { Text("Verification code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                enabled = !busy && otp.length == 6,
                onClick = {
                    busy = true; message = null; error = null
                    Thread {
                        val result = api.verifyPhoneSignup(identifier, otp)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            busy = false
                            result.onSuccess {
                                session = it
                                phoneVerifyMode = false
                                message = "Mobile verified. Welcome, ${it.profile.username}."
                            }.onFailure { error = it.message ?: "Could not verify the mobile number." }
                        }
                    }.start()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Verifying..." else "Verify code") }
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true; message = null; error = null
                    Thread {
                        val result = api.resendPhoneSignup(identifier)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            busy = false
                            result.onSuccess { message = it }.onFailure { error = it.message ?: "Could not resend the code." }
                        }
                    }.start()
                }
            ) { Text("Resend code") }
            TextButton(onClick = { phoneVerifyMode = false; message = null; error = null }) { Text("Back") }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { signup = false; message = null; error = null }, Modifier.weight(1f)) { Text("Log in") }
                OutlinedButton(onClick = { signup = true; message = null; error = null }, Modifier.weight(1f)) { Text("Create account") }
            }
            Row(Modifier.fillMaxWidth()) {
                Row(Modifier.weight(1f)) { RadioButton(!phoneMode, { phoneMode = false }); Text("Email", Modifier.padding(top = 12.dp)) }
                Row(Modifier.weight(1f)) { RadioButton(phoneMode, { phoneMode = true }); Text("Mobile", Modifier.padding(top = 12.dp)) }
            }
            if (signup) {
                OutlinedTextField(
                    username,
                    { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth()) {
                    Row(Modifier.weight(1f)) { RadioButton(gender == "male", { gender = "male" }); Text("Male", Modifier.padding(top = 12.dp)) }
                    Row(Modifier.weight(1f)) { RadioButton(gender == "female", { gender = "female" }); Text("Female", Modifier.padding(top = 12.dp)) }
                }
            }
            OutlinedTextField(
                identifier,
                { identifier = it },
                label = { Text(if (phoneMode) "Mobile number (+country code)" else "Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                password,
                { password = it },
                label = { Text("Password") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (phoneMode) {
                Text(
                    "Use the full international number, for example +947XXXXXXXX.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                enabled = !busy && identifier.isNotBlank() && password.length >= 6 && (!signup || username.trim().length >= 3),
                onClick = {
                    busy = true; message = null; error = null
                    Thread {
                        val result = if (signup) {
                            api.signUp(identifier, password, username, gender, phoneMode).map { text ->
                                if (phoneMode) {
                                    phoneVerifyMode = true
                                }
                                text
                            }
                        } else {
                            api.signIn(identifier, password, phoneMode).map { "Welcome, ${it.profile.username}." }
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            busy = false
                            result.onSuccess { text ->
                                message = text
                                if (!phoneVerifyMode) session = api.currentSession()
                            }.onFailure { error = it.message ?: "Authentication failed." }
                        }
                    }.start()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Please wait..." else if (signup) "Create account" else "Log in") }
            if (!signup && !phoneMode) {
                TextButton(onClick = { resetMode = true; message = null; error = null }) { Text("Forgot password?") }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
