package com.samco.trackandgraph.thingsboard

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.samco.trackandgraph.data.database.dto.DataPoint
import com.samco.trackandgraph.data.database.dto.Tracker
import com.samco.trackandgraph.data.interactor.TrackerHelper
import com.samco.trackandgraph.data.di.IODispatcher
import com.samco.trackandgraph.helpers.PrefHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.threeten.bp.Duration
import org.threeten.bp.Instant
import org.threeten.bp.OffsetDateTime
import org.threeten.bp.ZoneId
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ThingsboardSyncResult {
    object Success : ThingsboardSyncResult
    object NoConnectorConfigured : ThingsboardSyncResult
    object NoDeviceApiKey : ThingsboardSyncResult
    object NoDataToSync : ThingsboardSyncResult
    data class Failed(val message: String) : ThingsboardSyncResult
}

data class ThingsboardAutoSyncConfig(
    val firstDate: OffsetDateTime,
    val interval: Int,
    val units: PrefHelper.BackupConfigUnit,
)

data class ThingsboardAutoSyncInfo(
    val nextScheduled: OffsetDateTime,
    val interval: Int,
    val units: PrefHelper.BackupConfigUnit,
    val lastSuccessful: OffsetDateTime?
)

interface ThingsboardSyncInteractor {
    suspend fun syncTracker(trackerId: Long): ThingsboardSyncResult
    suspend fun syncAllTrackers(): ThingsboardSyncResult
    suspend fun getAutoSyncInfo(): ThingsboardAutoSyncInfo?
    fun scheduleAutoSync(config: ThingsboardAutoSyncConfig)
    fun disableAutoSync()
}

@Singleton
class ThingsboardSyncInteractorImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackerHelper: TrackerHelper,
    private val prefHelper: PrefHelper,
    @IODispatcher private val io: CoroutineDispatcher,
) : ThingsboardSyncInteractor {

    companion object {
        private const val AUTO_SYNC_WORK_NAME = "thingsboard_auto_sync"
        private const val DEFAULT_TIMEOUT_MS = 30_000
    }

    override suspend fun syncTracker(trackerId: Long): ThingsboardSyncResult = withContext(io) {
        val baseUrl = prefHelper.getThingsboardBaseUrl()?.trim()?.takeIf { it.isNotBlank() }
            ?: return@withContext ThingsboardSyncResult.NoConnectorConfigured

        val tracker = trackerHelper.getTrackerById(trackerId)
            ?: return@withContext ThingsboardSyncResult.Failed("Tracker not found")

        val deviceKey = tracker.thingsboardDeviceApiKey?.trim()?.takeIf { it.isNotBlank() }
            ?: return@withContext ThingsboardSyncResult.NoDeviceApiKey

        val dataPoint = trackerHelper.getLatestDataPointForTrackerSync(trackerId)
            ?: return@withContext ThingsboardSyncResult.NoDataToSync

        return@withContext sendTelemetry(baseUrl, deviceKey, buildPayload(tracker, dataPoint))
    }

    override suspend fun syncAllTrackers(): ThingsboardSyncResult = withContext(io) {
        val baseUrl = prefHelper.getThingsboardBaseUrl()?.trim()?.takeIf { it.isNotBlank() }
            ?: return@withContext ThingsboardSyncResult.NoConnectorConfigured

        val trackers = trackerHelper.getAllTrackersSync()
        val eligible = trackers.filter { it.thingsboardDeviceApiKey?.isNotBlank() == true }

        if (eligible.isEmpty()) {
            return@withContext ThingsboardSyncResult.NoDeviceApiKey
        }

        var anySuccess = false
        val failures = mutableListOf<String>()

        eligible.forEach { tracker ->
            val dataPoint = trackerHelper.getLatestDataPointForTrackerSync(tracker.id)
            if (dataPoint == null) {
                failures.add("${tracker.name}: no data")
                return@forEach
            }

            when (val result = sendTelemetry(baseUrl, tracker.thingsboardDeviceApiKey!!.trim(), buildPayload(tracker, dataPoint))) {
                ThingsboardSyncResult.Success -> anySuccess = true
                ThingsboardSyncResult.NoConnectorConfigured -> failures.add("${tracker.name}: missing connector")
                ThingsboardSyncResult.NoDeviceApiKey -> failures.add("${tracker.name}: no device key")
                ThingsboardSyncResult.NoDataToSync -> failures.add("${tracker.name}: no data")
                is ThingsboardSyncResult.Failed -> failures.add("${tracker.name}: ${result.message}")
            }
        }

        return@withContext if (anySuccess) {
            prefHelper.setLastThingsboardSyncTime(OffsetDateTime.now())
            ThingsboardSyncResult.Success
        } else {
            ThingsboardSyncResult.Failed(failures.joinToString(separator = "; "))
        }
    }

    override suspend fun getAutoSyncInfo(): ThingsboardAutoSyncInfo? {
        val config = prefHelper.getThingsboardAutoSyncConfig() ?: return null
        val nextScheduledMillis = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(AUTO_SYNC_WORK_NAME)
            .get()
            .firstOrNull()
            ?.takeIf { !it.state.isFinished }
            ?.nextScheduleTimeMillis
            ?: return null

        val nextScheduled = OffsetDateTime.ofInstant(Instant.ofEpochMilli(nextScheduledMillis), ZoneId.systemDefault())
        val lastSuccessful = prefHelper.getLastThingsboardSyncTime()

        return ThingsboardAutoSyncInfo(
            nextScheduled = nextScheduled,
            interval = config.interval,
            units = config.units,
            lastSuccessful = lastSuccessful
        )
    }

    override fun scheduleAutoSync(config: ThingsboardAutoSyncConfig) {
        prefHelper.setThingsboardAutoSyncConfig(
            PrefHelper.ThingsboardAutoSyncConfigData(
                firstDate = config.firstDate,
                interval = config.interval,
                units = config.units
            )
        )

        val unit = when (config.units) {
            PrefHelper.BackupConfigUnit.MINUTES -> TimeUnit.MINUTES
            PrefHelper.BackupConfigUnit.HOURS -> TimeUnit.HOURS
            PrefHelper.BackupConfigUnit.DAYS -> TimeUnit.DAYS
            PrefHelper.BackupConfigUnit.WEEKS -> TimeUnit.DAYS
        }

        val intervalValue = when (config.units) {
            PrefHelper.BackupConfigUnit.WEEKS -> config.interval.toLong() * 7
            else -> config.interval.toLong()
        }

        val secondsDelay = maxOf(
            10L,
            config.firstDate.toEpochSecond() - OffsetDateTime.now().toEpochSecond()
        )

        val workRequest = PeriodicWorkRequestBuilder<ThingsboardSyncWorker>(intervalValue, unit)
            .setInitialDelay(secondsDelay, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            AUTO_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            workRequest
        )
    }

    override fun disableAutoSync() {
        prefHelper.setThingsboardAutoSyncConfig(null)
        WorkManager.getInstance(context).cancelUniqueWork(AUTO_SYNC_WORK_NAME)
    }

    private suspend fun sendTelemetry(baseUrl: String, deviceKey: String, payload: String): ThingsboardSyncResult {
        return withContext(io) {
            val normalizedBaseUrl = normalizeUrl(baseUrl)
            val url = URL("$normalizedBaseUrl/api/v1/$deviceKey/telemetry")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = DEFAULT_TIMEOUT_MS
                readTimeout = DEFAULT_TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }

            try {
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    ThingsboardSyncResult.Success
                } else {
                    val responseMessage = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                    ThingsboardSyncResult.Failed("HTTP $responseCode: ${responseMessage.ifBlank { connection.responseMessage }}")
                }
            } catch (t: Throwable) {
                Timber.e(t, "Thingsboard sync failed")
                ThingsboardSyncResult.Failed(t.message ?: "Unknown network error")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun buildPayload(tracker: Tracker, dataPoint: DataPoint): String {
        val timestampMillis = dataPoint.timestamp.toInstant().toEpochMilli()
        val metadata = mutableMapOf<String, Any>(
            "timestamp" to timestampMillis,
            "name" to tracker.name,
        )
        if (dataPoint.label.isNotBlank()) metadata["label"] = dataPoint.label
        if (dataPoint.note.isNotBlank()) metadata["note"] = dataPoint.note

        val value = dataPoint.value
        val values = mapOf(
            "value" to value,
            "timestamp" to timestampMillis,
            "name" to tracker.name
        )
        return JsonEncoder.encode(values)
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        val prefixed = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        return prefixed.trimEnd('/')
    }
}

private object JsonEncoder {
    fun encode(map: Map<String, Any>): String {
        return buildString {
            append('{')
            map.entries.forEachIndexed { index, entry ->
                append('"')
                append(entry.key)
                append('"')
                append(':')
                val value = entry.value
                when (value) {
                    is Number, is Boolean -> append(value.toString())
                    else -> append('"').append(value.toString().replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
                }
                if (index < map.size - 1) append(',')
            }
            append('}')
        }
    }
}
