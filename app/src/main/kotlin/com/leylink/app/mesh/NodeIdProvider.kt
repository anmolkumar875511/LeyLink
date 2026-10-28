package com.leylink.app.mesh

import android.content.Context
import com.leylink.core.model.NodeId
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** This phone's identity in the mesh: 8 random bytes created once and kept in app preferences. */
@Singleton
class NodeIdProvider @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val nodeId: NodeId by lazy { loadOrCreate() }

    private fun loadOrCreate(): NodeId {
        val saved = prefs.getString(KEY, null)?.let { runCatching { NodeId.fromHex(it) }.getOrNull() }
        if (saved != null) return saved
        val fresh = NodeId.random()
        prefs.edit().putString(KEY, fresh.toString()).apply()
        return fresh
    }

    private companion object {
        const val PREFS = "leylink"
        const val KEY = "node_id"
    }
}
