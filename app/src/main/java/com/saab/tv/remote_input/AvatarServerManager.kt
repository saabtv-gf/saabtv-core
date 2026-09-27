package com.saab.tv.remote_input

import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException

internal fun pairingImage(encoded: String): ByteArray {
    require(encoded.length <= 700000)
    val bytes = PairingCrypto.decode(encoded)
    require(bytes.size in 1..524288)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth in 1..1024 && bounds.outHeight in 1..1024)
    require(bounds.outMimeType in listOf("image/jpeg", "image/png", "image/webp"))
    return bytes
}

/**
 * Compatibility facade for encrypted profile-photo pairing. No local listener.
 */
class AvatarServerManager {

    private var session: CloudPairingSession? = null

    /**
     * Creates a short-lived encrypted photo pairing session.
     * Returns ServerInfo on success, null on failure.
     */
    suspend fun startServer(onImageReceived: (ByteArray) -> Unit): ServerInfo? {
        stopServer()
        val pairing = CloudPairingSession("avatar") { onImageReceived(pairingImage(it.get("image").asString)) }
        session = pairing
        return try { pairing.open(); ServerInfo(pairing.url) }
        catch (e: CancellationException) { pairing.close(); throw e }
        catch (_: Exception) { pairing.close(); null }
    }

    /**
     * Stops the running server if any.
     */
    fun stopServer() {
        session?.close()
        session = null
    }
}
