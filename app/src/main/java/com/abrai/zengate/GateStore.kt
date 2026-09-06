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
    val POOL_SEC = longPreferencesKey("pool_sec")
    val USAGES = intPreferencesKey("usages")
    val DAY_ID = stringPreferencesKey("day_id")
    val LAST_REFILL_MS = longPreferencesKey("last_refill_ms")
    val SESSION_START_MS = longPreferencesKey("session_start_ms")
    val SESSION_SCREEN_ON_MS = longPreferencesKey("session_screen_on_ms")
    val WHITELIST = stringSetPreferencesKey("whitelist")
    val SEEDED = booleanPreferencesKey("seeded")
    val K_SESSION_ALLOW = longPreferencesKey("k_session_allow")
    val K_UNLOCK_POOL = longPreferencesKey("k_unlock_pool")
    val K_REFILL_AMOUNT = longPreferencesKey("k_refill_amount")
    val K_REFILL_INTERVAL = longPreferencesKey("k_refill_interval")
    val K_POOL_CAP = longPreferencesKey("k_pool_cap")
    val K_BASE_WAIT = longPreferencesKey("k_base_wait")
    val K_WAIT_INC = longPreferencesKey("k_wait_inc")
    val K_HARD_LIMIT = longPreferencesKey("k_hard_limit")
    val K_RESET_HOUR = intPreferencesKey("k_reset_hour")
    val K_RESET_MINUTE = intPreferencesKey("k_reset_minute")
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

    val whitelist: Flow<Set<String>> =
        app.gateDataStore.data.map { it[GateStoreKeys.WHITELIST] ?: emptySet() }

    val seeded: Flow<Boolean> =
        app.gateDataStore.data.map { it[GateStoreKeys.SEEDED] ?: false }

    private val defaults = ZenConfig()

    val config: Flow<ZenConfig> =
        app.gateDataStore.data.map {
            ZenConfig(
                unlockPoolSec = it[GateStoreKeys.K_UNLOCK_POOL] ?: defaults.unlockPoolSec,
                refillAmountSec = it[GateStoreKeys.K_REFILL_AMOUNT] ?: defaults.refillAmountSec,
                refillIntervalSec = it[GateStoreKeys.K_REFILL_INTERVAL] ?: defaults.refillIntervalSec,
                poolCapSec = it[GateStoreKeys.K_POOL_CAP] ?: defaults.poolCapSec,
                baseWaitSec = it[GateStoreKeys.K_BASE_WAIT] ?: defaults.baseWaitSec,
                waitIncrementSec = it[GateStoreKeys.K_WAIT_INC] ?: defaults.waitIncrementSec,
                sessionAllowSec = it[GateStoreKeys.K_SESSION_ALLOW] ?: defaults.sessionAllowSec,
                sessionHardLimitSec = it[GateStoreKeys.K_HARD_LIMIT] ?: defaults.sessionHardLimitSec,
                resetHour = it[GateStoreKeys.K_RESET_HOUR] ?: defaults.resetHour,
                resetMinute = it[GateStoreKeys.K_RESET_MINUTE] ?: defaults.resetMinute,
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

    suspend fun setWhitelisted(
        pkg: String,
        listed: Boolean,
    ) {
        app.gateDataStore.edit {
            val cur = it[GateStoreKeys.WHITELIST] ?: emptySet()
            it[GateStoreKeys.WHITELIST] = if (listed) cur + pkg else cur - pkg
        }
    }

    suspend fun seedDefaults(suggested: Set<String>) {
        app.gateDataStore.edit {
            if (it[GateStoreKeys.SEEDED] != true) {
                it[GateStoreKeys.WHITELIST] = (it[GateStoreKeys.WHITELIST] ?: emptySet()) + suggested
                it[GateStoreKeys.SEEDED] = true
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
