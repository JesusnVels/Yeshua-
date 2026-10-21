/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Google Drive access pattern adapted from Tempo (github.com/avinaxhroy/Tempo,
 * GPL-3.0): Credential Manager sign-in + Identity AuthorizationClient, with the
 * access token kept in EncryptedSharedPreferences. One deliberate difference:
 * Tempo authorizes DRIVE_FILE (its own backups); Yeshua must READ Tempo's
 * backups, so it requests DRIVE_READONLY instead.
 */

package dev.yeshua.player.utils.drive

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.datastore.preferences.core.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import dev.yeshua.player.BuildConfig
import dev.yeshua.player.constants.TempoDriveAccountEmailKey
import dev.yeshua.player.constants.TempoDriveAuthorizedKey
import dev.yeshua.player.utils.dataStore
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DefaultTempoDriveGateway(
    private val appContext: Context,
) : TempoDriveGateway {
    private val authorizationClient = Identity.getAuthorizationClient(appContext)
    private val authMutex = Mutex()

    @Volatile
    private var authorizationResult: AuthorizationResult? = null

    @Volatile
    private var authorizationResolution: PendingIntent? = null

    @Volatile
    private var currentEmail: String? = null

    override fun isAvailable(): Boolean = true

    // ----------------------------------------------------------------------
    // Sign-in + authorization
    // ----------------------------------------------------------------------

    override suspend fun signIn(activity: Activity): TempoDriveAuthResult =
        authMutex.withLock {
            val webClientId = configuredWebClientId()
                ?: return@withLock TempoDriveAuthResult.Failed(TempoDriveErrorKind.NOT_CONFIGURED)
            withContext(Dispatchers.Main) {
                try {
                    val credentialManager = CredentialManager.create(activity)
                    val option =
                        GetSignInWithGoogleOption
                            .Builder(webClientId)
                            .build()
                    val request =
                        GetCredentialRequest
                            .Builder()
                            .addCredentialOption(option)
                            .build()
                    // Credential Manager UI must run with the Activity context.
                    val response = credentialManager.getCredential(activity, request)
                    handleSignInResponse(response)
                } catch (e: GetCredentialCancellationException) {
                    TempoDriveAuthResult.Cancelled
                } catch (e: NoCredentialException) {
                    TempoDriveAuthResult.Failed(
                        TempoDriveErrorKind.FAILED,
                        "Google Sign-In is unavailable on this device.",
                    )
                } catch (e: GetCredentialException) {
                    TempoDriveAuthResult.Failed(TempoDriveErrorKind.FAILED, e.message)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TempoDriveAuthResult.Failed(TempoDriveErrorKind.FAILED, e.message)
                }
            }
        }

    private suspend fun handleSignInResponse(response: GetCredentialResponse): TempoDriveAuthResult {
        val credential = response.credential
        if (credential !is CustomCredential ||
            (
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL &&
                    credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL
                )
        ) {
            return TempoDriveAuthResult.Failed(TempoDriveErrorKind.FAILED, "Unexpected credential type.")
        }
        val googleCredential =
            try {
                GoogleIdTokenCredential.createFrom(credential.data)
            } catch (e: Exception) {
                return TempoDriveAuthResult.Failed(TempoDriveErrorKind.FAILED, e.message)
            }
        val email =
            googleCredential.email?.takeIf { it.isNotBlank() }
                ?: googleCredential.id.takeIf { it.isNotBlank() }
                ?: return TempoDriveAuthResult.Failed(
                    TempoDriveErrorKind.FAILED,
                    "Google Sign-In did not return an account.",
                )
        currentEmail = email
        tokenStorage(appContext).saveAccountEmail(email)
        persistAccount(email = email, authorized = false)
        // A fresh account selection invalidates any previous authorization.
        authorizationResult = null
        authorizationResolution = null
        tokenStorage(appContext).clearToken()
        return requestDriveAuthorization()
    }

    /** Runs the Drive authorization; returns the outcome and persists a good token. */
    private suspend fun requestDriveAuthorization(): TempoDriveAuthResult =
        withContext(Dispatchers.IO) {
            try {
                authorizationResolution = null
                val result = Tasks.await(authorizationClient.authorize(buildAuthRequest()))
                authorizationResult = result
                when {
                    result.hasResolution() -> {
                        authorizationResolution = result.pendingIntent
                        TempoDriveAuthResult.NeedsConsent
                    }

                    else -> {
                        val token = result.accessToken
                        val hasScope =
                            result.grantedScopes.orEmpty().any { it.contains(DriveScopes.DRIVE_READONLY) }
                        if (token != null && hasScope) {
                            tokenStorage(appContext).saveAccessToken(token)
                            persistAccount(email = currentEmail, authorized = true)
                            TempoDriveAuthResult.Authorized
                        } else {
                            // Token without the readonly scope — useless; don't persist it.
                            authorizationResult = null
                            tokenStorage(appContext).clearToken()
                            TempoDriveAuthResult.Failed(TempoDriveErrorKind.ACCESS_DENIED)
                        }
                    }
                }
            } catch (e: ApiException) {
                authorizationResult = null
                authorizationResolution = e.status.resolution
                if (authorizationResolution != null) {
                    TempoDriveAuthResult.NeedsConsent
                } else {
                    TempoDriveAuthResult.Failed(TempoDriveErrorKind.ACCESS_DENIED, e.status.statusMessage)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val apiException = e.cause as? ApiException
                authorizationResolution = apiException?.status?.resolution
                if (apiException != null && authorizationResolution != null) {
                    TempoDriveAuthResult.NeedsConsent
                } else {
                    TempoDriveAuthResult.Failed(TempoDriveErrorKind.FAILED, e.message)
                }
            }
        }

    override fun pendingConsentIntent(): PendingIntent? =
        authorizationResolution ?: authorizationResult?.pendingIntent

    override suspend fun completeConsent(): TempoDriveAuthResult =
        authMutex.withLock {
            authorizationResolution = null
            when (val result = requestDriveAuthorization()) {
                is TempoDriveAuthResult.Authorized -> result
                is TempoDriveAuthResult.NeedsConsent -> result
                else -> TempoDriveAuthResult.Failed(TempoDriveErrorKind.ACCESS_DENIED)
            }
        }

    override suspend fun authorizedAccountEmail(context: Context): String? {
        val prefs = context.dataStore.data.first()
        return if (prefs[TempoDriveAuthorizedKey] == true) {
            prefs[TempoDriveAccountEmailKey] ?: tokenStorage(context).getAccountEmail()
        } else {
            null
        }
    }

    override suspend fun ensureAuthorized(context: Context): Boolean =
        authMutex.withLock {
            if (getValidAccessToken(context) != null) return@withLock true
            // Silent re-authorization: Play Services may complete it without UI
            // when the user already consented; a resolution means we cannot.
            if (currentEmail == null) {
                currentEmail =
                    context.dataStore.data.first()[TempoDriveAccountEmailKey]
                        ?: tokenStorage(context).getAccountEmail()
            }
            if (configuredWebClientId() == null || currentEmail == null) return@withLock false
            when (requestDriveAuthorization()) {
                is TempoDriveAuthResult.Authorized -> true
                else -> false
            }
        }

    override suspend fun signOut(context: Context) {
        authMutex.withLock {
            authorizationResult = null
            authorizationResolution = null
            currentEmail = null
            tokenStorage(context).clearAll()
            context.dataStore.edit { prefs ->
                prefs.remove(TempoDriveAccountEmailKey)
                prefs.remove(TempoDriveAuthorizedKey)
            }
        }
    }

    // ----------------------------------------------------------------------
    // Drive access
    // ----------------------------------------------------------------------

    override suspend fun listBackups(context: Context): List<TempoDriveBackup> =
        withDriveService(context) { service ->
            val folderId = findBackupsFolderId(service) ?: return@withDriveService emptyList()
            val result =
                service
                    .files()
                    .list()
                    .setQ("'$folderId' in parents and name contains '$BACKUP_FILE_PREFIX' and trashed = false")
                    .setSpaces("drive")
                    .setFields("files(id, name, size, modifiedTime, createdTime)")
                    .setOrderBy("modifiedTime desc")
                    .execute()
            result.files.orEmpty().map { file ->
                TempoDriveBackup(
                    id = file.id,
                    name = file.name.orEmpty(),
                    modifiedTimeMillis = file.modifiedTime?.value ?: file.createdTime?.value ?: 0L,
                    sizeBytes = file.getSize()?.toLong() ?: 0L,
                )
            }
        }

    override suspend fun downloadBackup(
        context: Context,
        backup: TempoDriveBackup,
        onProgress: (fraction: Double) -> Unit,
    ): File =
        withDriveService(context) { service ->
            // Never trust the remote display name as a local path.
            val file = File.createTempFile("tempo_drive_", ".tempo", context.cacheDir)
            try {
                val request = service.files().get(backup.id)
                // Chunked download (not direct) so progress is reported per
                // chunk; stream it buffered straight into the cache file the
                // importer will read in place — no intermediate copies.
                val downloader = request.mediaHttpDownloader
                downloader.isDirectDownloadEnabled = false
                downloader.chunkSize = com.google.api.client.googleapis.media.MediaHttpDownloader.MAXIMUM_CHUNK_SIZE
                downloader.setProgressListener { d -> onProgress(d.progress) }
                java.io.BufferedOutputStream(FileOutputStream(file)).use { output ->
                    request.executeMediaAndDownloadTo(output)
                }
                onProgress(1.0)
                file
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }

    private fun findBackupsFolderId(service: Drive): String? {
        val result =
            service
                .files()
                .list()
                .setQ(
                    "name = '$BACKUP_FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and trashed = false",
                ).setSpaces("drive")
                .setFields("files(id, name)")
                .execute()
        return result.files?.firstOrNull()?.id
    }

    /**
     * Runs [block] with an authenticated Drive service. A 401 triggers one
     * silent re-authorization + retry; 401/403 surface as
     * [TempoDriveErrorKind.ACCESS_DENIED] so the UI can say "sign in again"
     * instead of showing a fake empty result.
     */
    private suspend fun <T> withDriveService(
        context: Context,
        block: (Drive) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            var token =
                getValidAccessToken(context)
                    ?: throw TempoDriveException(TempoDriveErrorKind.NOT_AUTHORIZED)
            try {
                block(buildDriveService(token))
            } catch (e: GoogleJsonResponseException) {
                when (e.statusCode) {
                    401 -> {
                        tokenStorage(context).clearToken()
                        if (ensureAuthorized(context)) {
                            token =
                                getValidAccessToken(context)
                                    ?: throw TempoDriveException(TempoDriveErrorKind.ACCESS_DENIED)
                            try {
                                block(buildDriveService(token))
                            } catch (retry: GoogleJsonResponseException) {
                                throw mapDriveException(retry)
                            }
                        } else {
                            throw TempoDriveException(TempoDriveErrorKind.ACCESS_DENIED, cause = e)
                        }
                    }

                    else -> throw mapDriveException(e)
                }
            } catch (e: HttpResponseException) {
                throw mapDriveException(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TempoDriveException) {
                throw e
            } catch (e: Exception) {
                throw TempoDriveException(TempoDriveErrorKind.FAILED, e.message, e)
            }
        }

    private fun mapDriveException(e: HttpResponseException): TempoDriveException =
        when (e.statusCode) {
            401, 403 -> TempoDriveException(TempoDriveErrorKind.ACCESS_DENIED, cause = e)
            else -> TempoDriveException(TempoDriveErrorKind.FAILED, e.message, e)
        }

    private fun buildDriveService(accessToken: String): Drive =
        Drive
            .Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
            ) { request ->
                request.headers.authorization = "Bearer $accessToken"
            }.setApplicationName("Yeshua")
            .build()

    // ----------------------------------------------------------------------
    // Tokens & account persistence
    // ----------------------------------------------------------------------

    private suspend fun getValidAccessToken(context: Context): String? {
        val storage = tokenStorage(context)
        val token = storage.getAccessToken() ?: return null
        return if (storage.isTokenStale()) null else token
    }

    private suspend fun persistAccount(
        email: String?,
        authorized: Boolean,
    ) {
        appContext.dataStore.edit { prefs ->
            if (email != null) {
                prefs[TempoDriveAccountEmailKey] = email
            }
            prefs[TempoDriveAuthorizedKey] = authorized
        }
    }

    private fun buildAuthRequest(): AuthorizationRequest {
        val webClientId =
            requireNotNull(configuredWebClientId()) {
                "GOOGLE_WEB_CLIENT_ID is required for Google Drive authorization"
            }
        val builder =
            AuthorizationRequest
                .Builder()
                .setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_READONLY)))
        currentEmail?.let { email ->
            builder.setAccount(Account(email, "com.google"))
        }
        builder.requestOfflineAccess(webClientId)
        return builder.build()
    }

    private fun configuredWebClientId(): String? =
        BuildConfig.GOOGLE_WEB_CLIENT_ID.trim().takeIf { it.isNotEmpty() }

    private fun tokenStorage(context: Context): TempoDriveTokenStorage = TempoDriveTokenStorage(context)

    companion object {
        private const val BACKUP_FOLDER_NAME = "Tempo Backups"
        private const val BACKUP_FILE_PREFIX = "tempo_backup_"
    }
}

/**
 * Access-token + account storage in EncryptedSharedPreferences, following
 * Tempo's GoogleDriveTokenStorage pattern. Access tokens live ~1 hour; they
 * are treated as stale after 55 minutes. Tokens are never logged.
 */
private class TempoDriveTokenStorage(
    context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey =
            MasterKey
                .Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun saveAccessToken(accessToken: String) {
        require(accessToken.isNotBlank()) { "Access token must not be blank" }
        prefs
            .edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_TOKEN_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun isTokenStale(): Boolean {
        val timestamp = prefs.getLong(KEY_TOKEN_TIMESTAMP, 0L)
        if (timestamp == 0L) return true
        val age = System.currentTimeMillis() - timestamp
        return age < 0L || age > TOKEN_STALE_THRESHOLD_MS
    }

    fun saveAccountEmail(email: String) {
        prefs.edit().putString(KEY_ACCOUNT_EMAIL, email).apply()
    }

    fun getAccountEmail(): String? = prefs.getString(KEY_ACCOUNT_EMAIL, null)?.takeIf { it.isNotBlank() }

    fun clearToken() {
        prefs
            .edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_TOKEN_TIMESTAMP)
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "tempo_drive_auth_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_TOKEN_TIMESTAMP = "token_timestamp"
        private const val KEY_ACCOUNT_EMAIL = "account_email"
        private const val TOKEN_STALE_THRESHOLD_MS = 55 * 60 * 1000L
    }
}
