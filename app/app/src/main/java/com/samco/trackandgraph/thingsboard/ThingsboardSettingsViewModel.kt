/*
 *  This file is part of Track & Graph
 *
 *  Track & Graph is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Track & Graph is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with Track & Graph.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.samco.trackandgraph.thingsboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.samco.trackandgraph.data.di.IODispatcher
import com.samco.trackandgraph.helpers.PrefHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.threeten.bp.OffsetDateTime
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

interface ThingsboardSettingsViewModel {
    val baseUrl: StateFlow<String>
    val apiToken: StateFlow<String>
    val autoSyncEnabled: StateFlow<Boolean>
    val autoSyncInterval: StateFlow<Duration>
    val errorEvent: Flow<String>

    fun setBaseUrl(url: String)
    fun setApiToken(token: String)
    fun setAutoSyncEnabled(enabled: Boolean)
    fun setAutoSyncInterval(duration: Duration)
    suspend fun saveConnectorConfig()
    suspend fun saveAutoSyncConfig()
}

@HiltViewModel
class ThingsboardSettingsViewModelImpl @Inject constructor(
    private val prefHelper: PrefHelper,
    private val thingsboardSyncInteractor: ThingsboardSyncInteractor,
    @IODispatcher private val io: CoroutineDispatcher,
) : ViewModel(), ThingsboardSettingsViewModel {

    private val _baseUrl = MutableStateFlow("")
    override val baseUrl: StateFlow<String> = _baseUrl

    private val _apiToken = MutableStateFlow("")
    override val apiToken: StateFlow<String> = _apiToken

    private val _autoSyncEnabled = MutableStateFlow(false)
    override val autoSyncEnabled: StateFlow<Boolean> = _autoSyncEnabled

    private val _autoSyncInterval = MutableStateFlow(15.minutes)
    override val autoSyncInterval: StateFlow<Duration> = _autoSyncInterval

    private val _errorEvent = Channel<String>()
    override val errorEvent: Flow<String> = _errorEvent.receiveAsFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch(io) {
            try {
                val url = prefHelper.getThingsboardBaseUrl() ?: ""
                val token = prefHelper.getThingsboardGlobalApiToken() ?: ""
                withContext(viewModelScope.coroutineContext) {
                    _baseUrl.value = url
                    _apiToken.value = token
                }

                val config = prefHelper.getThingsboardAutoSyncConfig()
                withContext(viewModelScope.coroutineContext) {
                    _autoSyncEnabled.value = config != null
                    _autoSyncInterval.value = config?.let {
                        when (it.units) {
                            PrefHelper.BackupConfigUnit.MINUTES -> it.interval.minutes
                            PrefHelper.BackupConfigUnit.HOURS -> (it.interval.toLong() * 60).minutes
                            PrefHelper.BackupConfigUnit.DAYS -> (it.interval.toLong() * 24 * 60).minutes
                            PrefHelper.BackupConfigUnit.WEEKS -> (it.interval.toLong() * 7 * 24 * 60).minutes
                        }
                    } ?: 15.minutes
                }
            } catch (e: Exception) {
                _errorEvent.trySend("Failed to load settings: ${e.message}")
            }
        }
    }

    override fun setBaseUrl(url: String) {
        _baseUrl.value = url
    }

    override fun setApiToken(token: String) {
        _apiToken.value = token
    }

    override fun setAutoSyncEnabled(enabled: Boolean) {
        _autoSyncEnabled.value = enabled
    }

    override fun setAutoSyncInterval(duration: Duration) {
        _autoSyncInterval.value = duration
    }

    override suspend fun saveConnectorConfig() {
        withContext(io) {
            try {
                prefHelper.setThingsboardBaseUrl(_baseUrl.value)
                prefHelper.setThingsboardGlobalApiToken(_apiToken.value)
            } catch (e: Exception) {
                _errorEvent.trySend("Failed to save connector config: ${e.message}")
            }
        }
    }

    override suspend fun saveAutoSyncConfig() {
        withContext(io) {
            try {
                val enabled = _autoSyncEnabled.value
                val interval = _autoSyncInterval.value

                if (enabled) {
                    val config = ThingsboardAutoSyncConfig(
                        firstDate = OffsetDateTime.now(),
                        interval = interval.inWholeMinutes.toInt(),
                        units = PrefHelper.BackupConfigUnit.MINUTES
                    )
                    thingsboardSyncInteractor.scheduleAutoSync(config)
                } else {
                    thingsboardSyncInteractor.disableAutoSync()
                }
            } catch (e: Exception) {
                _errorEvent.trySend("Failed to save auto-sync config: ${e.message}")
            }
        }
    }
}
