package dev.vory.android.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.vory.android.data.AppRepository
import dev.vory.android.data.BotProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class BotsViewModel(private val repo: AppRepository) : ViewModel() {

    private val _selected = MutableStateFlow<BotProfile?>(null)
    val selected: StateFlow<BotProfile?> = _selected.asStateFlow()

    private val _soul = MutableStateFlow("")
    val soul: StateFlow<String> = _soul.asStateFlow()

    private val _soulLoading = MutableStateFlow(false)
    val soulLoading: StateFlow<Boolean> = _soulLoading.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _groupRooms = MutableStateFlow<List<String>>(emptyList())
    val groupRooms: StateFlow<List<String>> = _groupRooms.asStateFlow()

    init { loadGroupRooms() }

    fun select(bot: BotProfile) {
        _selected.value = bot
        loadSoul(bot.name)
    }

    fun clearSelection() { _selected.value = null }
    fun clearNotice() { _notice.value = null }

    private fun loadSoul(name: String) {
        viewModelScope.launch {
            _soulLoading.value = true
            try {
                _soul.value = repo.rest?.getText("/api/profiles/$name/soul", profile = name).orEmpty()
            } catch (e: Exception) {
                _notice.value = e.message
            } finally {
                _soulLoading.value = false
            }
        }
    }

    fun saveSoul(text: String) {
        val name = _selected.value?.name ?: return
        viewModelScope.launch {
            try {
                repo.rest?.put("/api/profiles/$name/soul", profile = name, body = JSONObject().put("soul", text))
                _notice.value = "SOUL.md saved"
            } catch (e: Exception) {
                _notice.value = e.message
            }
        }
    }

    fun saveDescription(text: String) {
        val name = _selected.value?.name ?: return
        viewModelScope.launch {
            try {
                repo.rest?.put("/api/profiles/$name/description", profile = name, body = JSONObject().put("description", text))
                _notice.value = "Description saved"
                repo.loadProfiles()
            } catch (e: Exception) {
                _notice.value = e.message
            }
        }
    }

    fun saveDefaultModel(model: String) {
        val name = _selected.value?.name ?: return
        viewModelScope.launch {
            try {
                repo.rest?.put("/api/profiles/$name/model", profile = name, body = JSONObject().put("model", model))
                _notice.value = "Default model saved"
                repo.loadProfiles()
            } catch (e: Exception) {
                _notice.value = e.message
            }
        }
    }

    fun createBot(name: String) {
        viewModelScope.launch {
            try {
                repo.rest?.post("/api/profiles", profile = "", body = JSONObject().put("name", name))
                repo.loadProfiles()
            } catch (e: Exception) {
                _notice.value = e.message
            }
        }
    }

    fun deleteBot(name: String) {
        viewModelScope.launch {
            try {
                repo.rest?.delete("/api/profiles/$name", profile = "")
                repo.loadProfiles()
                if (_selected.value?.name == name) _selected.value = null
            } catch (e: Exception) {
                _notice.value = e.message
            }
        }
    }

    private fun loadGroupRooms() {
        viewModelScope.launch {
            _groupRooms.value = try {
                val res = repo.rpc("groups.list")
                val arr = res.optJSONArray("groups") ?: res.optJSONArray("items")
                if (arr != null) List(arr.length()) { arr.optJSONObject(it)?.optString("name", "") ?: arr.optString(it) }
                    .filter { it.isNotEmpty() } else emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun createGroupRoom(name: String) {
        viewModelScope.launch {
            try {
                repo.rpc("groups.create", JSONObject().put("name", name))
                loadGroupRooms()
            } catch (e: Exception) {
                _notice.value = e.message
            }
        }
    }

    class Factory(private val repo: AppRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BotsViewModel(repo) as T
    }
}
