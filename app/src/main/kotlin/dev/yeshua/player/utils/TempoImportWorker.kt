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
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dev.yeshua.player.MainActivity
import dev.yeshua.player.R
import dev.yeshua.player.db.MusicDatabase
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a Tempo backup import in the background so it survives leaving the Stats
 * screen (or the app). The backup ZIP uri must have a persistable read permission
 * (taken via [android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION] when the user
 * picks the file with [androidx.activity.result.contract.ActivityResultContracts.OpenDocument]).
 *
 * Progress is published with [setProgress] ([PROGRESS_DONE]/[PROGRESS_TOTAL]); the
 * Stats screen observes the work to render it. On completion a notification is posted.
 */
class TempoImportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        coroutineScope {
            val uriString = inputData.getString(KEY_ZIP_URI) ?: return@coroutineScope Result.failure()
            val searchMissing = inputData.getBoolean(KEY_SEARCH_MISSING, true)
            val uri = Uri.parse(uriString)

            val database =
                EntryPointAccessors
                    .fromApplication(applicationContext, TempoImportWorkerEntryPoint::class.java)
                    .database()

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

            try {
                val result =
                    TempoHistoryImporter.importTempoBackup(
                        zipUri = uri,
                        context = applicationContext,
                        database = database,
                        searchMissing = searchMissing,
                        onProgress = { done, total ->
                            progressDone.set(done)
                            progressTotal.set(total)
                        },
                    )
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
            } catch (security: SecurityException) {
                // Persisted URI permission was revoked mid-import.
                reportException(security)
                Result.failure(workDataOf(OUTPUT_ERROR to security.message))
            } catch (e: Exception) {
                reportException(e)
                Result.failure(workDataOf(OUTPUT_ERROR to e.message))
            } finally {
                progressPublisher.cancel()
            }
        }

    private fun postCompletionNotification(result: TempoImportResult) {
        val context = applicationContext
        try {
            createNotificationChannel(context)
            val openStatsIntent =
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("navigate_to", "stats")
                }
            val pendingIntent =
                PendingIntent.getActivity(
                    context,
                    0,
                    openStatsIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
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
                    ).setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager?.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; the Stats screen still shows the result.
        } catch (e: Exception) {
            reportException(e)
        }
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

        const val KEY_ZIP_URI = "zip_uri"
        const val KEY_SEARCH_MISSING = "search_missing"

        const val PROGRESS_DONE = "progress_done"
        const val PROGRESS_TOTAL = "progress_total"

        const val OUTPUT_IMPORTED = "imported"
        const val OUTPUT_MATCHED_LOCAL = "matched_local"
        const val OUTPUT_MATCHED_SEARCH = "matched_search"
        const val OUTPUT_SKIPPED = "skipped"
        const val OUTPUT_FAILED = "failed"
        const val OUTPUT_ERROR = "error"

        private const val CHANNEL_ID = "tempo_import_channel"
        private const val NOTIFICATION_ID = 9001
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
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TempoImportWorkerEntryPoint {
    fun database(): MusicDatabase
}
