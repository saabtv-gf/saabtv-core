package com.saab.tv.ui.account

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.saab.tv.MainActivity
import com.saab.tv.R
import com.saab.tv.ExitConfirmationDialog
import com.saab.tv.data.account.*
import com.saab.tv.data.localization.LocalizedComponentActivity
import com.saab.tv.di.DatabaseModule
import com.saab.tv.ui.components.SetupButton
import com.saab.tv.ui.components.SetupHeader
import com.saab.tv.ui.theme.SaabTvTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.IOException
import javax.inject.Inject

/** Launcher gate: account-owned dependencies are created only after the account identity is known. */
@AndroidEntryPoint
class AccountEntryActivity : LocalizedComponentActivity() {
    @Inject lateinit var auth: AccountAuthManager
    @Inject lateinit var deviceDisplay: com.saab.tv.data.profile.DeviceDisplayPreferences
    private var displaySetup by mutableStateOf(false)
    private var busy by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var importOffered by mutableStateOf(false)
    private var offlineAllowed by mutableStateOf(false)
    private var authenticated by mutableStateOf(false)
    private var signingIn by mutableStateOf(false)
    private var openingApp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SaabTvTheme {
                var showExit by rememberSaveable { mutableStateOf(false) }
                BackHandler { showExit = true }
                Box(Modifier.fillMaxSize().background(Color(0xFF07101F)), contentAlignment = Alignment.Center) {
                    when {
                        displaySetup -> Column(Modifier.widthIn(max = 560.dp).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            val displayFocus = remember { FocusRequester() }
                            LaunchedEffect(Unit) { withFrameNanos { }; displayFocus.requestFocus() }
                            SetupHeader(stringResource(R.string.account_device_setup), stringResource(R.string.account_device_setup_description), stringResource(R.string.account_device_stage))
                            SetupButton(stringResource(R.string.account_resolution_4k), { deviceDisplay.configure(true); openApp() }, primary = true, modifier = Modifier.fillMaxWidth(), focusRequester = displayFocus)
                            SetupButton(stringResource(R.string.account_resolution_hd), { deviceDisplay.configure(false); openApp() }, modifier = Modifier.fillMaxWidth())
                        }
                        busy -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(); Spacer(Modifier.height(20.dp))
                            Text(stringResource(R.string.account_opening), color = Color.White)
                        }
                        importOffered -> Column(Modifier.widthIn(max = 520.dp).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(R.string.account_import_title), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.account_import_description))
                            SetupButton(text = stringResource(R.string.account_import_profiles), onClick = { importLegacy() }, primary = true, modifier = Modifier.fillMaxWidth())
                            SetupButton(text = stringResource(R.string.account_start_fresh), onClick = { openApp() }, modifier = Modifier.fillMaxWidth())
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                        authenticated -> AccountDataUnavailablePanel(
                            error = error,
                            offlineAllowed = offlineAllowed,
                            onRetry = ::prepare,
                            onContinueOffline = ::openApp,
                            onSignOut = {
                                busy = true
                                lifecycleScope.launch {
                                    try { auth.signOut(); AccountRestart.restart(this@AccountEntryActivity) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) {
                                        com.saab.tv.AppDiagnostics.failure(applicationContext, "Account", "Sign Out Failed", failure)
                                        error = friendlyError(failure); busy = false
                                    }
                                }
                            }
                        )
                        else -> AccountLoginForm(auth, error, signingIn, onSubmit = { username, password, signup ->
                            if (signingIn) return@AccountLoginForm
                            signingIn = true; error = null
                            lifecycleScope.launch {
                                try { auth.authenticate(username, password, signup); prepare() }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) {
                                    com.saab.tv.AppDiagnostics.failure(applicationContext, "Account", "Authentication Failed", e)
                                    error = friendlyError(e); busy = false
                                }
                                finally { signingIn = false }
                            }
                        })
                    }
                }
                if (showExit) ExitConfirmationDialog(onConfirm = { finishAffinity() }, onDismiss = { showExit = false })
            }
        }
        lifecycleScope.launch {
            if (!auth.hasSession) { busy = false; return@launch }
            try {
                if (auth.validateSession()) prepare() else busy = false
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                com.saab.tv.AppDiagnostics.failure(applicationContext, "Account", "Session Validation Failed", e)
                authenticated = auth.hasSession
                offlineAllowed = hasLocalProfilesSafely()
                if (canContinueWithLocalAccount(offlineAllowed, auth.hasSession, e)) {
                    com.saab.tv.AppDiagnostics.event(applicationContext, "Account", "Opening Local Account", "stage=session_validation")
                    openApp()
                } else { error = friendlyError(e); busy = false }
            }
        }
    }

    private fun prepare() {
        busy = true; error = null; authenticated = true
        lifecycleScope.launch {
            try {
                AccountSyncManager.prepareBeforeOpeningApp(applicationContext, auth)
                withContext(Dispatchers.IO) { com.saab.tv.data.profile.WatchThresholdUpgrade.apply(applicationContext) }
                importOffered = withContext(Dispatchers.IO) { !localProfilesExist() && AccountLegacyImport.isAvailable(applicationContext) }
                if (!importOffered) openApp() else busy = false
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                com.saab.tv.AppDiagnostics.failure(applicationContext, "Account", "Prepare Failed", e)
                offlineAllowed = hasLocalProfilesSafely()
                if (canContinueWithLocalAccount(offlineAllowed, auth.hasSession, e)) {
                    com.saab.tv.AppDiagnostics.event(applicationContext, "Account", "Opening Local Account", "stage=prepare")
                    openApp()
                } else { error = friendlyError(e); busy = false }
            }
        }
    }

    private suspend fun hasLocalProfilesSafely(): Boolean = withContext(Dispatchers.IO) {
        try { localProfilesExist() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            com.saab.tv.AppDiagnostics.failure(applicationContext, "Account", "Local Data Check Failed", failure)
            false
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
            try { withContext(Dispatchers.IO) { AccountLegacyImport.import(applicationContext); com.saab.tv.data.profile.WatchThresholdUpgrade.apply(applicationContext, force = true) }; openApp() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { busy = false; error = getString(R.string.account_import_failed) }
        }
    }

    private fun openApp() {
        if (!deviceDisplay.isConfigured()) { busy = false; importOffered = false; displaySetup = true; return }
        if (openingApp) return
        openingApp = true; displaySetup = false; busy = true
        lifecycleScope.launch {
            try {
                // Also apply the upgrade when a returning account continues offline.
                withContext(Dispatchers.IO) { com.saab.tv.data.profile.WatchThresholdUpgrade.apply(applicationContext) }
                startActivity(Intent(this@AccountEntryActivity, MainActivity::class.java)); finish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                com.saab.tv.AppDiagnostics.failure(applicationContext, "Account", "Open App Failed", failure)
                openingApp = false; busy = false; authenticated = true; error = friendlyError(failure)
            }
        }
    }
    private fun friendlyError(e: Exception): String = when (e) {
        is AccountApiException -> e.message.orEmpty()
        is IOException -> e.message?.takeIf { !it.contains("http") }?.take(180) ?: getString(R.string.account_network_error)
        is javax.crypto.AEADBadTagException -> getString(R.string.account_unlock_error)
        else -> getString(R.string.account_load_error)
    }
}

@Composable
internal fun AccountDataUnavailablePanel(
    error: String?,
    offlineAllowed: Boolean,
    onRetry: () -> Unit,
    onContinueOffline: () -> Unit,
    onSignOut: () -> Unit
) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; retryFocus.requestFocus() }
    Column(
        Modifier.widthIn(max = 560.dp).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(R.string.account_data_unavailable_title),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.testTag("account-unavailable-title")
        )
        Text(
            error ?: stringResource(R.string.account_check_connection),
            color = Color.White.copy(alpha = 0.86f)
        )
        SetupButton(
            text = stringResource(R.string.common_try_again),
            onClick = onRetry,
            primary = true,
            modifier = Modifier.fillMaxWidth(),
            focusRequester = retryFocus
        )
        if (offlineAllowed) {
            SetupButton(
                text = stringResource(R.string.account_continue_offline),
                onClick = onContinueOffline,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SetupButton(
            text = stringResource(R.string.common_sign_out),
            onClick = onSignOut
        )
    }
}

/** A previously signed-in account can use its own cached data during a temporary outage. */
internal fun canContinueWithLocalAccount(hasLocalProfiles: Boolean, hasSession: Boolean, failure: Throwable): Boolean {
    if (!hasLocalProfiles || !hasSession) return false
    return when (failure) {
        is AccountApiException -> failure.status == 429 || failure.status in 500..599
        is java.net.SocketException, is java.net.SocketTimeoutException, is java.net.UnknownHostException -> true
        else -> false
    }
}

@Composable
internal fun AccountLoginForm(auth: AccountAuthManager, error: String?, busy: Boolean, onSubmit: (String, String, Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
        availability = context.getString(R.string.account_checking_username)
        delay(500)
        try {
            available = auth.usernameAvailable(username)
            availability = context.getString(if (available) R.string.account_username_available else R.string.account_username_taken)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { availability = context.getString(R.string.account_username_check_failed) }
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
                Text(stringResource(R.string.account_brand_statement), style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.account_brand_description),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp), color = Color(0xFFAAB7CC))
            }
            Box(Modifier.weight(1.2f), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SetupHeader(
                        stringResource(if (signup) R.string.account_create_title else R.string.account_welcome_title),
                        stringResource(if (signup) R.string.account_create_description else R.string.account_signin_description),
                        ""
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SetupButton(stringResource(R.string.account_sign_in), { signup = false; confirm = ""; passwordVisible = false; confirmVisible = false },
                            Modifier.weight(1f), primary = !signup, enabled = !busy)
                        SetupButton(stringResource(R.string.account_create_account), { signup = true; passwordVisible = false; confirmVisible = false },
                            Modifier.weight(1f), primary = signup, enabled = !busy)
                    }
                    SetupButton(stringResource(if (signup) R.string.account_create_with_phone else R.string.account_signin_with_phone), { remote = true },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy, compact = true)
                    AccountCredentialField(value = username, onValueChange = { username = it.take(32) }, label = stringResource(R.string.account_username),
                        enabled = !busy, focusRequester = usernameFocus, onNext = { passwordFocus.requestFocus() },
                        supportingText = { Text(availability ?: stringResource(if (signup) R.string.account_username_signup_hint else R.string.account_username_signin_hint),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
                            color = if (available && signup) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.72f)) })
                    if (availability == context.getString(R.string.account_username_check_failed)) SetupButton(stringResource(R.string.account_retry_username_check), { availabilityRetry++ }, enabled = !busy, compact = true)
                    AccountCredentialField(value = password, onValueChange = { password = it.take(128) }, label = stringResource(R.string.account_password),
                        enabled = !busy, focusRequester = passwordFocus, password = true, visible = passwordVisible,
                        onToggleVisibility = { passwordVisible = !passwordVisible },
                        onNext = if (signup) ({ confirmFocus.requestFocus() }) else null, onDone = submit)
                    if (signup) {
                        Text(stringResource(R.string.account_password_requirements), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AccountCredentialField(value = confirm, onValueChange = { confirm = it.take(128) }, label = stringResource(R.string.account_confirm_password),
                            enabled = !busy, focusRequester = confirmFocus, password = true, visible = confirmVisible,
                            onToggleVisibility = { confirmVisible = !confirmVisible },
                            isError = confirm.isNotEmpty() && confirm != password,
                            supportingText = if (confirm.isNotEmpty() && confirm != password) {{ Text(stringResource(R.string.account_password_mismatch)) }} else null,
                            onDone = { if (valid) submitFocus.requestFocus(); submit() })
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    SetupButton(stringResource(if (busy) R.string.common_please_wait else if (signup) R.string.account_create_account else R.string.account_sign_in), submit,
                        modifier = Modifier.fillMaxWidth().focusRequester(submitFocus), primary = true, enabled = valid && !busy)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(if (signup) R.string.account_recovery_unavailable else R.string.account_privacy_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
