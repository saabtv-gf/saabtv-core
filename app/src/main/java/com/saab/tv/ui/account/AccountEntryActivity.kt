package com.saab.tv.ui.account

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.saab.tv.MainActivity
import com.saab.tv.data.account.*
import com.saab.tv.di.DatabaseModule
import com.saab.tv.ui.components.SetupButton
import com.saab.tv.ui.components.SetupHeader
import com.saab.tv.ui.theme.SaabTvTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.IOException
import javax.inject.Inject

/** Launcher gate: no account-owned repository/ViewModel exists until sign-in and restore finish. */
@AndroidEntryPoint
class AccountEntryActivity : ComponentActivity() {
    @Inject lateinit var auth: AccountAuthManager
    private var busy by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var importOffered by mutableStateOf(false)
    private var offlineAllowed by mutableStateOf(false)
    private var authenticated by mutableStateOf(false)
    private var signingIn by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SaabTvTheme {
                BackHandler { finish() }
                Box(Modifier.fillMaxSize().background(Color(0xFF07101F)), contentAlignment = Alignment.Center) {
                    when {
                        busy -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(); Spacer(Modifier.height(20.dp))
                            Text("Opening Your Account…", color = Color.White)
                        }
                        importOffered -> Column(Modifier.widthIn(max = 520.dp).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Bring Your Profiles To This Account", style = MaterialTheme.typography.headlineSmall)
                            Text("Copy the existing profiles, settings, watchlists and playback progress on this TV. The original local data will be kept.")
                            SetupButton(text = "Import Profiles", onClick = { importLegacy() }, primary = true, modifier = Modifier.fillMaxWidth())
                            SetupButton(text = "Start Fresh", onClick = { openApp() }, modifier = Modifier.fillMaxWidth())
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                        authenticated -> Column(Modifier.widthIn(max = 560.dp).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Account Data Could Not Be Loaded", style = MaterialTheme.typography.headlineSmall)
                            Text(error ?: "Please check your connection.")
                            SetupButton(text = "Try Again", onClick = { prepare() }, primary = true, modifier = Modifier.fillMaxWidth())
                            if (offlineAllowed) SetupButton(text = "Continue Offline", onClick = { openApp() }, modifier = Modifier.fillMaxWidth())
                            SetupButton(text = "Sign Out", onClick = {
                                lifecycleScope.launch { auth.signOut(); authenticated = false; error = null }
                            })
                        }
                        else -> AccountLoginForm(auth, error, signingIn, onSubmit = { username, password, signup ->
                            if (signingIn) return@AccountLoginForm
                            signingIn = true; error = null
                            lifecycleScope.launch {
                                try { auth.authenticate(username, password, signup); prepare() }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = friendlyError(e); busy = false }
                                finally { signingIn = false }
                            }
                        })
                    }
                }
            }
        }
        lifecycleScope.launch {
            if (!auth.hasSession) { busy = false; return@launch }
            try {
                if (auth.validateSession()) prepare() else busy = false
            } catch (e: CancellationException) { throw e }
            catch (e: IOException) {
                authenticated = auth.hasSession
                offlineAllowed = withContext(Dispatchers.IO) { localProfilesExist() }
                error = friendlyError(e); busy = false
            }
        }
    }

    private fun prepare() {
        busy = true; error = null; authenticated = true
        lifecycleScope.launch {
            try {
                AccountSyncManager.prepareBeforeOpeningApp(applicationContext, auth)
                importOffered = withContext(Dispatchers.IO) { !localProfilesExist() && AccountLegacyImport.isAvailable(applicationContext) }
                if (!importOffered) openApp() else busy = false
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                offlineAllowed = withContext(Dispatchers.IO) { localProfilesExist() }
                error = friendlyError(e); busy = false
            }
        }
    }

    private fun localProfilesExist(): Boolean {
        if (AccountStorage.userId(applicationContext) == null) return false
        val db = DatabaseModule.provideDatabase(applicationContext)
        return try { db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM profiles").use { it.moveToFirst(); it.getInt(0) > 0 } }
        finally { db.close() }
    }

    private fun importLegacy() {
        busy = true
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { AccountLegacyImport.import(applicationContext) }; openApp() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { busy = false; error = "Import failed. Your original local profiles are still safe." }
        }
    }

    private fun openApp() { startActivity(Intent(this, MainActivity::class.java)); finish() }
    private fun friendlyError(e: Exception): String = when (e) {
        is AccountApiException -> e.message.orEmpty()
        is IOException -> "${e.message?.takeIf { !it.contains("http") }?.take(180) ?: "Unable to connect to Neon. Please try again."}"
        else -> "Account data could not be unlocked. Please sign in again or retry. Local data has been preserved."
    }
}

@Composable
private fun AccountLoginForm(auth: AccountAuthManager, error: String?, busy: Boolean, onSubmit: (String, String, Boolean) -> Unit) {
    var signup by rememberSaveable { mutableStateOf(false) }
    var remote by remember { mutableStateOf(false) }
    if (remote) AccountRemoteDialog(auth, signup, onDismiss = { remote = false }, onSubmit = onSubmit)
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var availability by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf(false) }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val submitFocus = remember { FocusRequester() }
    var availabilityRetry by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { withFrameNanos { }; usernameFocus.requestFocus() }
    LaunchedEffect(username, signup, availabilityRetry) {
        available = false; availability = null
        if (!signup || AccountCredentials.usernameError(username) != null) return@LaunchedEffect
        availability = "Checking availability…"
        delay(500)
        try {
            available = auth.usernameAvailable(username)
            availability = if (available) "Username available" else "This username is already taken"
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { availability = "Could not check availability" }
    }
    val valid = AccountCredentials.usernameError(username) == null && password.isNotEmpty() &&
        (!signup || (available && AccountCredentials.passwordError(password) == null && password == confirm))
    val submit = { if (valid && !busy) onSubmit(username, password, signup) }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(horizontal = 40.dp, vertical = 24.dp)) {
        val wide = maxWidth >= 760.dp
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            if (wide) Column(Modifier.weight(1f)) {
                Text("Saab TV", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold), color = Color.White)
                Spacer(Modifier.height(20.dp))
                Text("Your Cinema.\nYour Account.", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Spacer(Modifier.height(14.dp))
                Text("Keep your profiles, watchlist and progress together. Your cloud backup is encrypted on this TV.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp), color = Color(0xFFAAB7CC))
            }
            Box(Modifier.weight(1.2f), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SetupHeader(if (signup) "Create Your Account" else "Welcome Back",
                        if (signup) "One account for all your profiles." else "Sign in to continue your story.", if (wide) "ACCOUNT" else "SAAB TV · ACCOUNT")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SetupButton("Sign In", { signup = false; confirm = ""; passwordVisible = false; confirmVisible = false },
                            Modifier.weight(1f), primary = !signup, enabled = !busy)
                        SetupButton("Create Account", { signup = true; passwordVisible = false; confirmVisible = false },
                            Modifier.weight(1f), primary = signup, enabled = !busy)
                    }
                    SetupButton(if (signup) "Create Account With Phone" else "Sign In With Phone", { remote = true },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy, compact = true)
                    OutlinedTextField(value = username, onValueChange = { username = it.take(32) }, label = { Text("Username") },
                        enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(usernameFocus),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                        supportingText = { Text(availability ?: if (signup) "3–32 letters, numbers or underscores" else "Usernames are not case-sensitive",
                            color = if (available && signup) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) })
                    if (availability == "Could not check availability") SetupButton("Retry Username Check", { availabilityRetry++ }, enabled = !busy, compact = true)
                    OutlinedTextField(value = password, onValueChange = { password = it.take(128) }, label = { Text("Password") },
                        enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (signup) ImeAction.Next else ImeAction.Done),
                        keyboardActions = KeyboardActions(onNext = { confirmFocus.requestFocus() }, onDone = { submit() }))
                    // An independent focus target: text fields/TV IMEs can consume
                    // D-pad events aimed at a nested trailing button.
                    SetupButton(if (passwordVisible) "Hide Password" else "Show Password", { passwordVisible = !passwordVisible },
                        enabled = !busy, compact = true, modifier = Modifier.fillMaxWidth())
                    if (signup) {
                        Text("8+ characters · uppercase · lowercase · number · symbol", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = confirm, onValueChange = { confirm = it.take(128) }, label = { Text("Confirm Password") },
                            enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(confirmFocus),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            isError = confirm.isNotEmpty() && confirm != password,
                            supportingText = if (confirm.isNotEmpty() && confirm != password) {{ Text("Passwords do not match") }} else null,
                            visualTransformation = if (confirmVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (valid) submitFocus.requestFocus(); submit() }))
                        SetupButton(if (confirmVisible) "Hide Confirmation" else "Show Confirmation", { confirmVisible = !confirmVisible },
                            enabled = !busy, compact = true, modifier = Modifier.fillMaxWidth())
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    SetupButton(if (busy) "Please Wait…" else if (signup) "Create Account" else "Sign In", submit,
                        modifier = Modifier.fillMaxWidth().focusRequester(submitFocus), primary = true, enabled = valid && !busy)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (signup) "Keep your password safe. Password recovery is not available yet." else "Your profiles and cloud backup stay private to your account.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
