/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.ui.screens.settings

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.yeshua.player.R
import dev.yeshua.player.constants.DeezerAccountNameKey
import dev.yeshua.player.constants.DeezerArlKey
import dev.yeshua.player.constants.DeezerEnabledKey
import dev.yeshua.player.ui.component.PreferenceEntry
import dev.yeshua.player.ui.component.PreferenceGroup
import dev.yeshua.player.ui.component.SwitchPreference
import dev.yeshua.player.utils.DeezerStreamResolver
import dev.yeshua.player.utils.rememberPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Self-contained Deezer account section for Stream Sources settings.
 *
 * Sign-in happens in a WebView on deezer.com itself — the user types
 * their credentials into Deezer's own page, never into this app. When
 * the login completes, the `arl` session cookie is read from the
 * WebView cookie store, validated against the gw-light API, and ONLY
 * the arl (+ the account display name) is persisted. The arl value is
 * never shown on screen or logged.
 *
 * Note: email + password login works in the WebView; Google's social
 * sign-in refuses embedded WebViews, so users who normally sign in with
 * Google should use (or set) a Deezer password instead.
 */
@Composable
fun DeezerAuthSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val (arl, onArlChange) = rememberPreference(DeezerArlKey, defaultValue = "")
    val (accountName, onAccountNameChange) = rememberPreference(DeezerAccountNameKey, defaultValue = "")
    val (enabled, onEnabledChange) = rememberPreference(DeezerEnabledKey, defaultValue = false)
    var showLogin by remember { mutableStateOf(false) }

    val signedIn = arl.isNotBlank()

    PreferenceGroup(title = stringResource(R.string.deezer_source)) {
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.deezer)) },
                description =
                    if (signedIn) {
                        accountName.ifBlank { stringResource(R.string.deezer_signed_in) }
                    } else {
                        stringResource(R.string.deezer_sign_in_subtitle)
                    },
                checked = enabled && signedIn,
                onCheckedChange = onEnabledChange,
                isEnabled = signedIn,
            )
        }
        if (signedIn) {
            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.deezer_sign_out)) },
                    onClick = {
                        onArlChange("")
                        onAccountNameChange("")
                        onEnabledChange(false)
                    },
                )
            }
        } else {
            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.deezer_sign_in)) },
                    description = stringResource(R.string.deezer_sign_in_subtitle),
                    onClick = { showLogin = true },
                )
            }
        }
    }

    if (showLogin) {
        DeezerLoginDialog(
            onDismiss = { showLogin = false },
            onArlCaptured = { candidate ->
                showLogin = false
                Toast
                    .makeText(context, R.string.deezer_checking_account, Toast.LENGTH_SHORT)
                    .show()
                scope.launch {
                    DeezerStreamResolver
                        .validateArl(candidate)
                        .onSuccess { account ->
                            onArlChange(candidate)
                            onAccountNameChange(account.displayName.orEmpty())
                            onEnabledChange(true)
                            Toast
                                .makeText(
                                    context,
                                    context.getString(
                                        R.string.deezer_signed_in_as,
                                        account.displayName ?: account.userId,
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                        }.onFailure {
                            Toast
                                .makeText(context, R.string.deezer_sign_in_failed, Toast.LENGTH_LONG)
                                .show()
                        }
                }
            },
        )
    }
}

/**
 * Full-screen WebView on Deezer's own login page. Polls the cookie
 * store while open and reports the arl as soon as the user is signed
 * in; the "Use this account" button captures it manually.
 */
@Composable
private fun DeezerLoginDialog(
    onDismiss: () -> Unit,
    onArlCaptured: (String) -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var captured by remember { mutableStateOf(false) }

    fun capture() {
        if (captured) return
        val arl = readDeezerArl() ?: return
        captured = true
        onArlCaptured(arl)
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            capture()
        }
    }

    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { androidContext ->
                        WebView(androidContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)
                            webViewClient = WebViewClient()
                            loadUrl("https://www.deezer.com/login")
                            webView = this
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton(onClick = { capture() }) {
                        Text(stringResource(R.string.deezer_use_this_account))
                    }
                }
            }
        }
    }
}

/** Extracts the `arl` value from the deezer.com cookies, or null when not signed in. */
private fun readDeezerArl(): String? {
    val cookies = CookieManager.getInstance().getCookie("https://www.deezer.com") ?: return null
    return cookies
        .split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("arl=") }
        ?.removePrefix("arl=")
        ?.takeIf { it.isNotBlank() }
}
