package com.saab.tv.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** Back layers are explicit so nested UI does not rely on composition order for precedence. */
enum class AppBackPriority(val rank: Int) {
    Navigation(35),
    Screen(20),
    Route(30),
    Player(40),
    Preview(50),
    ManualPreview(55),
    Modal(60)
}

/** Main-thread registry used by the app shell's single Android Back handler. */
internal class BackNavigationDispatcher(
    private val nowNanos: () -> Long = System::nanoTime,
    private val duplicateBackWindowNanos: Long = 100_000_000L
) {
    private data class Entry(
        val priority: Int,
        val order: Long,
        var enabled: Boolean,
        var onBack: () -> Unit
    )

    private val entries = linkedMapOf<Long, Entry>()
    private var nextId = 1L
    private var nextOrder = 1L
    private var suppressSystemBackUntilNanos: Long? = null

    fun register(priority: Int, enabled: Boolean, onBack: () -> Unit): Long {
        val id = nextId++
        entries[id] = Entry(priority, nextOrder++, enabled, onBack)
        return id
    }

    fun update(id: Long, enabled: Boolean, onBack: () -> Unit) {
        entries[id]?.let { entry ->
            entry.enabled = enabled
            entry.onBack = onBack
        }
    }

    fun unregister(id: Long) {
        entries.remove(id)
    }

    fun dispatch(): Boolean {
        val target = entries.values
            .asSequence()
            .filter(Entry::enabled)
            .maxWithOrNull(compareBy<Entry> { it.priority }.thenBy { it.order })
            ?: return false
        target.onBack()
        return true
    }

    fun dispatchFromKey(onUnhandledBack: () -> Unit) {
        if (!dispatch()) onUnhandledBack()
        suppressSystemBackUntilNanos = nowNanos() + duplicateBackWindowNanos
    }

    fun dispatchFromSystem(onUnhandledBack: () -> Unit) {
        val suppressUntil = suppressSystemBackUntilNanos
        suppressSystemBackUntilNanos = null
        if (suppressUntil != null && nowNanos() <= suppressUntil) return
        if (!dispatch()) {
            onUnhandledBack()
        }
    }
}

private class BackNavigationContext(
    val dispatcher: BackNavigationDispatcher,
    val latestUnhandledBack: State<() -> Unit>
)

private val LocalBackNavigationContext = staticCompositionLocalOf<BackNavigationContext?> { null }

/** Installs the app's only Android Back callback and provides prioritized child registrations. */
@Composable
fun BackNavigationHost(
    onUnhandledBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val dispatcher = remember { BackNavigationDispatcher() }
    val latestUnhandledBack = rememberUpdatedState(onUnhandledBack)
    val context = remember(dispatcher, latestUnhandledBack) {
        BackNavigationContext(dispatcher, latestUnhandledBack)
    }

    BackHandler {
        dispatcher.dispatchFromSystem { latestUnhandledBack.value() }
    }

    CompositionLocalProvider(LocalBackNavigationContext provides context, content = content)
}

/** Registers a child Back action with the shell, falling back to AndroidX when used standalone. */
@Composable
fun AppBackHandler(
    enabled: Boolean = true,
    priority: AppBackPriority = AppBackPriority.Screen,
    onBack: () -> Unit
) {
    val context = LocalBackNavigationContext.current
    val dispatcher = context?.dispatcher
    val latestOnBack = rememberUpdatedState(onBack)

    if (dispatcher == null) {
        BackHandler(enabled = enabled) { latestOnBack.value() }
        return
    }

    val registrationId = remember(dispatcher, priority) { mutableLongStateOf(0L) }
    DisposableEffect(dispatcher, priority) {
        val id = dispatcher.register(priority.rank, enabled) { latestOnBack.value() }
        registrationId.longValue = id
        onDispose { dispatcher.unregister(id) }
    }
    SideEffect {
        if (registrationId.longValue != 0L) {
            dispatcher.update(registrationId.longValue, enabled) { latestOnBack.value() }
        }
    }
}

/** Use from a D-pad Back key preview to share the shell route and suppress duplicate system Back. */
@Composable
fun rememberAppBackKeyAction(onStandaloneBack: () -> Unit = {}): () -> Unit {
    val context = LocalBackNavigationContext.current
    val latestStandaloneBack = rememberUpdatedState(onStandaloneBack)
    return rememberUpdatedState {
        if (context == null) {
            latestStandaloneBack.value()
        } else {
            context.dispatcher.dispatchFromKey { context.latestUnhandledBack.value() }
        }
    }.value
}
