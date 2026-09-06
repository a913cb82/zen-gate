package com.abrai.zengate

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abrai.zengate.policy.PoolState
import com.abrai.zengate.policy.ZenConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.gateDataStore: DataStore<Preferences> by preferencesDataStore(name = "gate")

object GateStoreKeys {
    val ENABLED = booleanPreferencesKey("enabled")
    val SESSION_EXPIRY_MS = longPreferencesKey("session_expiry_ms")
    val SESSION_PENDING = booleanPreferencesKey("session_pending")
    val POOL_SEC = longPreferencesKey("pool_sec")
    val USAGES = intPreferencesKey("usages")
    val DAY_ID = stringPreferencesKey("day_id")
    val LAST_REFILL_MS = longPreferencesKey("last_refill_ms")
    val WHITELIST = stringSetPreferencesKey("whitelist")
    val SEED_VERSION = intPreferencesKey("seed_version")
    val K_SESSION_ALLOW = longPreferencesKey("k_session_allow")
    val K_UNLOCK_POOL = longPreferencesKey("k_unlock_pool")
    val K_REFILL_AMOUNT = longPreferencesKey("k_refill_amount")
    val K_REFILL_INTERVAL = longPreferencesKey("k_refill_interval")
    val K_BASE_WAIT = longPreferencesKey("k_base_wait")
    val K_WAIT_INC = longPreferencesKey("k_wait_inc")
    val K_RESET_HOUR = intPreferencesKey("k_reset_hour")
    val K_RESET_MINUTE = intPreferencesKey("k_reset_minute")
}

/** Full engine snapshot for the in-memory mirror. */
data class EngineSnapshot(
    val poolSec: Long = 10,
    val usagesToday: Int = 0,
    val dayId: String = "",
    val lastRefillMs: Long = 0L,
    val sessionExpiryMs: Long = 0L,
    val sessionPending: Boolean = false,
) {
    fun poolState(): PoolState = PoolState(poolSec, usagesToday, dayId, lastRefillMs, sessionExpiryMs, sessionPending)
}

/** Persisted gate state: engine + whitelist + knobs (M4). */
class GateStore(
    context: Context,
) {
    // Application context: exactly one DataStore per file. Activity/Service contexts
    // would spawn rival instances that silently lose writes (M3 lesson).
    val appContext: Context = context.applicationContext
    private val app: Context = appContext

    val enabled: Flow<Boolean> =
        app.gateDataStore.data.map { it[GateStoreKeys.ENABLED] ?: true }

    val snapshot: Flow<EngineSnapshot> =
        app.gateDataStore.data.map {
            EngineSnapshot(
                poolSec = it[GateStoreKeys.POOL_SEC] ?: 10,
                usagesToday = it[GateStoreKeys.USAGES] ?: 0,
                dayId = it[GateStoreKeys.DAY_ID] ?: "",
                lastRefillMs = it[GateStoreKeys.LAST_REFILL_MS] ?: 0L,
                sessionExpiryMs = it[GateStoreKeys.SESSION_EXPIRY_MS] ?: 0L,
                sessionPending = it[GateStoreKeys.SESSION_PENDING] ?: false,
            )
        }

    val whitelist: Flow<Set<String>> =
        app.gateDataStore.data.map { it[GateStoreKeys.WHITELIST] ?: emptySet() }

    val seedVersion: Flow<Int> =
        app.gateDataStore.data.map { it[GateStoreKeys.SEED_VERSION] ?: 0 }

    private val defaults = ZenConfig()

    val config: Flow<ZenConfig> =
        app.gateDataStore.data.map {
            ZenConfig(
                unlockPoolSec = it[GateStoreKeys.K_UNLOCK_POOL] ?: defaults.unlockPoolSec,
                refillAmountSec = it[GateStoreKeys.K_REFILL_AMOUNT] ?: defaults.refillAmountSec,
                refillIntervalSec = it[GateStoreKeys.K_REFILL_INTERVAL] ?: defaults.refillIntervalSec,
                baseWaitSec = it[GateStoreKeys.K_BASE_WAIT] ?: defaults.baseWaitSec,
                waitIncrementSec = it[GateStoreKeys.K_WAIT_INC] ?: defaults.waitIncrementSec,
                sessionAllowSec = it[GateStoreKeys.K_SESSION_ALLOW] ?: defaults.sessionAllowSec,
                resetHour = it[GateStoreKeys.K_RESET_HOUR] ?: defaults.resetHour,
                resetMinute = it[GateStoreKeys.K_RESET_MINUTE] ?: defaults.resetMinute,
            )
        }

    suspend fun setEnabled(value: Boolean) {
        app.gateDataStore.edit { it[GateStoreKeys.ENABLED] = value }
    }

    suspend fun savePool(state: PoolState) {
        app.gateDataStore.edit {
            it[GateStoreKeys.POOL_SEC] = state.poolSec
            it[GateStoreKeys.USAGES] = state.usagesToday
            it[GateStoreKeys.DAY_ID] = state.dayId
            it[GateStoreKeys.LAST_REFILL_MS] = state.lastRefillWallMs
            it[GateStoreKeys.SESSION_EXPIRY_MS] = state.sessionExpiryWallMs
            it[GateStoreKeys.SESSION_PENDING] = state.sessionPending
        }
    }

    suspend fun setWhitelisted(
        pkg: String,
        listed: Boolean,
    ) {
        app.gateDataStore.edit {
            val cur = it[GateStoreKeys.WHITELIST] ?: emptySet()
            it[GateStoreKeys.WHITELIST] = if (listed) cur + pkg else cur - pkg
        }
    }

    /** Versioned seeding: newer versions add their entries to existing installs. */
    suspend fun seedDefaults(
        suggested: Set<String>,
        version: Int,
    ) {
        app.gateDataStore.edit {
            if ((it[GateStoreKeys.SEED_VERSION] ?: 0) < version) {
                it[GateStoreKeys.WHITELIST] = (it[GateStoreKeys.WHITELIST] ?: emptySet()) + suggested
                it[GateStoreKeys.SEED_VERSION] = version
            }
        }
    }

    suspend fun setKnob(
        key: Preferences.Key<Long>,
        value: Long,
    ) {
        app.gateDataStore.edit { it[key] = value }
    }

    suspend fun setKnob(
        key: Preferences.Key<Int>,
        value: Int,
    ) {
        app.gateDataStore.edit { it[key] = value }
    }
}
