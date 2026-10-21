/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.yeshua.player.R
import dev.yeshua.player.constants.TidalAccessTokenKey
import dev.yeshua.player.constants.TidalCountryCodeKey
import dev.yeshua.player.constants.TidalRefreshTokenKey
import dev.yeshua.player.constants.TidalUserIdKey
import dev.yeshua.player.ui.component.PreferenceEntry
import dev.yeshua.player.ui.component.PreferenceGroup
import dev.yeshua.player.utils.TidalAuth
import dev.yeshua.player.utils.TidalStreamResolver
import dev.yeshua.player.utils.rememberPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "Tidal" group for Stream Sources settings: links the user's OWN Tidal
 * account with the OAuth device-code flow (see [TidalAuth]). The user
 * approves on link.tidal.com in their browser — no password is ever
 * typed into the app — and only the OAuth tokens + account id/country
 * are persisted (the Tidal* preference keys). While a code is pending,
 * this section polls the token endpoint for as long as it stays on
 * screen; leaving the screen cancels the poll (the code itself stays
 * valid for its 5 minutes if the user comes back and restarts).
 *
 * Self-contained: StreamSourcesSettings only has to call
 * `TidalAuthSection()` inside its column (see TIDAL_WIRING.md).
 */
@Composable
fun TidalAuthSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val (accessToken, onAccessTokenChange) =
        rememberPreference(TidalAccessTokenKey, defaultValue = "")
    val (refreshToken, onRefreshTokenChange) =
        rememberPreference(TidalRefreshTokenKey, defaultValue = "")
    val (countryCode, onCountryCodeChange) =
        rememberPreference(TidalCountryCodeKey, defaultValue = "")
    val (userId, onUserIdChange) =
        rememberPreference(TidalUserIdKey, defaultValue = 0L)

    var pendingCode by remember { mutableStateOf<TidalAuth.DeviceCode?>(null) }
    var requestingCode by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }

    val isLinked = accessToken.isNotBlank()

    fun startLinking() {
        linkError = null
        requestingCode = true
        scope.launch {
            TidalAuth
                .requestDeviceCode()
                .onSuccess { pendingCode = it }
                .onFailure { error ->
                    linkError =
                        error.message ?: context.getString(R.string.tidal_link_failed)
                }
            requestingCode = false
        }
    }

    // Poll while a code is pending and this section is composed.
    LaunchedEffect(pendingCode) {
        val code = pendingCode ?: return@LaunchedEffect
        val deadlineMs = System.currentTimeMillis() + code.expiresInSeconds * 1_000L
        while (isActive && System.currentTimeMillis() < deadlineMs) {
            delay(code.intervalSeconds * 1_000L)
            when (val result = TidalAuth.pollForToken(code.deviceCode)) {
                TidalAuth.PollResult.Pending -> Unit

                is TidalAuth.PollResult.Success -> {
                    val tokens = result.tokens
                    // Country/user id come from the session; if that fetch
                    // fails we still keep the tokens (the resolver falls
                    // back to "US") rather than discarding a good link.
                    val session = TidalAuth.fetchSession(tokens.accessToken).getOrNull()
                    onAccessTokenChange(tokens.accessToken)
                    onRefreshTokenChange(tokens.refreshToken.orEmpty())
                    onCountryCodeChange(session?.countryCode ?: "US")
                    onUserIdChange(session?.userId ?: tokens.userId ?: 0L)
                    pendingCode = null
                    return@LaunchedEffect
                }

                is TidalAuth.PollResult.Failure -> {
                    linkError = result.message
                    pendingCode = null
                    return@LaunchedEffect
                }
            }
        }
        // Code expired unapproved.
        if (pendingCode == code) pendingCode = null
    }

    PreferenceGroup(title = stringResource(R.string.tidal_source)) {
        if (isLinked) {
            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.tidal_linked)) },
                    description =
                        stringResource(
                            R.string.tidal_linked_country,
                            countryCode.ifBlank { "US" },
                        ),
                )
            }
            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.tidal_sign_out)) },
                    description = stringResource(R.string.tidal_sign_out_description),
                    onClick = {
                        onAccessTokenChange("")
                        onRefreshTokenChange("")
                        onCountryCodeChange("")
                        onUserIdChange(0L)
                        TidalStreamResolver.clearInMemoryAuth()
                        pendingCode = null
                        linkError = null
                    },
                )
            }
        } else if (pendingCode != null) {
            val code = pendingCode!!
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(
                        text = stringResource(R.string.tidal_code_instruction),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = code.userCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.tidal_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            val url =
                                code.verificationUriComplete
                                    .takeIf { it.startsWith("http") }
                                    ?: "https://${code.verificationUriComplete}"
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                            )
                        },
                    ) {
                        Text(stringResource(R.string.tidal_open_link))
                    }
                    TextButton(onClick = { pendingCode = null }) {
                        Text(stringResource(R.string.tidal_cancel_link))
                    }
                }
            }
        } else {
            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.tidal_link_account)) },
                    description =
                        if (requestingCode) {
                            stringResource(R.string.tidal_linking)
                        } else {
                            stringResource(R.string.tidal_link_description)
                        },
                    onClick = { if (!requestingCode) startLinking() },
                )
            }
        }
        if (linkError != null) {
            item {
                Text(
                    text = linkError!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}
