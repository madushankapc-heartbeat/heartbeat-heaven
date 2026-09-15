package com.heartbeatheaven.app

import android.content.Intent
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
    var showDeleteDialog by remember { mutableStateOf(false) }
    var signup by remember { mutableStateOf(false) }
    var phoneMode by remember { mutableStateOf(false) }
    var resetMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var identifier by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var ageText by remember { mutableStateOf("") }
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

    if (showDeleteDialog && session != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) showDeleteDialog = false },
            title = { Text("Delete account?") },
            text = { Text("This will permanently delete your HEARTBEAT HEAVEN account and sign you out. This action cannot be undone.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    message = null
                    error = null
                    val accessToken = session!!.accessToken
                    Thread {
                        val result = api.deleteMyAccount(accessToken)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            busy = false
                            result.onSuccess {
                                showDeleteDialog = false
                                session = null
                                message = it
                            }.onFailure {
                                error = it.message ?: "Could not delete your account."
                            }
                        }
                    }.start()
                }) { Text(if (busy) "Deleting..." else "Delete permanently", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Profile", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Email and mobile number are private. Other users see your username only.", color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (session != null) {
            val p = session!!.profile
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.username, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Gender: ${p.gender.replaceFirstChar { it.uppercase() }}")
                    Text("Age: ${p.age ?: "Not available"}")
                    Text("Email: ${p.email ?: "Not available"}")
                    Text("Mobile: ${p.phone ?: "Not available"}")
                    Text("Your login contacts are never shown publicly.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.isAdmin) {
                        Button(onClick = { context.startActivity(Intent(context, StudioActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("🎵 Open Studio") }
                    }
                    Button(enabled = !busy, onClick = {
                        busy = true
                        Thread {
                            api.signOut()
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                session = null
                                busy = false
                                message = "You are signed out."
                            }
                        }.start()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Log out") }
                    OutlinedButton(enabled = !busy, onClick = {
                        message = null
                        error = null
                        showDeleteDialog = true
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete account", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        } else if (resetMode) {
            Text("Reset password", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Enter the email address linked to your account. We will send a secure password reset link.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && email.contains("@"), onClick = {
                busy = true; message = null; error = null
                Thread {
                    val result = api.requestPasswordReset(email)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        busy = false
                        result.onSuccess { message = it }.onFailure { error = it.message ?: "Could not send reset email." }
                    }
                }.start()
            }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Please wait..." else "Send reset link") }
            TextButton(onClick = { resetMode = false; message = null; error = null }) { Text("Back to login") }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { phoneMode = false; signup = false; message = null; error = null }, Modifier.weight(1f)) { Text("Email") }
                OutlinedButton(onClick = { phoneMode = true; signup = false; message = null; error = null }, Modifier.weight(1f)) { Text("Phone") }
            }

            if (phoneMode) {
                Text("Phone account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Sign in with your phone number or username and password.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { signup = false; message = null; error = null }, Modifier.weight(1f)) { Text("Log in") }
                OutlinedButton(onClick = { signup = true; message = null; error = null }, Modifier.weight(1f)) { Text("Create account") }
            }

            if (signup) {
                Text(if (phoneMode) "Create phone account" else "Create your HEARTBEAT HEAVEN account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (phoneMode) {
                    OutlinedTextField(phone, { phone = it }, label = { Text("Mobile number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ageText, { value -> ageText = value.filter { it.isDigit() }.take(3) }, label = { Text("Age") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("No SMS or OTP verification is used. Use a strong password.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(phone, { phone = it }, label = { Text("Mobile number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ageText, { value -> ageText = value.filter { it.isDigit() }.take(3) }, label = { Text("Age") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Your mobile number is collected for account safety and administration. It is not used for login or SMS verification.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth()) {
                    Row(Modifier.weight(1f)) { RadioButton(gender == "male", { gender = "male" }); Text("Male", Modifier.padding(top = 12.dp)) }
                    Row(Modifier.weight(1f)) { RadioButton(gender == "female", { gender = "female" }); Text("Female", Modifier.padding(top = 12.dp)) }
                }
            } else {
                if (phoneMode) {
                    OutlinedTextField(identifier, { identifier = it }, label = { Text("Phone number or username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }

            OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val age = ageText.toIntOrNull()
            val validPhoneSignup = username.trim().length >= 3 && phone.trim().length >= 7 && age != null && age in 13..120
            val enabled = if (phoneMode) {
                !busy && password.length >= 6 && if (signup) validPhoneSignup else identifier.trim().length >= 3
            } else {
                !busy && password.length >= 6 && if (signup) (email.contains("@") && username.trim().length >= 3 && phone.trim().length >= 7 && age != null && age in 13..120) else email.contains("@")
            }
            Button(enabled = enabled, onClick = {
                busy = true; message = null; error = null
                Thread {
                    val result = when {
                        phoneMode && signup -> api.signUpPhone(phone, password, username, gender, age!!).map { "Account created successfully. Welcome, ${it.profile.username}." }
                        phoneMode -> api.signInPhone(identifier, password).map { "Welcome, ${it.profile.username}." }
                        signup -> api.signUp(email, password, username, gender, phone, age!!)
                        else -> api.signIn(email, password).map { "Welcome, ${it.profile.username}." }
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        busy = false
                        result.onSuccess { text ->
                            message = text
                            session = api.currentSession()
                        }.onFailure { error = it.message ?: "Authentication failed." }
                    }
                }.start()
            }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Please wait..." else if (signup) "Create account" else "Log in") }
            if (!phoneMode && !signup) TextButton(onClick = { resetMode = true; message = null; error = null }) { Text("Forgot password?") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
