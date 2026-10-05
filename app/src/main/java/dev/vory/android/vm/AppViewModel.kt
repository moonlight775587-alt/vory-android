package dev.vory.android.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.vory.android.data.AppRepository
import dev.vory.android.data.BotMood
import dev.vory.android.data.BotProfile
import dev.vory.android.data.Gateway
import dev.vory.android.data.HermesSocketClient
import dev.vory.android.data.PendingCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * App-scoped state: gateways, profiles, connection, pending cards, restart banner.
 * Backed by [AppRepository]; screens collect these flows.
 */
class AppViewModel(val repo: AppRepository) : ViewModel() {

    val gateways: StateFlow<List<Gateway>> = repo.store.gatewaysFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeGateway: StateFlow<Gateway?> = repo.activeGateway
    val profiles: StateFlow<List<BotProfile>> = repo.profiles
    val activeProfile: StateFlow<String> = repo.activeProfile
    val connState: StateFlow<HermesSocketClient.ConnState> = repo.connState
    val needsRestart: StateFlow<Boolean> = repo.needsRestart
    val pendingCards: StateFlow<List<PendingCard>> = repo.pendingCards
    val botMoods: StateFlow<Map<String, BotMood>> = repo.botMoods

    /** Sessions with a waiting card — drives the "Needs you" badge. */
    val needsYouSessions: StateFlow<Set<String>> = repo.pendingCards
        .combine(repo.activeProfile) { cards, _ -> cards.mapNotNull { it.sessionId.ifEmpty { null } }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val botColors: StateFlow<Map<String, Int>> = repo.store.botColorsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun selectGateway(gw: Gateway) = repo.selectGateway(gw)

    fun deleteGateway(gw: Gateway) = viewModelScope.launch {
        val wasActive = repo.activeGateway.value?.id == gw.id
        repo.store.deleteGateway(gw.id)
        if (wasActive) {
            val rest = repo.store.gateways().firstOrNull()
            if (rest != null) repo.selectGateway(rest) else repo.disconnectSocket()
        }
    }
    fun reconnect() = repo.connectSocket()
    fun setActiveProfile(name: String) = viewModelScope.launch { repo.setActiveProfile(name) }

    fun answerCard(card: PendingCard, payload: JSONObject) =
        viewModelScope.launch { repo.answerCard(card, payload) }

    fun dismissRestartBanner() = viewModelScope.launch {
        // Re-probe; clears if the gateway has been updated since.
        repo.probeRestartRequired()
    }

    fun updateHermes(onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = repo.updateHermes() != null
        onDone(ok)
    }

    fun restartGateway(onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = repo.restartGateway() != null
        onDone(ok)
    }

    fun setBotColor(profile: String, argb: Int) = viewModelScope.launch {
        repo.store.setBotColor(profile, argb)
        repo.loadProfiles()
    }

    class Factory(private val repo: AppRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(repo) as T
    }
}
