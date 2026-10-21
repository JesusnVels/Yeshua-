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
 * One Tempo backup stored in the user's Google Drive ("Tempo Backups" folder).
 *
 * [modifiedTimeMillis] is the Drive `modifiedTime` (falling back to
 * `createdTime` when absent); listings are newest-modified first.
 */
data class TempoDriveBackup(
    val id: String,
    val name: String,
    val modifiedTimeMillis: Long,
    val sizeBytes: Long,
)

/** Why a Drive operation could not complete. Surfaced to the UI as a state, never faked as an empty result. */
enum class TempoDriveErrorKind {
    /** This build has no Google Drive support (foss flavor). */
    UNAVAILABLE,

    /** No GOOGLE_WEB_CLIENT_ID is configured in this build, so sign-in cannot start. */
    NOT_CONFIGURED,

    /** The user has not signed in / authorized yet. */
    NOT_AUTHORIZED,

    /** Consent was declined, authorization expired, or Drive answered 401/403. */
    ACCESS_DENIED,

    /** Anything else (network, Play services, parsing). */
    FAILED,
}

class TempoDriveException(
    val kind: TempoDriveErrorKind,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Outcome of an interactive sign-in / authorization attempt. */
sealed interface TempoDriveAuthResult {
    /** Drive access is authorized; backups can be listed and downloaded. */
    data object Authorized : TempoDriveAuthResult

    /** Google requires the user to consent via [TempoDriveGateway.pendingConsentIntent]. */
    data object NeedsConsent : TempoDriveAuthResult

    /** The user dismissed the sign-in UI. */
    data object Cancelled : TempoDriveAuthResult

    /** Sign-in/authorization failed; [kind] says why. */
    data class Failed(val kind: TempoDriveErrorKind, val message: String? = null) : TempoDriveAuthResult
}

/**
 * Flavor-provided access to Tempo's Google Drive backups.
 *
 * The contract lives in the main source set; [DefaultTempoDriveGateway] is
 * implemented per distribution flavor (real Google implementation in `gms`,
 * an unavailable stub in `foss`), mirroring how the cast repository is split.
 *
 * Only the account email + an authorized flag are persisted in the app
 * preferences; OAuth tokens never leave the implementation's own storage and
 * are never logged or displayed.
 */
interface TempoDriveGateway {
    /** True when this build can talk to Google Drive at all. */
    fun isAvailable(): Boolean

    /** Interactive sign-in (Credential Manager) followed by Drive authorization. Needs an [Activity]. */
    suspend fun signIn(activity: Activity): TempoDriveAuthResult

    /** The consent [PendingIntent] to launch when [signIn] returned [TempoDriveAuthResult.NeedsConsent]. */
    fun pendingConsentIntent(): PendingIntent?

    /** Re-runs authorization after the user completed (or dismissed) the consent screen. */
    suspend fun completeConsent(): TempoDriveAuthResult

    /** Email of the authorized account, or null when not authorized. */
    suspend fun authorizedAccountEmail(context: Context): String?

    /** Silently restores authorization for background work (worker downloads). */
    suspend fun ensureAuthorized(context: Context): Boolean

    /** Backups in the "Tempo Backups" folder, newest first. Throws [TempoDriveException] on failure. */
    suspend fun listBackups(context: Context): List<TempoDriveBackup>

    /**
     * Downloads [backup] into the app's cache dir and returns the local file.
     * [onProgress] receives the downloaded fraction (0.0–1.0) as chunks arrive;
     * it may stay 0.0 when Drive does not report a content length.
     * Throws [TempoDriveException].
     */
    suspend fun downloadBackup(
        context: Context,
        backup: TempoDriveBackup,
        onProgress: (fraction: Double) -> Unit = {},
    ): File

    /** Forgets the account and any stored authorization. */
    suspend fun signOut(context: Context)
}

/** Process-wide locator, mirroring the cast repository locator pattern. */
object TempoDriveGatewayLocator {
    @Volatile
    private var gateway: TempoDriveGateway? = null

    fun get(context: Context): TempoDriveGateway =
        gateway ?: synchronized(this) {
            gateway ?: DefaultTempoDriveGateway(context.applicationContext).also { gateway = it }
        }
}
