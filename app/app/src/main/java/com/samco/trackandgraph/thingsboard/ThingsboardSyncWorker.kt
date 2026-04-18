package com.samco.trackandgraph.thingsboard

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ThingsboardSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val syncInteractor: ThingsboardSyncInteractor,
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result {
        return when (syncInteractor.syncAllTrackers()) {
            ThingsboardSyncResult.Success,
            ThingsboardSyncResult.NoConnectorConfigured,
            ThingsboardSyncResult.NoDeviceApiKey,
            ThingsboardSyncResult.NoDataToSync -> Result.success()
            is ThingsboardSyncResult.Failed -> Result.failure()
        }
    }
}
