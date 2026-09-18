package com.heartbeatheaven.app

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PHONE_RECOVERY_QUESTIONS = listOf(
    "What is your mother's maiden name?",
    "What was the name of your first school?",
    "What was the name of your favorite childhood teacher?"
)

@Composable
internal fun AccountScreen(refreshTrigger: Int = 0) {
    val context = LocalContext.current
    val api = remember { AuthApi(context) }
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<AuthSession?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPhoneDeleteDialog by remember { mutableStateOf(false) }
    var showSignupChoice by remember { mutableStateOf(false) }
    var signup by remember { mutableStateOf(false) }
    var phoneMode by remember { mutableStateOf(false) }
    var resetMode by remember { mutableStateOf(false) }
    var phoneRecoveryMode by remember { mutableStateOf(false) }
    var recoveryStep by remember { mutableStateOf(0) }
    var recoveryQuestion by remember { mutableStateOf("") }
    var recoveryAnswer by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var identifier by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var ageText by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("male") }
    var recoveryQuestionInput by remember { mutableStateOf("") }
    var recoveryAnswerInput by remember { mutableStateOf("") }
    var recoveryQuestionMenuOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var profilePhotoBusy by remember { mutableStateOf(false) }
    var showEditProfile by remember { mutableStateOf(false) }
    var bioInput by remember { mutableStateOf("") }
    var visibilityInput by remember { mutableStateOf("everyone") }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && session != null) {
            profilePhotoBusy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { ProfilePictureSupport.upload(context, uri, session!!) }
                result.onSuccess { session = withContext(Dispatchers.IO) { api.refreshCurrentProfile() } ?: session; message = "Profile picture updated." }
                    .onFailure { error = it.message ?: "Could not update profile picture." }
                profilePhotoBusy = false
            }
        }
    }

    LaunchedEffect(refreshTrigger) {
        session = withContext(Dispatchers.IO) { api.currentSession() }
        if (session != null) {
            session = withContext(Dispatchers.IO) { api.refreshCurrentProfile() } ?: session
        }
        loading = false
    }
    if (loading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }

    if (showEditProfile && session != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) showEditProfile = false },
            title = { Text("Edit profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(bioInput, { bioInput = it.take(160) }, label = { Text("About / Bio") }, supportingText = { Text(bioInput.length.toString() + "/160") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    Text("Last seen", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(visibilityInput == "everyone", { visibilityInput = "everyone" }); Text("Everyone") }
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(visibilityInput == "friends", { visibilityInput = "friends" }); Text("Friends") }
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(visibilityInput == "nobody", { visibilityInput = "nobody" }); Text("Nobody") }
                }
            },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { api.updateProfile(session!!.accessToken, bioInput, visibilityInput) }
                        result.onSuccess {
                            session = withContext(Dispatchers.IO) { api.refreshCurrentProfile() } ?: session
                            showEditProfile = false
                            message = it
                        }.onFailure { error = it.message ?: "Could not update profile." }
                        busy = false
                    }
                }) { Text(if (busy) "Saving..." else "Save") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { showEditProfile = false }) { Text("Cancel") } }
        )
    }

    if (showSignupChoice) {
        AlertDialog(onDismissRequest = { showSignupChoice = false }, title = { Text("Create account") }, text = { Text("How would you like to create your HEARTBEAT HEAVEN account?") },
            confirmButton = { TextButton(onClick = { showSignupChoice = false; signup = true; phoneMode = false; resetMode = false; phoneRecoveryMode = false; message = null; error = null }) { Text("Email account") } },
            dismissButton = { TextButton(onClick = { showSignupChoice = false; signup = true; phoneMode = true; resetMode = false; phoneRecoveryMode = false; message = null; error = null }) { Text("Phone account") } })
    }

    if (showDeleteDialog && session != null) {
        AlertDialog(onDismissRequest = { if (!busy) showDeleteDialog = false }, title = { Text("Delete account?") },
            text = { Text("This will permanently delete your HEARTBEAT HEAVEN account and sign you out. This action cannot be undone.") },
            confirmButton = { TextButton(enabled = !busy, onClick = { busy = true; message = null; error = null; val accessToken = session!!.accessToken; Thread { val result = api.deleteMyAccount(accessToken); android.os.Handler(android.os.Looper.getMainLooper()).post { busy = false; result.onSuccess { showDeleteDialog = false; session = null; message = it }.onFailure { error = it.message ?: "Could not delete your account." } } }.start() }) { Text(if (busy) "Deleting..." else "Delete permanently", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { showDeleteDialog = false }) { Text("Cancel") } })
    }

    if (showPhoneDeleteDialog) {
        AlertDialog(onDismissRequest = { if (!busy) showPhoneDeleteDialog = false }, title = { Text("Delete phone account permanently?") },
            text = { Text("This permanently deletes the phone account linked to this device. You will not be able to undo this action.") },
            confirmButton = { TextButton(enabled = !busy, onClick = { busy = true; message = null; error = null; Thread { val result = api.deletePhoneAccountWithoutLogin(identifier); android.os.Handler(android.os.Looper.getMainLooper()).post { busy = false; result.onSuccess { showPhoneDeleteDialog = false; phoneRecoveryMode = false; recoveryStep = 0; session = null; message = it }.onFailure { error = it.message ?: "Could not permanently delete the account." } } }.start() }) { Text(if (busy) "Deleting..." else "Delete permanently", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { showPhoneDeleteDialog = false }) { Text("Cancel") } })
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Profile", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Email and mobile number are private. Other users see your username only.", color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (session != null) {
            val p = session!!.profile
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (p.avatarUrl.isNotBlank()) AsyncImage(model = p.avatarUrl, contentDescription = "Profile picture", modifier = Modifier.size(104.dp).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    else Surface(modifier = Modifier.size(104.dp).clip(CircleShape), tonalElevation = 2.dp) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, "Profile picture", Modifier.size(54.dp)) } }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(enabled = !profilePhotoBusy, onClick = { photoPicker.launch("image/*") }) { Text(if (profilePhotoBusy) "Uploading..." else "📷 Change photo") }
                    if (p.avatarUrl.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(enabled = !profilePhotoBusy, onClick = { profilePhotoBusy = true; scope.launch { val result = withContext(Dispatchers.IO) { ProfilePictureSupport.remove(session!!) }; result.onSuccess { session = withContext(Dispatchers.IO) { api.refreshCurrentProfile() } ?: session; message = "Profile picture removed." }.onFailure { error = it.message ?: "Could not remove profile picture." }; profilePhotoBusy = false } }) { Text("Remove") }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(p.username, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(if (p.bio.isBlank()) "Add a short bio" else p.bio, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3) }; IconButton(onClick = { bioInput = p.bio; visibilityInput = p.lastSeenVisibility; showEditProfile = true }) { Icon(Icons.Default.Edit, "Edit profile") } }
                Text("Gender: ${p.gender.replaceFirstChar { it.uppercase() }}"); Text("Age: ${p.age ?: "Not available"}"); Text("Email: ${p.email ?: "Not available"}"); Text("Mobile: ${p.phone ?: "Not available"}")
                Text("Last seen: " + when (p.lastSeenVisibility) { "nobody" -> "Hidden"; "friends" -> "Friends only"; else -> "Visible to everyone" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Your login contacts are never shown publicly.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (p.isAdmin) Button(onClick = { context.startActivity(Intent(context, StudioActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("🎵 Open Studio") }
                Button(enabled = !busy, onClick = { busy = true; Thread { api.signOut(); android.os.Handler(android.os.Looper.getMainLooper()).post { session = null; busy = false; message = "You are signed out." } } .start() }, modifier = Modifier.fillMaxWidth()) { Text("Log out") }
                OutlinedButton(enabled = !busy, onClick = { message = null; error = null; showDeleteDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete account", color = MaterialTheme.colorScheme.error) }
            } }
        } else if (resetMode) {
            Text("Reset password", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Enter the email address linked to your account. We will send a secure password reset link.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && email.contains("@"), onClick = { busy = true; message = null; error = null; Thread { val result = api.requestPasswordReset(email); android.os.Handler(android.os.Looper.getMainLooper()).post { busy = false; result.onSuccess { message = it }.onFailure { error = it.message ?: "Could not send reset email." } } }.start() }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Please wait..." else "Send reset link") }
            TextButton(onClick = { resetMode = false; message = null; error = null }) { Text("Back to login") }
        } else if (phoneRecoveryMode) {
            Text("Phone password recovery", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Recovery works only on the device linked to this phone account. No SMS or OTP is required.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(identifier, { identifier = it }, label = { Text("Phone number or username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (recoveryStep == 0) {
                Button(enabled = !busy && identifier.trim().length >= 3, onClick = { busy = true; message = null; error = null; Thread { val result = api.getPhoneRecoveryQuestion(identifier); android.os.Handler(android.os.Looper.getMainLooper()).post { busy = false; result.onSuccess { recoveryQuestion = it; recoveryStep = 1 }.onFailure { error = it.message ?: "Could not start recovery." } } }.start() }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Checking..." else "Continue with 2-Step Verification") }
            } else {
                Text("Recovery question", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(recoveryQuestion, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(recoveryAnswer, { recoveryAnswer = it }, label = { Text("Your answer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(newPassword, { newPassword = it }, label = { Text("New password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Your old password will stop working after the new password is saved.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(enabled = !busy && recoveryAnswer.trim().length >= 2 && newPassword.length >= 8, onClick = { busy = true; message = null; error = null; Thread { val result = api.resetPhonePassword(identifier, recoveryAnswer, newPassword); android.os.Handler(android.os.Looper.getMainLooper()).post { busy = false; result.onSuccess { text -> val login = api.signInPhone(identifier, newPassword); login.onSuccess { session = it; phoneRecoveryMode = false; recoveryStep = 0; recoveryAnswer = ""; newPassword = ""; message = "Password changed and you are now logged in." }.onFailure { phoneRecoveryMode = false; recoveryStep = 0; password = newPassword; message = "$text Please log in with your new password." } }.onFailure { error = it.message ?: "Could not reset your password." } } }.start() }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Updating..." else "Set new password and log in") }
            }
            OutlinedButton(enabled = !busy && identifier.trim().length >= 3, onClick = { showPhoneDeleteDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete Account Permanently", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { phoneRecoveryMode = false; recoveryStep = 0; recoveryQuestion = ""; recoveryAnswer = ""; newPassword = ""; message = null; error = null }) { Text("Back to login") }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { phoneMode = false; signup = false; message = null; error = null }, Modifier.weight(1f)) { Text("Email") }
                OutlinedButton(onClick = { phoneMode = true; signup = false; message = null; error = null }, Modifier.weight(1f)) { Text("Phone") }
            }
            if (phoneMode) { Text("Phone account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(if (signup) "Create your phone account." else "Sign in with your phone number or username and password.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (signup) {
                Text(if (phoneMode) "Create phone account" else "Create your HEARTBEAT HEAVEN account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (phoneMode) {
                    OutlinedTextField(phone, { phone = it }, label = { Text("Mobile number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ageText, { value -> ageText = value.filter { it.isDigit() }.take(3) }, label = { Text("Age") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = recoveryQuestionInput,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Recovery question") },
                            placeholder = { Text("Select a recovery question") },
                            trailingIcon = { Text(if (recoveryQuestionMenuOpen) "▲" else "▼") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = recoveryQuestionMenuOpen,
                            onDismissRequest = { recoveryQuestionMenuOpen = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            PHONE_RECOVERY_QUESTIONS.forEach { question ->
                                DropdownMenuItem(
                                    text = { Text(question) },
                                    onClick = {
                                        recoveryQuestionInput = question
                                        recoveryQuestionMenuOpen = false
                                    }
                                )
                            }
                        }
                        Spacer(
                            Modifier
                                .matchParentSize()
                                .clickable { recoveryQuestionMenuOpen = true }
                        )
                    }
                    OutlinedTextField(recoveryAnswerInput, { recoveryAnswerInput = it.take(160) }, label = { Text("Recovery answer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Choose a question and answer only you can reliably remember. The answer is stored only as a secure hash.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No SMS or OTP verification is used.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(phone, { phone = it }, label = { Text("Mobile number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ageText, { value -> ageText = value.filter { it.isDigit() }.take(3) }, label = { Text("Age") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Your mobile number is collected for account safety and administration. It is not used for login or SMS verification.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth()) { Row(Modifier.weight(1f)) { RadioButton(gender == "male", { gender = "male" }); Text("Male", Modifier.padding(top = 12.dp)) }; Row(Modifier.weight(1f)) { RadioButton(gender == "female", { gender = "female" }); Text("Female", Modifier.padding(top = 12.dp)) } }
            } else {
                if (phoneMode) OutlinedTextField(identifier, { identifier = it }, label = { Text("Phone number or username") }, singleLine = true, modifier = Modifier.fillMaxWidth()) else OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (signup) {
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val age = ageText.toIntOrNull()
                val validPhoneSignup = username.trim().length >= 3 && phone.trim().length >= 7 && age != null && age in 13..120 && recoveryQuestionInput in PHONE_RECOVERY_QUESTIONS && recoveryAnswerInput.trim().length >= 2
                val enabled = if (phoneMode) !busy && password.length >= 8 && validPhoneSignup else !busy && password.length >= 6 && email.contains("@") && username.trim().length >= 3 && phone.trim().length >= 7 && age != null && age in 13..120
                Button(enabled = enabled, onClick = { busy = true; message = null; error = null; Thread { val result = when { phoneMode -> api.signUpPhone(phone, password, username, gender, age!!, recoveryQuestionInput, recoveryAnswerInput).map { "Account created successfully. Welcome, ${it.profile.username}." }; else -> api.signUp(email, password, username, gender, phone, age!!).map { it } }; android.os.Handler(android.os.Looper.getMainLooper()).post { busy = false; result.onSuccess { text -> message = text; session = api.currentSession(); if (session == null) signup = false }.onFailure { error = it.message ?: "Account creation failed." } } }.start() }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Please wait..." else "Create account") }
                TextButton(onClick = { signup = false; message = null; error = null }) { Text("Back to login") }
            } else {
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && password.length >= 6 && if (phoneMode) identifier.trim().length >= 3 else email.contains("@"), onClick = { busy = true; message = null; error = null; Thread { val result = if (phoneMode) api.signInPhone(identifier, password).map { "Welcome, ${it.profile.username}." } else api.signIn(email, password).map { "Welcome, ${it.profile.username}." }; android.os.Handler(android.os.Looper.getMainLooper()).post { busy = false; result.onSuccess { text -> message = text; session = api.currentSession() }.onFailure { error = it.message ?: "Authentication failed." } } }.start() }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Please wait..." else "Log in") }
                OutlinedButton(enabled = !busy, onClick = { showSignupChoice = true; message = null; error = null }, modifier = Modifier.fillMaxWidth()) { Text("Create account") }
                if (phoneMode) TextButton(enabled = !busy, onClick = { phoneRecoveryMode = true; recoveryStep = 0; recoveryQuestion = ""; recoveryAnswer = ""; newPassword = ""; message = null; error = null }) { Text("Forgot your password?") }
                else TextButton(onClick = { resetMode = true; message = null; error = null }, modifier = Modifier.fillMaxWidth()) { Text("Forgot password?") }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
