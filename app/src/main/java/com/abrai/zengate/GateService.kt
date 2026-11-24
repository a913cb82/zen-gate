package com.abrai.zengate

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.abrai.zengate.policy.GatePolicy
import com.abrai.zengate.policy.PoolEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Persistent foreground presence for the gate (ScreenZen-shape). Mirrors
 * [GateStore] into [GateState], tracks screen on/off for pool drain accounting
 * (sessions are wall-clock and unaffected by screen state), and hosts the
 * notification kill switch. Engine deadlines live in [GateAlarms].
 */
class GateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: GateStore
    private var lastResetHour: Int = -1
    private var lastResetMinute: Int = -1
    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_SCREEN_ON -> onScreenOn()
                    Intent.ACTION_USER_PRESENT -> onPhoneUnlock()
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        store = GateStore(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Zen Gate", NotificationManager.IMPORTANCE_MIN),
        )
        startForeground(
            NOTIFICATION_ID,
            buildNotification(gateActive()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        scope.launch {
            store.transparent.collect { GateState.transparentPkgs = it }
        }
        scope.launch {
            // Boot-path coverage for the seeded transparent set (MainActivity
            // seeds the same version on UI open; versioned, so idempotent).
            store.seedTransparent(SeedTransparentPackages, SEED_TRANSPARENT_VERSION_CURRENT)
        }
        scope.launch {
            combine(
                store.pauseUntil,
                store.snapshot,
                store.config,
                store.whitelist,
                GateState.lastEventPkg,
            ) { pauseUntil, snap, cfg, list, eventPkg ->
                val fg = eventPkg?.takeUnless { it == packageName } ?: GateState.lastGatedApp.value
                GateState.pauseUntilMs = pauseUntil
                GateState.poolSec = snap.poolSec
                GateState.usagesToday = snap.usagesToday
                GateState.dayId = snap.dayId
                GateState.sessionExpiryMs = snap.sessionExpiryMs
                GateState.config = cfg
                GateState.userWhitelist = list
                GateState.storeLoaded = true
                if (cfg.resetHour != lastResetHour || cfg.resetMinute != lastResetMinute) {
                    lastResetHour = cfg.resetHour
                    lastResetMinute = cfg.resetMinute
                    GateAlarms.scheduleMidnight(this@GateService, lastResetHour, lastResetMinute)
                }
                // lastStatusLine updates even when notifications are denied so
                // buildNotification never renders a stale line once granted.
                val active = gateActive()
                val line = liveLine(active, snap.poolState(), cfg, list, fg)
                if (line != lastStatusLine) {
                    Log.d(
                        TAG,
                        "status line=\"$line\" showing=${BlockActivity.isShowing} fg=$fg pool=${snap.poolSec}",
                    )
                    lastStatusLine = line
                    if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        manager.notify(NOTIFICATION_ID, buildNotification(active))
                    }
                }
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    manageFreeTicker(line)
                }
            }.catch { e ->
                // An upstream store failure must not wedge storeLoaded=false
                // forever: the gate runs degraded on the last mirror instead.
                Log.e(TAG, "collector failed; gate degraded", e)
            }.collect { }
        }
        registerReceiver(
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON).apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                // Dynamic (not manifest): HyperOS SmartPower denies this broadcast
                // to background manifest receivers; the live FGS gets it directly.
                addAction(Intent.ACTION_USER_PRESENT)
            },
            RECEIVER_NOT_EXPORTED,
        )
        // Midnight is scheduled inside the collector once the store loads:
        // scheduling here would arm from GateState defaults, not persisted truth.
        scope.launch {
            // Alarms die on reboot: re-arm the pause end from persisted truth.
            // Expiry while off reads as active, so clear the stale deadline.
            val pauseUntil = store.pauseUntil.first()
            val now = System.currentTimeMillis()
            if (GatePolicy.isPaused(pauseUntil, now)) {
                // Mirror before alarming: the collector's pauseUntil write may
                // arrive later, and the status line reads the mirror meanwhile.
                GateState.pauseUntilMs = pauseUntil
                GateAlarms.schedulePauseEnd(this@GateService, pauseUntil - now)
            } else if (pauseUntil != 0L) {
                GateState.pauseUntilMs = 0
                store.setPauseUntil(0)
            }
        }
        Log.d(TAG, "foreground presence active")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_PAUSE) {
            scope.launch {
                val pauseMs = GateState.config.pauseDurationSec.coerceAtLeast(1) * 1_000
                val until = System.currentTimeMillis() + pauseMs
                GateState.pauseUntilMs = until
                GateState.requestBlockDismiss()
                store.setPauseUntil(until)
                // Sessions are wall-clock: pausing only cancels the pool drain.
                GateAlarms.cancelPoolExpiry(this@GateService)
                synchronized(GateState.drainLock) { GateState.drainEnterElapsedMs = null }
                GateAlarms.schedulePauseEnd(this@GateService, pauseMs)
                Log.d(TAG, "paused until=$until")
            }
        }
        if (intent?.action == ACTION_RESUME) {
            scope.launch {
                GateState.pauseUntilMs = 0
                store.setPauseUntil(0)
                GateAlarms.cancelPauseEnd(this@GateService)
                rearmDrainFromRemainder()
                Log.d(TAG, "pause cleared; gate active")
            }
        }
        if (intent?.action == ACTION_DEADLINE) {
            val deadline = intent.getStringExtra(EXTRA_DEADLINE).orEmpty()
            // Alarm redelivery can double-fire within ms: dedup identical
            // deadlines inside 1500ms on the elapsed clock.
            val now = SystemClock.elapsedRealtime()
            synchronized(GateState.drainLock) {
                if (deadline == GateState.lastDeadline && now - GateState.lastDeadlineAt < 1_500) {
                    Log.d(TAG, "deadline deduped $deadline")
                    return START_STICKY
                }
                GateState.lastDeadline = deadline
                GateState.lastDeadlineAt = now
            }
            onDeadline(deadline)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (t: Throwable) {
            Log.e(TAG, "unregister failed", t)
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Re-arm the pool drain from the persisted remainder after a pause ends:
     * only when an app is still gated, pool remains, and no session covers it.
     */
    private fun rearmDrainFromRemainder() {
        synchronized(GateState.drainLock) {
            val snap = GateState.poolState()
            if (GateState.lastGatedPkg != null &&
                snap.poolSec > 0 &&
                !PoolEngine.hasSession(snap) &&
                GateState.drainEnterElapsedMs == null
            ) {
                GateState.drainEnterElapsedMs = SystemClock.elapsedRealtime()
                GateAlarms.schedulePoolExpiry(this, snap.poolSec * 1_000)
                Log.d(TAG, "drain re-armed pool=${snap.poolSec}")
            }
        }
    }

    private fun onScreenOff() {
        Log.d(TAG, "screen off")
        freeTicker?.cancel()
        freeTicker = null
        if (!GateState.storeLoaded) return
        val elapsed = SystemClock.elapsedRealtime()
        scope.launch {
            // Mirror-clobber window: the collector may rewrite GateState
            // between our read and our save, so persist only on actual change.
            val before = GateState.poolState()
            val settled =
                synchronized(GateState.drainLock) {
                    val snap = GateState.poolState()
                    // Pause pool drain. Sessions are wall-clock: unaffected by screen state.
                    // Write-through (not just store): the mirror must converge now, so a
                    // screen-on seconds later re-arms from the drained remainder.
                    if (GateState.lastGatedPkg != null &&
                        !PoolEngine.hasSession(snap) &&
                        GateState.drainEnterElapsedMs != null
                    ) {
                        val s = PoolEngine.settleSegment(snap, GateState.drainEnterElapsedMs, elapsed)
                        GateState.drainEnterElapsedMs = null
                        GateAlarms.cancelPoolExpiry(this@GateService)
                        if (s != null) {
                            GateState.applyPool(s)
                            Log.d(TAG, "screen off: settled pool=${s.poolSec}")
                        }
                        s
                    } else {
                        null
                    }
                }
            if (settled != null && settled != before) {
                store.savePool(settled)
            }
        }
    }

    private var lastCacheRefreshMs: Long = 0L

    private fun onPhoneUnlock() {
        if (!GateState.storeLoaded) return
        scope.launch {
            // New keyboards/launchers install while we look away: re-resolve
            // the package caches off the main thread (throttled TTL, no force),
            // so fresh surfaces classify correctly.
            val now = SystemClock.elapsedRealtime()
            if (GatePolicy.refreshDue(now, lastCacheRefreshMs, force = false)) {
                lastCacheRefreshMs = now
                ZenGateService.instance?.dropPackageCaches()
            }
            val snap = GateState.poolState()
            val cur = PoolEngine.phoneUnlock(snap, System.currentTimeMillis(), GateState.config)
            // Mirror-clobber window (see onScreenOff): persist only on change.
            if (cur != snap) {
                synchronized(GateState.drainLock) {
                    GateState.applyPool(cur)
                    // Open the drain segment now: the unlock reveal often emits no
                    // window event, and without this the grace clock never starts.
                    GateState.drainEnterElapsedMs = SystemClock.elapsedRealtime()
                    GateAlarms.schedulePoolExpiry(this@GateService, cur.poolSec * 1_000)
                }
                store.savePool(cur)
                refreshNotification()
                Log.d(TAG, "phone unlock; grace pool=${cur.poolSec}")
            }
        }
    }

    private fun onScreenOn() {
        Log.d(TAG, "screen on")
        if (!GateState.storeLoaded) return
        val elapsed = SystemClock.elapsedRealtime()
        scope.launch {
            // No save here: the snapshot is unchanged, and persisting it would
            // clobber a racing collector write (mirror-clobber window).
            val snap = GateState.poolState()
            // Screen on but still locked (lockscreen camera/torch lighting the
            // screen): never re-arm the drain while the keyguard is up.
            val keys = getSystemService(KeyguardManager::class.java)
            if (keys.isKeyguardLocked) return@launch
            refreshNotification()
            synchronized(GateState.drainLock) {
                val cur = GateState.poolState()
                if (!PoolEngine.hasSession(cur) &&
                    GateState.lastGatedPkg != null &&
                    cur.poolSec > 0 &&
                    GateState.drainEnterElapsedMs == null
                ) {
                    GateState.drainEnterElapsedMs = elapsed
                    GateAlarms.schedulePoolExpiry(this@GateService, cur.poolSec * 1_000)
                }
            }
        }
    }

    private var lastStatusLine: String? = "Starting…"

    private fun refreshNotification() {
        val line =
            liveLine(
                gateActive(),
                GateState.poolState(),
                GateState.config,
                GateState.userWhitelist,
                statusForeground(),
            )
        lastStatusLine = line
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(gateActive()))
        manageFreeTicker(line)
    }

    @Volatile private var freeTicker: kotlinx.coroutines.Job? = null

    /** Gate active unless a pause deadline is in the future. */
    private fun gateActive(): Boolean = !GatePolicy.isPaused(GateState.pauseUntilMs, System.currentTimeMillis())

    /** Own window is noise: fall back to the last gated app for the status line. */
    private fun statusForeground(): String? =
        GateState.lastEventPkg.value?.takeUnless { it == packageName } ?: GateState.lastGatedApp.value

    private fun pauseMin(): Long =
        GatePolicy.pauseMinLeft(GatePolicy.pauseRemainingMs(GateState.pauseUntilMs, System.currentTimeMillis()))

    /** Status line with the live display pool (open segment elapsed). */
    private fun liveLine(
        active: Boolean,
        snap: com.abrai.zengate.policy.PoolState,
        cfg: com.abrai.zengate.policy.ZenConfig,
        list: Set<String>,
        fg: String?,
    ): String {
        val live =
            snap.copy(
                poolSec =
                    com.abrai.zengate.policy.PoolEngine.displayPoolSec(
                        snap.poolSec,
                        GateState.drainEnterElapsedMs,
                        android.os.SystemClock.elapsedRealtime(),
                    ),
            )
        return statusLine(active, live, cfg, list, fg, if (active) 0 else pauseMin())
    }

    /**
     * Bounded 1/s refresh while grace or a session is live on a lit screen.
     * Everything else reuses the event-driven collector.
     */
    private fun manageFreeTicker(line: String) {
        val power = getSystemService(PowerManager::class.java)
        // Prefixes alone go stale: TickGate also requires a lit interactive
        // screen, an active gate, and live session-or-pool behind the line.
        if (!com.abrai.zengate.policy.TickGate.shouldTick(
                line,
                power.isInteractive,
                gateActive(),
                PoolEngine.hasSession(GateState.poolState()),
                GateState.poolSec,
            )
        ) {
            freeTicker?.cancel()
            freeTicker = null
            return
        }
        if (freeTicker?.isActive == true) return
        freeTicker =
            scope.launch {
                while (isActive) {
                    kotlinx.coroutines.delay(1_000)
                    if (!power.isInteractive) break
                    val tick =
                        liveLine(
                            gateActive(),
                            GateState.poolState(),
                            GateState.config,
                            GateState.userWhitelist,
                            statusForeground(),
                        )
                    if (tick != lastStatusLine) {
                        lastStatusLine = tick
                        val manager = getSystemService(NotificationManager::class.java)
                        manager.notify(NOTIFICATION_ID, buildNotification(gateActive()))
                    }
                    if (!com.abrai.zengate.policy.TickGate.shouldTick(
                            tick,
                            power.isInteractive,
                            gateActive(),
                            PoolEngine.hasSession(GateState.poolState()),
                            GateState.poolSec,
                        )
                    ) {
                        break
                    }
                }
                freeTicker = null
            }
    }

    private fun statusLine(
        active: Boolean,
        snap: com.abrai.zengate.policy.PoolState,
        cfg: com.abrai.zengate.policy.ZenConfig,
        list: Set<String>,
        fg: String?,
        pauseMin: Long = 0,
    ): String {
        val remaining =
            com.abrai.zengate.policy.PoolEngine
                .sessionRemainingMs(snap, System.currentTimeMillis(), cfg)
        return when (
            val s =
                com.abrai.zengate.policy.GateStatus.describe(
                    active,
                    remaining,
                    snap.sessionExpiryWallMs,
                    snap.poolSec,
                    snap.usagesToday,
                    fg,
                    list,
                    BlockActivity.isShowing,
                    pauseMin,
                )
        ) {
            is com.abrai.zengate.policy.GateStatus.Status.Paused ->
                com.abrai.zengate.policy.NotificationText
                    .paused(s.minLeft)
            is com.abrai.zengate.policy.GateStatus.Status.Session ->
                com.abrai.zengate.policy.NotificationText.session(
                    (s.expiryWallMs - System.currentTimeMillis()).coerceAtLeast(0L),
                    endLabel(s.expiryWallMs),
                )
            is com.abrai.zengate.policy.GateStatus.Status.Whitelisted ->
                com.abrai.zengate.policy.NotificationText
                    .whitelisted(appLabel(s.pkg))
            is com.abrai.zengate.policy.GateStatus.Status.FreeTime ->
                com.abrai.zengate.policy.NotificationText
                    .free(s.poolSec)
            is com.abrai.zengate.policy.GateStatus.Status.Blocking ->
                com.abrai.zengate.policy.NotificationText
                    .blocking(s.usagesToday)
        }
    }

    private fun endLabel(wallMs: Long): String =
        try {
            java.time.LocalTime
                .ofInstant(
                    java.time.Instant.ofEpochMilli(wallMs),
                    java.time.ZoneId.systemDefault(),
                ).toString()
                .substring(0, 5)
        } catch (t: Throwable) {
            ""
        }

    private fun appLabel(pkg: String): String =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (t: Throwable) {
            pkg
        }

    private fun buildNotification(active: Boolean): Notification {
        val paused = !active
        val pauseMin = (GateState.config.pauseDurationSec / 60).coerceAtLeast(1)
        val toggle =
            PendingIntent.getService(
                this,
                0,
                Intent(this, GateService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL_ID)
            .setContentTitle(if (active) "Zen Gate" else "Zen Gate is paused")
            .setContentText(
                lastStatusLine
                    ?: statusLine(
                        active,
                        GateState.poolState(),
                        GateState.config,
                        GateState.userWhitelist,
                        statusForeground(),
                        if (active) 0 else pauseMin(),
                    ),
            ).setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    1,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).addAction(
                Notification.Action
                    .Builder(
                        null,
                        if (paused) "Re-enable now" else "Pause $pauseMin min",
                        toggle,
                    ).build(),
            ).build()
    }

    /**
     * Alarm-time launch path (single process): the manifest receiver persisted
     * first, so a store-direct read is fresh by construction (no ordering race).
     * The verdict runs against the live a11y root via the shared static.
     */
    private fun onDeadline(deadline: String) {
        scope.launch {
            try {
                val snap = store.snapshot.first().poolState()
                val cfg = store.config.first()
                val list = store.whitelist.first()
                // Store-direct (never the mirror): this path can run in a fresh
                // process whose mirror never loaded — a cold empty set would
                // misread declared overlays as gated launch targets (P0#1).
                val transparent = store.transparent.first()
                val wall = System.currentTimeMillis()
                val pauseUntil = store.pauseUntil.first()
                val enabled = !GatePolicy.isPaused(pauseUntil, wall)
                val power = getSystemService(PowerManager::class.java)
                val keys = getSystemService(KeyguardManager::class.java)
                val remaining = PoolEngine.sessionRemainingMs(snap, wall, cfg)
                val usageFg =
                    com.abrai.zengate.policy.UsageOracle
                        .queryForeground(
                            this@GateService,
                            com.abrai.zengate.policy.UsageOracle
                                .windowMs(snap.poolSec),
                        )
                when (
                    val v =
                        ZenGateService.decideDeadline(
                            enabled,
                            remaining,
                            packageName,
                            list,
                            transparent,
                            keys.isKeyguardLocked,
                            power.isInteractive,
                            usageFg,
                        )
                ) {
                    is com.abrai.zengate.policy.DeadlineVerdict.Outcome.Launch -> {
                        Log.d(TAG, "deadline launch pkg=${v.pkg} via $deadline")
                        startActivity(
                            Intent(this@GateService, BlockActivity::class.java)
                                .putExtra(BlockActivity.EXTRA_PACKAGE, v.pkg)
                                .putExtra(BlockActivity.EXTRA_WAIT_SEC, PoolEngine.penaltySec(snap, cfg).toInt())
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                ),
                        )
                    }
                    com.abrai.zengate.policy.DeadlineVerdict.Outcome.Skip ->
                        Log.d(TAG, "deadline skipped via $deadline")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "deadline handling failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "ZenGate"
        private const val CHANNEL_ID = "gate"
        private const val NOTIFICATION_ID = 1
        const val ACTION_PAUSE = "com.abrai.zengate.PAUSE"
        const val ACTION_RESUME = "com.abrai.zengate.RESUME"
        const val ACTION_DEADLINE = "com.abrai.zengate.DEADLINE"
        const val EXTRA_DEADLINE = "deadline"
    }
}
