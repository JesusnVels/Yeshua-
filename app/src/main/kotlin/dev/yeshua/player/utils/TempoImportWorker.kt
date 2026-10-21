/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.datastore.preferences.core.edit
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dev.yeshua.player.MainActivity
import dev.yeshua.player.R
import dev.yeshua.player.db.MusicDatabase
import dev.yeshua.player.utils.drive.TempoDriveBackup
import dev.yeshua.player.utils.drive.TempoDriveErrorKind
import dev.yeshua.player.utils.drive.TempoDriveException
import dev.yeshua.player.utils.drive.TempoDriveGatewayLocator
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a Tempo backup import in the background so it survives leaving the Stats
 * screen (or the app). Two sources feed the same [TempoHistoryImporter]:
 *
 * - a picked backup file (persistable URI permission taken when the user picks
 *   it with [androidx.activity.result.contract.ActivityResultContracts.OpenDocument]);
 * - a Tempo backup downloaded from the user's Google Drive ("drive" source).
 *
 * The worker runs as a foreground service with a live progress notification
 * (indeterminate while downloading/parsing, then a determinate bar driven by
 * the importer's per-chunk progress callback), so the import keeps running
 * when the app is closed. Progress is also published with [setProgress]
 * ([PROGRESS_DONE]/[PROGRESS_TOTAL]) for the Stats screen. On completion or
 * failure a notification is posted.
 */
class TempoImportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        coroutineScope {
            val source = inputData.getString(KEY_SOURCE) ?: SOURCE_FILE
            val searchMissing = inputData.getBoolean(KEY_SEARCH_MISSING, true)

            val database =
                EntryPointAccessors
                    .fromApplication(applicationContext, TempoImportWorkerEntryPoint::class.java)
                    .database()

            // Foreground from the start: the import must keep running when the
            // app is closed. If foreground promotion is refused (background
            // start restrictions), continue without it rather than failing.
            startForegroundNotification(
                if (source == SOURCE_DRIVE) {
                    applicationContext.getString(R.string.tempo_import_downloading)
                } else {
                    applicationContext.getString(R.string.tempo_import_working)
                },
            )

            val progressDone = AtomicInteger(0)
            val progressTotal = AtomicInteger(0)
            val progressPublisher =
                launch {
                    while (isActive) {
                        runCatching {
                            setProgress(
                                workDataOf(
                                    PROGRESS_DONE to progressDone.get(),
                                    PROGRESS_TOTAL to progressTotal.get(),
                                ),
                            )
                        }
                        delay(PROGRESS_PUBLISH_INTERVAL_MS)
                    }
                }

            var downloadedFile: File? = null
            try {
                if (source == SOURCE_DRIVE) {
                    // Short-circuit: the exact same Drive backup (same file id
                    // AND same modifiedTime) already ran to completion once —
                    // re-downloading and re-parsing it could only reproduce
                    // the same result. Any difference falls through to the
                    // full path, where the importer's content-hash checkpoint
                    // remains the authority.
                    val fileId = inputData.getString(KEY_DRIVE_FILE_ID)
                    val modified = inputData.getLong(KEY_DRIVE_MODIFIED_TIME, 0L)
                    if (fileId != null && modified != 0L && isDriveBackupAlreadyImported(fileId, modified)) {
                        postAlreadyImportedNotification()
                        return@coroutineScope Result.success(
                            workDataOf(
                                OUTPUT_IMPORTED to 0,
                                OUTPUT_MATCHED_LOCAL to 0,
                                OUTPUT_MATCHED_SEARCH to 0,
                                OUTPUT_SKIPPED to 0,
                                OUTPUT_FAILED to 0,
                                OUTPUT_ALREADY_IMPORTED to true,
                            ),
                        )
                    }
                }
                val zipUri =
                    if (source == SOURCE_DRIVE) {
                        val fileId =
                            inputData.getString(KEY_DRIVE_FILE_ID)
                                ?: return@coroutineScope Result.failure(
                                    workDataOf(OUTPUT_ERROR to "Missing Drive file id"),
                                )
                        val fileName = inputData.getString(KEY_DRIVE_FILE_NAME) ?: fileId
                        downloadedFile = downloadDriveBackup(fileId, fileName)
                        Uri.fromFile(downloadedFile)
                    } else {
                        val uriString =
                            inputData.getString(KEY_ZIP_URI)
                                ?: return@coroutineScope Result.failure()
                        Uri.parse(uriString)
                    }

                val result =
                    TempoHistoryImporter.importTempoBackup(
                        zipUri = zipUri,
                        context = applicationContext,
                        database = database,
                        searchMissing = searchMissing,
                        onProgress = { done, total ->
                            progressDone.set(done)
                            progressTotal.set(total)
                            updateForegroundNotification(done, total)
                        },
                    )
                if (source == SOURCE_DRIVE) {
                    // Remember this backup as fully imported for the
                    // short-circuit above. Only written after the import ran
                    // to completion.
                    val fileId = inputData.getString(KEY_DRIVE_FILE_ID)
                    val modified = inputData.getLong(KEY_DRIVE_MODIFIED_TIME, 0L)
                    if (fileId != null && modified != 0L) {
                        runCatching { markDriveBackupImported(fileId, modified) }
                    }
                }
                postCompletionNotification(result)
                Result.success(
                    workDataOf(
                        OUTPUT_IMPORTED to result.importedCount,
                        OUTPUT_MATCHED_LOCAL to result.matchedLocalCount,
                        OUTPUT_MATCHED_SEARCH to result.matchedSearchCount,
                        OUTPUT_SKIPPED to result.skippedCount,
                        OUTPUT_FAILED to result.failedCount,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (denied: TempoDriveException) {
                // Drive consent declined / token expired / 403: a clear denial,
                // never a fake zero-play success.
                reportException(denied)
                val message =
                    when (denied.kind) {
                        TempoDriveErrorKind.ACCESS_DENIED,
                        TempoDriveErrorKind.NOT_AUTHORIZED,
                        ->
                            applicationContext.getString(R.string.tempo_import_drive_denied)

                        TempoDriveErrorKind.NOT_CONFIGURED ->
                            applicationContext.getString(R.string.tempo_import_drive_not_configured)

                        else -> denied.message ?: denied.kind.name
                    }
                postFailureNotification(message)
                Result.failure(workDataOf(OUTPUT_ERROR to message))
            } catch (security: SecurityException) {
                // Persisted URI permission was revoked mid-import.
                reportException(security)
                postFailureNotification(security.message)
                Result.failure(workDataOf(OUTPUT_ERROR to security.message))
            } catch (e: Exception) {
                reportException(e)
                postFailureNotification(e.message)
                Result.failure(workDataOf(OUTPUT_ERROR to e.message))
            } finally {
                progressPublisher.cancel()
                downloadedFile?.delete()
            }
        }

    /** Downloads the chosen Drive backup to the cache dir, after a silent authorization check. */
    private suspend fun downloadDriveBackup(
        fileId: String,
        fileName: String,
    ): File {
        val gateway = TempoDriveGatewayLocator.get(applicationContext)
        if (!gateway.ensureAuthorized(applicationContext)) {
            throw TempoDriveException(TempoDriveErrorKind.NOT_AUTHORIZED)
        }
        return gateway.downloadBackup(
            applicationContext,
            TempoDriveBackup(id = fileId, name = fileName, modifiedTimeMillis = 0L, sizeBytes = 0L),
        ) { fraction ->
            updateDownloadNotification(fraction)
        }
    }

    private var lastDownloadPercent = -1

    /** Download progress in the same foreground notification (bar stays indeterminate). */
    private fun updateDownloadNotification(fraction: Double) {
        val percent = (fraction * 100).toInt().coerceIn(0, 100)
        if (percent == lastDownloadPercent || percent == 0) return
        lastDownloadPercent = percent
        val base = applicationContext.getString(R.string.tempo_import_downloading)
        val notification = buildProgressNotification("$base $percent%", done = 0, total = 0)
        runCatching {
            applicationContext
                .getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun isDriveBackupAlreadyImported(
        fileId: String,
        modifiedTimeMillis: Long,
    ): Boolean {
        val prefs = applicationContext.dataStore.data.first()
        return prefs[dev.yeshua.player.constants.TempoDriveLastImportedIdKey] == fileId &&
            prefs[dev.yeshua.player.constants.TempoDriveLastImportedModifiedKey] == modifiedTimeMillis
    }

    private suspend fun markDriveBackupImported(
        fileId: String,
        modifiedTimeMillis: Long,
    ) {
        applicationContext.dataStore.edit { prefs ->
            prefs[dev.yeshua.player.constants.TempoDriveLastImportedIdKey] = fileId
            prefs[dev.yeshua.player.constants.TempoDriveLastImportedModifiedKey] = modifiedTimeMillis
        }
    }

    private fun postAlreadyImportedNotification() {
        val context = applicationContext
        try {
            createNotificationChannel(context)
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.music_note)
                    .setContentTitle(context.getString(R.string.tempo_import_notification_title))
                    .setContentText(context.getString(R.string.tempo_import_drive_already_imported))
                    .setContentIntent(contentPendingIntent())
                    .setAutoCancel(true)
                    .build()
            context
                .getSystemService(NotificationManager::class.java)
                ?.notify(COMPLETION_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; the Stats screen still shows the message.
        } catch (e: Exception) {
            reportException(e)
        }
    }

    // ----------------------------------------------------------------------
    // Notifications
    // ----------------------------------------------------------------------

    private suspend fun startForegroundNotification(text: String) {
        createNotificationChannel(applicationContext)
        val notification = buildProgressNotification(text, done = 0, total = 0)
        // Post the running notification directly as well as promoting to
        // foreground: promotion can be refused (background-start limits) and
        // that refusal must not make the import invisible. The per-chunk
        // updates reuse this same notification id either way.
        runCatching {
            applicationContext
                .getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        }
        runCatching {
            setForeground(createForegroundInfo(notification))
        }
    }

    private var lastNotifiedPercent = -1

    /**
     * Updates the foreground notification from the importer's progress callback
     * (which fires per processed chunk). Updates are throttled to whole-percent
     * steps so the notification doesn't churn; [total] == 0 keeps it
     * indeterminate.
     */
    private fun updateForegroundNotification(
        done: Int,
        total: Int,
    ) {
        val percent =
            if (total > 0) {
                (done * 100) / total
            } else {
                -1
            }
        if (percent == lastNotifiedPercent && done != total) return
        lastNotifiedPercent = percent
        val text =
            if (total > 0) {
                applicationContext.getString(R.string.tempo_import_matching, done, total)
            } else {
                applicationContext.getString(R.string.tempo_import_working)
            }
        val notification = buildProgressNotification(text, done, total)
        runCatching {
            applicationContext
                .getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildProgressNotification(
        text: String,
        done: Int,
        total: Int,
    ): android.app.Notification {
        val builder =
            NotificationCompat
                .Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.music_note)
                .setContentTitle(
                    applicationContext.getString(R.string.tempo_import_notification_running_title),
                ).setContentText(text)
                .setContentIntent(contentPendingIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
        if (total > 0) {
            builder.setProgress(100, (done * 100) / total.coerceAtLeast(1), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun createForegroundInfo(notification: android.app.Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }

    private fun postCompletionNotification(result: TempoImportResult) {
        val context = applicationContext
        try {
            createNotificationChannel(context)
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.music_note)
                    .setContentTitle(context.getString(R.string.tempo_import_notification_title))
                    .setContentText(
                        context.getString(
                            R.string.tempo_import_notification_text,
                            result.importedCount,
                        ),
                    ).setContentIntent(contentPendingIntent())
                    .setAutoCancel(true)
                    .build()
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager?.notify(COMPLETION_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; the Stats screen still shows the result.
        } catch (e: Exception) {
            reportException(e)
        }
    }

    private fun postFailureNotification(reason: String?) {
        val context = applicationContext
        try {
            createNotificationChannel(context)
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.music_note)
                    .setContentTitle(
                        context.getString(R.string.tempo_import_notification_failed_title),
                    ).setContentText(
                        reason?.takeIf { it.isNotBlank() }
                            ?: context.getString(R.string.tempo_import_working),
                    ).setContentIntent(contentPendingIntent())
                    .setAutoCancel(true)
                    .build()
            context
                .getSystemService(NotificationManager::class.java)
                ?.notify(COMPLETION_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; the Stats screen still shows the error.
        } catch (e: Exception) {
            reportException(e)
        }
    }

    private fun contentPendingIntent(): PendingIntent {
        val openStatsIntent =
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("navigate_to", "stats")
            }
        return PendingIntent.getActivity(
            applicationContext,
            0,
            openStatsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.tempo_import_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "tempo_history_import"

        const val KEY_SOURCE = "source"
        const val KEY_ZIP_URI = "zip_uri"
        const val KEY_SEARCH_MISSING = "search_missing"
        const val KEY_DRIVE_FILE_ID = "drive_file_id"
        const val KEY_DRIVE_FILE_NAME = "drive_file_name"
        const val KEY_DRIVE_MODIFIED_TIME = "drive_modified_time"

        const val SOURCE_FILE = "file"
        const val SOURCE_DRIVE = "drive"

        const val PROGRESS_DONE = "progress_done"
        const val PROGRESS_TOTAL = "progress_total"

        const val OUTPUT_IMPORTED = "imported"
        const val OUTPUT_MATCHED_LOCAL = "matched_local"
        const val OUTPUT_MATCHED_SEARCH = "matched_search"
        const val OUTPUT_SKIPPED = "skipped"
        const val OUTPUT_FAILED = "failed"
        const val OUTPUT_ERROR = "error"
        const val OUTPUT_ALREADY_IMPORTED = "already_imported"

        private const val CHANNEL_ID = "tempo_import_channel"
        private const val NOTIFICATION_ID = 9001
        private const val COMPLETION_NOTIFICATION_ID = 9002
        private const val PROGRESS_PUBLISH_INTERVAL_MS = 500L

        fun enqueue(
            context: Context,
            zipUri: Uri,
            searchMissing: Boolean,
        ): UUID {
            val requestBuilder =
                OneTimeWorkRequestBuilder<TempoImportWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_SOURCE to SOURCE_FILE,
                            KEY_ZIP_URI to zipUri.toString(),
                            KEY_SEARCH_MISSING to searchMissing,
                        ),
                    )
            if (searchMissing) {
                // The YouTube-search matching tier needs network.
                requestBuilder.setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
            }
            val request = requestBuilder.build()
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            return request.id
        }

        fun enqueueDrive(
            context: Context,
            driveFileId: String,
            driveFileName: String,
            driveModifiedTimeMillis: Long,
            searchMissing: Boolean,
        ): UUID {
            val request =
                OneTimeWorkRequestBuilder<TempoImportWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_SOURCE to SOURCE_DRIVE,
                            KEY_DRIVE_FILE_ID to driveFileId,
                            KEY_DRIVE_FILE_NAME to driveFileName,
                            KEY_DRIVE_MODIFIED_TIME to driveModifiedTimeMillis,
                            KEY_SEARCH_MISSING to searchMissing,
                        ),
                    )
                    // Downloading the backup always needs network.
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).build()
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            return request.id
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TempoImportWorkerEntryPoint {
    fun database(): MusicDatabase
}
