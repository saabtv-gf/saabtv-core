package com.saab.tv.remote_input

import android.net.Uri
import kotlinx.coroutines.CancellationException

/**
 * Holds the private, short-lived GitHub Pages pairing link.
 */
data class ServerInfo(val url: String)

/**
 * Compatibility facade for text-input dialogs. Opens no local server or port.
 */
class ServerManager {

    private var session: CloudPairingSession? = null

    /**
     * Creates an encrypted cloud pairing session.
     * Returns ServerInfo on success, null on failure.
     */
    suspend fun startServer(
        mode: RemoteInputMode = RemoteInputMode.URL,
        onInputReceived: (String) -> Unit
    ): ServerInfo? {
        stopServer()
        val tool = if (mode == RemoteInputMode.SEARCH) "search" else "paste"
        val pairing = CloudPairingSession(tool) { message ->
            val value = message.get("value").asString.trim()
            require(value.isNotEmpty() && value.length <= if (tool == "search") 200 else 2048)
            if (tool == "paste") require(Uri.parse(value).scheme?.lowercase() in listOf("http", "https"))
            onInputReceived(value)
        }
        session = pairing
        return try { pairing.open(); ServerInfo(pairing.url) }
        catch (e: CancellationException) { pairing.close(); throw e }
        catch (_: Exception) { pairing.close(); null }
    }

    /**
     * Cancels polling and closes the pairing session.
     */
    fun stopServer() {
        session?.close()
        session = null
    }
}
