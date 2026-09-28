package com.saab.tv.remote_input

import android.net.Uri
import com.google.gson.JsonObject
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
        val manifest = JsonObject().apply {
            if (mode == RemoteInputMode.SECRET) addProperty("inputKind", "secret")
        }
        val pairing = CloudPairingSession(tool, manifest) { message ->
            val value = message.get("value").asString.trim()
            val maxLength = when (mode) {
                RemoteInputMode.URL -> 2048
                RemoteInputMode.SEARCH -> 200
                RemoteInputMode.SECRET -> 256
            }
            require(value.isNotEmpty() && value.length <= maxLength)
            if (mode == RemoteInputMode.URL) require(Uri.parse(value).scheme?.lowercase() in listOf("http", "https"))
            if (mode == RemoteInputMode.SECRET) require(value.none(Char::isWhitespace))
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
