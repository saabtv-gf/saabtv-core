package com.saab.tv.remote_input

import com.saab.tv.domain.HubShape
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException

/**
 * Singleton manager for encrypted Hub artwork pairing.
 * Manages the cloud session lifecycle for the GitHub Pages portal
 * where users can upload images for multiple hub items at once.
 */
object HubServerManager {

    private var session: CloudPairingSession? = null

    /**
     * Start the Bulk Hub upload server.
     *
     * @param items List of items to manage
     * @param shape The shape constraint for all items
     * @param onImageReceived Callback when an image is uploaded for a specific ID
     * @return The URL string for QR code generation, or null on failure
     */
    suspend fun startBulkServer(
        items: List<com.saab.tv.data.model.HubRowItemEntity>,
        shape: HubShape,
        onImageReceived: (String, ByteArray) -> Unit,
        onImageDeleted: ((String) -> Unit)? = null
    ): String? {
        stopServer()

        val ids = items.map { it.configUniqueId }.toSet()
        val manifest = JsonObject().apply {
            addProperty("shape", shape.name)
            addProperty("canDelete", onImageDeleted != null)
            add("items", JsonArray().apply { items.forEach { item -> add(JsonObject().apply {
                addProperty("id", item.configUniqueId); addProperty("title", item.title)
                addProperty("hasImage", item.customImageUrl != null)
            }) } })
        }
        val pairing = CloudPairingSession("hub", manifest) { message ->
            val id = message.get("item").asString
            require(id in ids)
            if (message.get("delete")?.asBoolean == true) {
                require(onImageDeleted != null); onImageDeleted(id)
            } else onImageReceived(id, pairingImage(message.get("image").asString))
        }
        session = pairing
        return try { pairing.open(); pairing.url }
        catch (e: CancellationException) { pairing.close(); throw e }
        catch (_: Exception) { pairing.close(); null }
    }

    /**
     * Stop the Hub upload server.
     */
    fun stopServer() {
        session?.close()
        session = null
    }

}
