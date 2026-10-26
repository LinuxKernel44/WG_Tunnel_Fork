package com.zaneschepke.wireguardautotunnel.core.tunnel

import com.zaneschepke.wireguardautotunnel.core.orchestration.PauseCoordinator
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelActionSource
import com.zaneschepke.wireguardautotunnel.domain.events.TunnelActionEvent
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.notification.TunnelNotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Shows a persistent notification while the VPN is off after the user turned it off, so it can be
 * turned back on without opening the app.
 */
class VpnOffNotifier(
    private val tunnelCoordinator: TunnelCoordinator,
    private val tunnelRepository: TunnelRepository,
    private val notificationService: TunnelNotificationService,
    private val pauseCoordinator: PauseCoordinator,
) {

    // tunnels the user stopped since a tunnel was last started
    private val userStopped = MutableStateFlow<List<Int>>(emptyList())

    @OptIn(FlowPreview::class)
    fun bind(scope: CoroutineScope) {
        scope.launch {
            tunnelCoordinator.actions.collect { event ->
                when (event) {
                    is TunnelActionEvent.Started -> userStopped.value = emptyList()
                    is TunnelActionEvent.Stopped ->
                        if (event.source == TunnelActionSource.USER) {
                            userStopped.update { (it + event.tunnelId).distinct() }
                        }
                }
            }
        }
        scope.launch {
            combine(
                    userStopped,
                    tunnelCoordinator.backendStatus.map { it.activeTunnels.isEmpty() },
                    pauseCoordinator.state.map { it != null },
                ) { stopped, idle, paused ->
                    stopped.isNotEmpty() && idle && !paused
                }
                .distinctUntilChanged()
                // a restart stops then starts the tunnel, don't flash the notification
                .debounce(DEBOUNCE_MS)
                .collect { show ->
                    if (show) {
                        notificationService.showVpnOff(resolveTarget()?.name)
                    } else {
                        notificationService.removeVpnOff()
                    }
                }
        }
    }

    suspend fun reactivate() {
        resolveTarget()?.let { tunnelCoordinator.startTunnel(it) }
    }

    // the default tunnel, otherwise the tunnel that was turned off
    private suspend fun resolveTarget(): TunnelConfig? =
        tunnelRepository.getDefaultTunnel()
            ?: userStopped.value.firstNotNullOfOrNull { tunnelRepository.getById(it) }

    companion object {
        private const val DEBOUNCE_MS = 750L
    }
}
