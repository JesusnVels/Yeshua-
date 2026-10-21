/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.utils

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.parametersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Tidal account linking via Tidal's OAuth 2.0 **device-code flow** — the
 * same flow Tidal's TV apps use, and the one open-source clients
 * (python-tidalapi, tidal-dl-ng) implement against the user's OWN Tidal
 * account. There is no embedded username/password anywhere: the user
 * approves the link on tidal.com (link.tidal.com/<code>) with their own
 * credentials, and this app only ever receives OAuth tokens.
 *
 * Client credentials: Tidal's partner API is not open to us, so the flow
 * runs with the client_id/client_secret pair shipped inside Tidal's own
 * Fire TV app — the pair python-tidalapi ships for exactly this flow.
 * Provenance and rotation: these values are public (they ship in a
 * consumer app binary and in multiple GPL projects) and Tidal can rotate
 * or revoke them at any time; if linking ever starts failing with
 * `invalid_client`, the constants below are what needs replacing.
 *
 * VERIFIED LIVE (2026-10-21, without an account):
 *  - POST /v1/oauth2/device_authorization with this client_id returns a
 *    real deviceCode/userCode pair.
 *  - Polling /v1/oauth2/token for an unapproved code returns the
 *    textbook `authorization_pending` error (sub_status 1002) — only
 *    when the secret is sent as a form field; HTTP Basic is rejected.
 *  - grant_type=refresh_token with a bogus token returns
 *    `invalid_grant` ("Token has invalid payload") — the client itself
 *    is accepted; the error is about the token, proving the grant shape.
 * The final approval step can by definition only complete with the
 * user's real account.
 *
 * Only tokens and account identifiers are ever persisted (by callers,
 * via the Tidal* preference keys) — never a password.
 */
internal object TidalAuth {
    private const val TAG = "TidalAuth"
    private const val AUTH_TIMEOUT_MS = 15_000L

    private const val AUTH_BASE = "https://auth.tidal.com/v1/oauth2"
    const val API_BASE = "https://api.tidal.com/v1"

    // Client credentials from Tidal's own Fire TV app (see class doc).
    const val CLIENT_ID = "zU4XHVVkc2tDPo4t"
    private const val CLIENT_SECRET = "VJKhDFqJPqvsPVNBV6ukXTJmwlvbttP7wlMlrc72se4="
    private const val SCOPE = "r_usr+w_usr+w_sub"
    private const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

    private val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = AUTH_TIMEOUT_MS
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = AUTH_TIMEOUT_MS
            }
        }
    }

    /** A device-code challenge: show [userCode] / open [verificationUriComplete]. */
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val expiresInSeconds: Long,
        val intervalSeconds: Long,
    )

    /** OAuth token pair. [refreshToken] is null when Tidal didn't rotate it. */
    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSeconds: Long,
        val userId: Long?,
    )

    /** Result of one token-endpoint poll for a pending device code. */
    sealed interface PollResult {
        /** User hasn't approved yet — poll again after the interval. */
        data object Pending : PollResult

        /** User approved — [tokens] are ready to persist. */
        data class Success(
            val tokens: Tokens,
        ) : PollResult

        /** Expired code, denied, or a protocol error — stop polling. */
        data class Failure(
            val message: String,
        ) : PollResult
    }

    /** Account facts from GET /v1/sessions for the linked session. */
    data class SessionInfo(
        val userId: Long,
        val countryCode: String,
    )

    @Serializable
    private data class DeviceCodeResponse(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String? = null,
        val verificationUriComplete: String? = null,
        val expiresIn: Long = 300,
        val interval: Long = 5,
    )

    @Serializable
    private data class TokenResponse(
        val access_token: String,
        val refresh_token: String? = null,
        val token_type: String? = null,
        val expires_in: Long = 0,
        val user_id: Long? = null,
        val scope: String? = null,
    )

    @Serializable
    private data class OAuthErrorResponse(
        val error: String? = null,
        val error_description: String? = null,
        val status: Int? = null,
        val sub_status: Int? = null,
    )

    @Serializable
    private data class SessionResponse(
        val sessionId: String? = null,
        val userId: Long? = null,
        val countryCode: String? = null,
    )

    /**
     * Step 1 of the device flow: ask Tidal for a code the user can approve
     * at [DeviceCode.verificationUriComplete] (link.tidal.com/<userCode>).
     */
    suspend fun requestDeviceCode(): Result<DeviceCode> =
        runCatching {
            withContext(Dispatchers.IO) {
                withTimeout(AUTH_TIMEOUT_MS) {
                    val response =
                        httpClient.submitForm(
                            url = "$AUTH_BASE/device_authorization",
                            formParameters =
                                parametersOf(
                                    "client_id" to listOf(CLIENT_ID),
                                    "scope" to listOf(SCOPE),
                                ),
                        )
                    if (!response.status.isSuccess()) {
                        throw IllegalStateException(
                            "Tidal device authorization failed: ${response.errorMessage()}",
                        )
                    }
                    val body = response.body<DeviceCodeResponse>()
                    DeviceCode(
                        deviceCode = body.deviceCode,
                        userCode = body.userCode,
                        verificationUri = body.verificationUri ?: "link.tidal.com",
                        verificationUriComplete =
                            body.verificationUriComplete
                                ?: "link.tidal.com/${body.userCode}",
                        expiresInSeconds = body.expiresIn,
                        intervalSeconds = body.interval.coerceAtLeast(1),
                    )
                }
            }
        }

    /**
     * Step 2: ONE poll of the token endpoint for [deviceCode]. Returns
     * [PollResult.Pending] while the user hasn't approved — the caller
     * owns the polling loop (the settings UI polls while visible), so
     * this never blocks waiting for a human.
     */
    suspend fun pollForToken(deviceCode: String): PollResult =
        try {
            withContext(Dispatchers.IO) {
                withTimeout(AUTH_TIMEOUT_MS) {
                    val response =
                        httpClient.submitForm(
                            url = "$AUTH_BASE/token",
                            formParameters =
                                parametersOf(
                                    "client_id" to listOf(CLIENT_ID),
                                    "client_secret" to listOf(CLIENT_SECRET),
                                    "device_code" to listOf(deviceCode),
                                    "grant_type" to listOf(DEVICE_CODE_GRANT),
                                    "scope" to listOf(SCOPE),
                                ),
                        )
                    when {
                        response.status.isSuccess() ->
                            PollResult.Success(response.body<TokenResponse>().toTokens())

                        else -> {
                            val error = runCatching { response.body<OAuthErrorResponse>() }.getOrNull()
                            when (error?.error) {
                                // Not approved yet (or Tidal asks us to slow
                                // down) — both mean "keep polling".
                                "authorization_pending", "slow_down" -> PollResult.Pending
                                else ->
                                    PollResult.Failure(
                                        error?.error_description
                                            ?: error?.error
                                            ?: "Tidal token request failed (${response.status.value})",
                                    )
                            }
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Tidal token poll failed")
            PollResult.Failure(e.message ?: "Network error while linking Tidal")
        }

    /**
     * Exchange a stored refresh token for a fresh access token
     * (grant_type=refresh_token). Used by TidalStreamResolver on a 401.
     */
    suspend fun refreshTokens(refreshToken: String): Result<Tokens> =
        runCatching {
            withContext(Dispatchers.IO) {
                withTimeout(AUTH_TIMEOUT_MS) {
                    val response =
                        httpClient.submitForm(
                            url = "$AUTH_BASE/token",
                            formParameters =
                                parametersOf(
                                    "client_id" to listOf(CLIENT_ID),
                                    "client_secret" to listOf(CLIENT_SECRET),
                                    "refresh_token" to listOf(refreshToken),
                                    "grant_type" to listOf("refresh_token"),
                                    "scope" to listOf(SCOPE),
                                ),
                        )
                    if (!response.status.isSuccess()) {
                        throw IllegalStateException(
                            "Tidal token refresh failed: ${response.errorMessage()}",
                        )
                    }
                    response.body<TokenResponse>().toTokens()
                }
            }
        }

    /**
     * Fetch the linked session's user id + country code
     * (GET /v1/sessions). The playback/search APIs need the country code
     * and Tidal serves catalog per-country, so this is captured once at
     * link time and persisted alongside the tokens.
     */
    suspend fun fetchSession(accessToken: String): Result<SessionInfo> =
        runCatching {
            withContext(Dispatchers.IO) {
                withTimeout(AUTH_TIMEOUT_MS) {
                    val response =
                        httpClient.get("$API_BASE/sessions") {
                            header(HttpHeaders.Authorization, "Bearer $accessToken")
                            header(HttpHeaders.Accept, "application/json")
                        }
                    if (!response.status.isSuccess()) {
                        throw IllegalStateException(
                            "Tidal session fetch failed (${response.status.value})",
                        )
                    }
                    val body = response.body<SessionResponse>()
                    SessionInfo(
                        userId =
                            body.userId
                                ?: throw IllegalStateException("Tidal session has no userId"),
                        countryCode =
                            body.countryCode?.takeIf { it.isNotBlank() }
                                ?: throw IllegalStateException("Tidal session has no countryCode"),
                    )
                }
            }
        }

    private fun TokenResponse.toTokens(): Tokens =
        Tokens(
            accessToken = access_token,
            refreshToken = refresh_token,
            expiresInSeconds = expires_in,
            userId = user_id,
        )

    private suspend fun HttpResponse.errorMessage(): String {
        val error = runCatching { body<OAuthErrorResponse>() }.getOrNull()
        return error?.error_description ?: error?.error ?: "HTTP ${status.value}"
    }

    /** Kept for callers that only have the status (e.g. logging in the resolver). */
    fun isUnauthorized(status: HttpStatusCode): Boolean = status == HttpStatusCode.Unauthorized
}
