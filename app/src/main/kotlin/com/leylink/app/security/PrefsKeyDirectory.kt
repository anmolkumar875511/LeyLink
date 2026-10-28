package com.leylink.app.security

import android.content.Context
import com.leylink.core.model.NodeId
import com.leylink.core.port.KeyDirectory
import com.leylink.core.port.PinResult
import java.util.concurrent.ConcurrentHashMap

/** Trust-on-first-use pins, persisted in app-private SharedPreferences so they survive a restart. */
class PrefsKeyDirectory(context: Context) : KeyDirectory {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val pins = ConcurrentHashMap<String, String>().also { map ->
        prefs.all.forEach { (origin, key) -> if (key is String) map[origin] = key }
    }

    override suspend fun pin(origin: NodeId, publicKey: ByteArray): PinResult {
        val id = origin.toString()
        val key = publicKey.joinToString("") { "%02x".format(it) }
        val existing = pins.putIfAbsent(id, key)
        return when {
            existing == null -> {
                prefs.edit().putString(id, key).apply()
                PinResult.NEW
            }
            existing == key -> PinResult.KNOWN
            else -> PinResult.MISMATCH
        }
    }

    override suspend fun count(): Int = pins.size

    private companion object {
        const val PREFS = "leylink_pinned_keys"
    }
}
