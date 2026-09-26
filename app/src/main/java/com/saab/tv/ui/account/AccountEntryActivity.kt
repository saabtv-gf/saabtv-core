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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.saab.tv.MainActivity
import com.saab.tv.data.account.*
import com.saab.tv.di.DatabaseModule
import com.saab.tv.ui.addons.VoidButton
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
                            VoidButton(text = "Import Existing Profiles", onClick = { importLegacy() }, isPrimary = true)
                            VoidButton(text = "Start Fresh", onClick = { openApp() })
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                        authenticated -> Column(Modifier.widthIn(max = 560.dp).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Account Data Could Not Be Loaded", style = MaterialTheme.typography.headlineSmall)
                            Text(error ?: "Please check your connection.")
                            VoidButton(text = "Retry", onClick = { prepare() }, isPrimary = true)
                            if (offlineAllowed) VoidButton(text = "Continue With Local Data", onClick = { openApp() })
                            VoidButton(text = "Sign Out", onClick = {
                                lifecycleScope.launch { auth.signOut(); authenticated = false; error = null }
                            })
                        }
                        else -> AccountLoginForm(auth, error, onSubmit = { username, password, signup ->
                            busy = true; error = null
                            lifecycleScope.launch {
                                try { auth.authenticate(username, password, signup); prepare() }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = friendlyError(e); busy = false }
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
private fun AccountLoginForm(auth: AccountAuthManager, error: String?, onSubmit: (String, String, Boolean) -> Unit) {
    var signup by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var availability by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf(false) }
    val usernameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { usernameFocus.requestFocus() }
    LaunchedEffect(username, signup) {
        available = false; availability = null
        if (!signup || AccountCredentials.usernameError(username) != null) return@LaunchedEffect
        availability = "Checking Username…"
        delay(500)
        try {
            available = auth.usernameAvailable(username)
            availability = if (available) "Username Available" else "Username Already Exists"
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { availability = "Unable To Check Username. Please Retry." }
    }
    Column(Modifier.widthIn(max = 560.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.weight(1f))
        Text("Saab TV", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Text(if (signup) "Create Your Account" else "Welcome Back", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(value = username, onValueChange = { username = it.take(32) }, label = { Text("Username") },
            singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(usernameFocus),
            supportingText = { Text(availability ?: "3–32 characters: letters, numbers, underscores. Case-insensitive.") })
        OutlinedTextField(value = password, onValueChange = { password = it.take(128) }, label = { Text("Password") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        if (signup) {
            Text("At least 8 characters (Neon requirement), including uppercase, lowercase, a number and a special character.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = confirm, onValueChange = { confirm = it.take(128) }, label = { Text("Confirm Password") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Text("No email recovery is available yet. Keep your password safe.", style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val valid = AccountCredentials.usernameError(username) == null && password.isNotEmpty() &&
            (!signup || (available && AccountCredentials.passwordError(password) == null && password == confirm))
        VoidButton(text = if (signup) "Create Account" else "Log In", onClick = {
            if (valid) { val secret = password; password = ""; confirm = ""; onSubmit(username, secret, signup) }
        }, isPrimary = true, enabled = valid, modifier = Modifier.fillMaxWidth())
        if (signup && AccountCredentials.passwordError(password) != null && password.isNotEmpty()) {
            Text(AccountCredentials.passwordError(password).orEmpty(), color = MaterialTheme.colorScheme.error)
        }
        VoidButton(text = if (signup) "Already Have An Account? Log In" else "New Here? Create An Account",
            onClick = { signup = !signup; password = ""; confirm = "" })
        Spacer(Modifier.weight(1f))
    }
}
