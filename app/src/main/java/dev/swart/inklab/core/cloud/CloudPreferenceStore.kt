package dev.swart.inklab.core.cloud

import android.content.Context

class CloudPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("cloud_preferences_v1", Context.MODE_PRIVATE)

    fun load(): CloudPreferences = CloudPreferences(
        accountId = prefs.getString("accountId", null),
        rootFileId = prefs.getString("rootFileId", null),
        selectedDocumentIds = prefs.getStringSet("selectedDocumentIds", emptySet()).orEmpty().toSet(),
        wifiOnly = prefs.getBoolean("wifiOnly", true),
        includeAudio = prefs.getBoolean("includeAudio", true),
        paused = prefs.getBoolean("paused", false)
    )

    fun save(value: CloudPreferences) {
        prefs.edit()
            .putString("accountId", value.accountId)
            .putString("rootFileId", value.rootFileId)
            .putStringSet("selectedDocumentIds", value.selectedDocumentIds)
            .putBoolean("wifiOnly", value.wifiOnly)
            .putBoolean("includeAudio", value.includeAudio)
            .putBoolean("paused", value.paused)
            .apply()
    }

    fun unlink(): CloudPreferences {
        val old = load()
        val cleared = old.copy(accountId = null, rootFileId = null, paused = true)
        save(cleared)
        return cleared
    }
}
