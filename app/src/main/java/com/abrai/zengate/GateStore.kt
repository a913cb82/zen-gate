package com.abrai.zengate

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abrai.zengate.policy.PoolState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.gateDataStore: DataStore<Preferences> by preferencesDataStore(name = "gate")

object GateStoreKeys {
    val ENABLED = booleanPreferencesKey("enabled")
    val SESSION_EXPIRY_MS = longPreferencesKey("session_expiry_ms")
    val POOL_SEC = longPreferencesKey("pool_sec")
    val USAGES = intPreferencesKey("usages")
    val DAY_ID = stringPreferencesKey("day_id")
    val LAST_REFILL_MS = longPreferencesKey("last_refill_ms")
    val SESSION_START_MS = longPreferencesKey("session_start_ms")
    val SESSION_SCREEN_ON_MS = longPreferencesKey("session_screen_on_ms")
}

/** Full engine snapshot for the in-memory mirror. */
data class EngineSnapshot(
    val poolSec: Long = 10,
    val usagesToday: Int = 0,
    val dayId: String = "",
    val lastRefillMs: Long = 0L,
    val sessionStartMs: Long = 0L,
    val sessionScreenOnMs: Long = 0L,
) {
    fun poolState(): PoolState = PoolState(poolSec, usagesToday, dayId, lastRefillMs, sessionStartMs, sessionScreenOnMs)
}

/** Persisted gate state. Whitelist + tuning knobs join these keys in M4. */
class GateStore(
    context: Context,
) {
    // Application context: exactly one DataStore per file. Activity/Service contexts
    // would spawn rival instances that silently lose writes (M3 lesson).
    private val app: Context = context.applicationContext

    val enabled: Flow<Boolean> =
        app.gateDataStore.data.map { it[GateStoreKeys.ENABLED] ?: true }

    val sessionExpiryMs: Flow<Long> =
        app.gateDataStore.data.map { it[GateStoreKeys.SESSION_EXPIRY_MS] ?: 0L }

    val snapshot: Flow<EngineSnapshot> =
        app.gateDataStore.data.map {
            EngineSnapshot(
                poolSec = it[GateStoreKeys.POOL_SEC] ?: 10,
                usagesToday = it[GateStoreKeys.USAGES] ?: 0,
                dayId = it[GateStoreKeys.DAY_ID] ?: "",
                lastRefillMs = it[GateStoreKeys.LAST_REFILL_MS] ?: 0L,
                sessionStartMs = it[GateStoreKeys.SESSION_START_MS] ?: 0L,
                sessionScreenOnMs = it[GateStoreKeys.SESSION_SCREEN_ON_MS] ?: 0L,
            )
        }

    suspend fun setEnabled(value: Boolean) {
        app.gateDataStore.edit { it[GateStoreKeys.ENABLED] = value }
    }

    suspend fun setSessionExpiryMs(value: Long) {
        app.gateDataStore.edit { it[GateStoreKeys.SESSION_EXPIRY_MS] = value }
    }

    suspend fun savePool(state: PoolState) {
        app.gateDataStore.edit {
            it[GateStoreKeys.POOL_SEC] = state.poolSec
            it[GateStoreKeys.USAGES] = state.usagesToday
            it[GateStoreKeys.DAY_ID] = state.dayId
            it[GateStoreKeys.LAST_REFILL_MS] = state.lastRefillWallMs
            it[GateStoreKeys.SESSION_START_MS] = state.sessionStartWallMs
            it[GateStoreKeys.SESSION_SCREEN_ON_MS] = state.sessionScreenOnMs
        }
    }
}
