package com.zaneschepke.wireguardautotunnel.core.orchestration

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.core.broadcast.NotificationActionReceiver
import com.zaneschepke.wireguardautotunnel.data.DataStoreManager
import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelActionSource
import com.zaneschepke.wireguardautotunnel.domain.events.TunnelActionEvent
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.notification.TunnelNotificationService
import com.zaneschepke.wireguardautotunnel.service.ServiceManager
import com.zaneschepke.wireguardautotunnel.service.autotunnel.AutoTunnelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Temporarily turns the VPN off. The tunnels that were running and auto-tunnel are restored when
 * the pause ends, or never if the pause is indefinite. The pause survives the process being killed
 * or the device rebooting.
 */
class PauseCoordinator(
    private val context: Context,
    private val dataStoreManager: DataStoreManager,
    private val tunnelCoordinator: TunnelCoordinator,
    private val autoTunnelCoordinator: AutoTunnelCoordinator,
    private val autoTunnelStateHolder: AutoTunnelStateHolder,
    private val serviceManager: ServiceManager,
    private val tunnelRepository: TunnelRepository,
    private val notificationService: TunnelNotificationService,
    private val scope: CoroutineScope,
) {

    /** [endsAtMs] is null for an indefinite pause. */
    data class PauseState(
        val endsAtMs: Long?,
        val tunnelIds: List<Int>,
        val autoTunnelWasRunning: Boolean,
    )

    private val mutex = Mutex()
    private val _state = MutableStateFlow<PauseState?>(null)
    val state: StateFlow<PauseState?> = _state.asStateFlow()

    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private var timerJob: Job? = null
    private var warnedFor = 0L

    fun bind(scope: CoroutineScope) {
        scope.launch { handleStartup() }
        scope.launch {
            tunnelCoordinator.actions.collect { event ->
                // starting a tunnel by hand ends the pause
                if (
                    event is TunnelActionEvent.Started &&
                        event.source == TunnelActionSource.USER &&
                        _state.value != null
                ) {
                    mutex.withLock { cancelLocked() }
                }
            }
        }
    }

    /**
     * Picks a persisted pause back up after the process restarted. Returns true if the VPN must not
     * be restored by the caller, either because it is still paused or because the pause just ended
     * and already restored it.
     */
    suspend fun handleStartup(): Boolean = mutex.withLock {
        val state = _state.value ?: load() ?: return@withLock false
        Timber.d("Pause found on startup: $state")
        _state.value = state
        if (state.endsAtMs != null && state.endsAtMs <= now()) {
            endLocked()
        } else {
            armLocked(state)
            notificationService.showPaused(state.endsAtMs)
        }
        true
    }

    /** Pauses for [durationMs], or indefinitely if null. */
    suspend fun pause(durationMs: Long?) = mutex.withLock {
        val current = _state.value
        val active = tunnelCoordinator.backendStatus.value.activeTunnels.keys.toList()
        val state =
            PauseState(
                endsAtMs = durationMs?.let { now() + it },
                tunnelIds = active.ifEmpty { current?.tunnelIds.orEmpty() },
                autoTunnelWasRunning =
                    current?.autoTunnelWasRunning ?: autoTunnelStateHolder.active.value,
            )
        save(state)
        _state.value = state
        // auto-tunnel would bring the VPN straight back up
        if (state.autoTunnelWasRunning) serviceManager.stopAutoTunnelService()
        tunnelCoordinator.stopActiveTunnels()
        notificationService.removePauseEnding()
        armLocked(state)
        notificationService.showPaused(state.endsAtMs)
    }

    fun launchPause(durationMs: Long?) {
        scope.launch { pause(durationMs) }
    }

    suspend fun extend(addMs: Long) = mutex.withLock {
        val state = _state.value ?: return@withLock
        val endsAtMs = state.endsAtMs ?: return@withLock
        update(state.copy(endsAtMs = maxOf(endsAtMs, now()) + addMs))
    }

    suspend fun makeIndefinite() = mutex.withLock {
        val state = _state.value ?: return@withLock
        update(state.copy(endsAtMs = null))
    }

    suspend fun resume() = mutex.withLock { endLocked() }

    suspend fun onWarnDue() = mutex.withLock {
        val endsAtMs = _state.value?.endsAtMs ?: return@withLock
        val remaining = endsAtMs - now()
        if (warnedFor == endsAtMs || remaining <= 0 || remaining > WARN_LEAD_MS + TOLERANCE_MS) {
            return@withLock
        }
        warnedFor = endsAtMs
        notificationService.showPauseEnding()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, R.string.pause_ending_toast, Toast.LENGTH_LONG).show()
        }
    }

    suspend fun onEndDue() = mutex.withLock {
        val endsAtMs = _state.value?.endsAtMs ?: return@withLock
        if (endsAtMs - now() <= TOLERANCE_MS) endLocked()
    }

    private suspend fun update(state: PauseState) {
        save(state)
        _state.value = state
        notificationService.removePauseEnding()
        armLocked(state)
        notificationService.showPaused(state.endsAtMs)
    }

    private suspend fun endLocked() {
        val state = _state.value ?: return
        Timber.d("Pause ended, restoring tunnels ${state.tunnelIds}")
        clearLocked()
        // single tunnel for now, so only one can come back
        val tunnel = state.tunnelIds.firstNotNullOfOrNull { tunnelRepository.getById(it) }
        tunnel?.let { tunnelCoordinator.startTunnel(it) }
        if (state.autoTunnelWasRunning) autoTunnelCoordinator.start()
    }

    // the user started a tunnel, so leave it running and just get auto-tunnel going again
    private suspend fun cancelLocked() {
        val state = _state.value ?: return
        clearLocked()
        if (state.autoTunnelWasRunning) autoTunnelCoordinator.start()
    }

    private suspend fun clearLocked() {
        _state.value = null
        timerJob?.cancel()
        cancelAlarm(ACTION_PAUSE_WARN)
        cancelAlarm(ACTION_PAUSE_END)
        notificationService.removePaused()
        notificationService.removePauseEnding()
        dataStoreManager.saveToDataStore(DataStoreManager.pauseActive, false)
    }

    private fun armLocked(state: PauseState) {
        timerJob?.cancel()
        cancelAlarm(ACTION_PAUSE_WARN)
        cancelAlarm(ACTION_PAUSE_END)
        val endsAtMs = state.endsAtMs ?: return
        val warnAtMs = endsAtMs - WARN_LEAD_MS
        val warn = warnAtMs > now() && warnedFor != endsAtMs
        if (warn) scheduleAlarm(ACTION_PAUSE_WARN, warnAtMs)
        scheduleAlarm(ACTION_PAUSE_END, endsAtMs)
        // alarms can be late, this keeps it on time while the process is alive
        timerJob = scope.launch {
            if (warn) {
                delay(warnAtMs - now())
                onWarnDue()
            }
            delay(endsAtMs - now())
            onEndDue()
        }
    }

    private fun scheduleAlarm(action: String, atMs: Long) {
        val intent = alarmIntent(action, PendingIntent.FLAG_UPDATE_CURRENT)
        val exact =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        try {
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, intent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, intent)
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Failed to schedule pause alarm")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, intent)
        }
    }

    private fun cancelAlarm(action: String) {
        alarmIntent(action, PendingIntent.FLAG_UPDATE_CURRENT).let(alarmManager::cancel)
    }

    private fun alarmIntent(action: String, flags: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, NotificationActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or flags,
        )

    private suspend fun save(state: PauseState) {
        with(dataStoreManager) {
            saveToDataStore(DataStoreManager.pauseEndsAtMs, state.endsAtMs ?: INDEFINITE)
            saveToDataStore(DataStoreManager.pauseTunnelIds, state.tunnelIds.joinToString(","))
            saveToDataStore(
                DataStoreManager.pauseAutoTunnelWasRunning,
                state.autoTunnelWasRunning,
            )
            saveToDataStore(DataStoreManager.pauseActive, true)
        }
    }

    private suspend fun load(): PauseState? {
        if (dataStoreManager.getFromStore(DataStoreManager.pauseActive) != true) return null
        val endsAtMs = dataStoreManager.getFromStore(DataStoreManager.pauseEndsAtMs)
        return PauseState(
            endsAtMs = endsAtMs?.takeIf { it != INDEFINITE },
            tunnelIds =
                dataStoreManager
                    .getFromStore(DataStoreManager.pauseTunnelIds)
                    .orEmpty()
                    .split(',')
                    .mapNotNull { it.toIntOrNull() },
            autoTunnelWasRunning =
                dataStoreManager.getFromStore(DataStoreManager.pauseAutoTunnelWasRunning) ?: false,
        )
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        const val ACTION_PAUSE_WARN = "com.zaneschepke.wireguardautotunnel.PAUSE_WARN"
        const val ACTION_PAUSE_END = "com.zaneschepke.wireguardautotunnel.PAUSE_END"
        const val MINUTE_MS = 60_000L
        private const val WARN_LEAD_MS = MINUTE_MS
        private const val TOLERANCE_MS = 1_000L
        private const val INDEFINITE = -1L
    }
}
