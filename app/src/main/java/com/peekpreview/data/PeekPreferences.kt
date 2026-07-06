package com.peekpreview.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.peekpreview.util.SUPPORTED_APPS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// One DataStore for the whole app.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "peek_prefs")

/**
 * Settings:
 *  - master enabled: the overall on/off gate.
 *  - per-app enabled: one boolean per supported package (default true, so
 *    flipping the master on peeks everywhere until the user narrows it).
 *  - debug logging: dev-only node-tree dump toggle.
 *
 * A peek fires only when master AND the specific app's toggle are both on;
 * [enabledPackagesFlow] pre-computes that set for the service's hot path.
 *
 * ponytail: three flat boolean keys per app, not a serialized Map — DataStore
 * keys are already a map. No schema type needed.
 */
object PeekPreferences {
    private val MASTER = booleanPreferencesKey("peek_enabled")           // kept name for back-compat
    private val DEBUG_LOG = booleanPreferencesKey("debug_logging")
    private val NOTICE_DISMISSED = booleanPreferencesKey("notice_dismissed")
    private fun appKey(pkg: String) = booleanPreferencesKey("enabled_$pkg")

    fun masterEnabledFlow(context: Context): Flow<Boolean> =
        context.applicationContext.dataStore.data.map { it[MASTER] ?: false }

    suspend fun setMasterEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.dataStore.edit { it[MASTER] = enabled }
    }

    fun appEnabledFlow(context: Context, pkg: String): Flow<Boolean> =
        context.applicationContext.dataStore.data.map { it[appKey(pkg)] ?: true }

    suspend fun setAppEnabled(context: Context, pkg: String, enabled: Boolean) {
        context.applicationContext.dataStore.edit { it[appKey(pkg)] = enabled }
    }

    fun debugLoggingFlow(context: Context): Flow<Boolean> =
        context.applicationContext.dataStore.data.map { it[DEBUG_LOG] ?: false }

    suspend fun setDebugLogging(context: Context, enabled: Boolean) {
        context.applicationContext.dataStore.edit { it[DEBUG_LOG] = enabled }
    }

    fun noticeDismissedFlow(context: Context): Flow<Boolean> =
        context.applicationContext.dataStore.data.map { it[NOTICE_DISMISSED] ?: false }

    suspend fun setNoticeDismissed(context: Context) {
        context.applicationContext.dataStore.edit { it[NOTICE_DISMISSED] = true }
    }

    /** Set of package names currently eligible to peek (master on AND app on).
     *  Empty when the master is off. */
    fun enabledPackagesFlow(context: Context): Flow<Set<String>> =
        context.applicationContext.dataStore.data.map { p ->
            if (p[MASTER] != true) emptySet()
            else SUPPORTED_APPS.map { it.pkg }.filter { p[appKey(it)] ?: true }.toSet()
        }
}
