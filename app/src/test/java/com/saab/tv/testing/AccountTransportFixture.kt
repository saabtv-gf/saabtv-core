package com.saab.tv.testing

import android.content.Context
import android.util.Base64
import com.saab.tv.data.account.*
import com.saab.tv.data.security.SecurePreferencesResult
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Collections

/** Replace transport/storage dependencies only, not auth/cloud algorithms.
 * Plain test preferences simulate a working keystore; this does not qualify real encryption storage.
 * Private fields are localized here until production constructors expose injectable seams.
 */
class AccountTransportFixture(val context: Context, signedIn: Boolean = true) {
    val auth = AccountAuthManager(context)
    val requests = Collections.synchronizedList(mutableListOf<Request>())
    val prefs = context.getSharedPreferences("isolated_test_session", Context.MODE_PRIVATE)
    val key = ByteArray(32) { it.toByte() }
    val user = "fixture-account"
    var handler: (Request) -> Pair<Int,String> = { error("No fixture for ${it.url.encodedPath}") }
    init {
        prefs.edit().clear().commit()
        if (signedIn) prefs.edit().putString("user_id",user).putString("cookie","neon-auth.session_token=fixture")
            .putString("sync_key",Base64.encodeToString(key,Base64.NO_WRAP)).putString("username","tester").commit()
        replace("secure",SecurePreferencesResult(prefs,true)); replace("prefs",prefs)
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            requests += chain.request()
            val (code,body) = if(chain.request().url.encodedPath.endsWith("/token")) 200 to """{"token":"fixture-token"}"""
                else handler(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        replace("client",client)
        AccountStorage.setUserId(context,if(signedIn) user else null)
    }
    private fun replace(name: String, value: Any) {
        AccountAuthManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(auth,value)
    }
}
