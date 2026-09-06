package com.abrai.zengate

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.gateDataStore: DataStore<Preferences> by preferencesDataStore(name = "gate")

object GateStoreKeys {
    val ENABLED = booleanPreferencesKey("enabled")
    val SESSION_EXPIRY_MS = longPreferencesKey("session_expiry_ms")
}

/** Persisted gate state. Whitelist + tuning knobs join these keys in M4. */
class GateStore(
    private val context: Context,
) {
    val enabled: Flow<Boolean> =
        context.gateDataStore.data.map { it[GateStoreKeys.ENABLED] ?: true }

    val sessionExpiryMs: Flow<Long> =
        context.gateDataStore.data.map { it[GateStoreKeys.SESSION_EXPIRY_MS] ?: 0L }

    suspend fun setEnabled(value: Boolean) {
        context.gateDataStore.edit { it[GateStoreKeys.ENABLED] = value }
    }

    suspend fun setSessionExpiryMs(value: Long) {
        context.gateDataStore.edit { it[GateStoreKeys.SESSION_EXPIRY_MS] = value }
    }
}
