/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.utils.drive

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import java.io.File

/**
 * Foss-flavor stub: no Google Play services / Drive libraries exist in this
 * build, so every Drive operation reports [TempoDriveErrorKind.UNAVAILABLE]
 * and the Stats import dialog explains that a backup file should be used.
 * Same FQN/API as the gms implementation, mirroring the cast repository split.
 */
class DefaultTempoDriveGateway(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : TempoDriveGateway {
    override fun isAvailable(): Boolean = false

    override suspend fun signIn(activity: Activity): TempoDriveAuthResult =
        TempoDriveAuthResult.Failed(TempoDriveErrorKind.UNAVAILABLE)

    override fun pendingConsentIntent(): PendingIntent? = null

    override suspend fun completeConsent(): TempoDriveAuthResult =
        TempoDriveAuthResult.Failed(TempoDriveErrorKind.UNAVAILABLE)

    override suspend fun authorizedAccountEmail(context: Context): String? = null

    override suspend fun ensureAuthorized(context: Context): Boolean = false

    override suspend fun listBackups(context: Context): List<TempoDriveBackup> =
        throw TempoDriveException(TempoDriveErrorKind.UNAVAILABLE)

    override suspend fun downloadBackup(
        context: Context,
        backup: TempoDriveBackup,
        onProgress: (fraction: Double) -> Unit,
    ): File = throw TempoDriveException(TempoDriveErrorKind.UNAVAILABLE)

    override suspend fun signOut(context: Context) = Unit
}
