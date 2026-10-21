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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.yeshua.player.R
import dev.yeshua.player.utils.QobuzStreamResolver
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

/**
 * What the Qobuz web sign-in captured from the player's own session
 * state. Only [userAuthToken] is guaranteed; the profile fields are
 * best-effort extras the stored state may or may not expose, and are
 * null when it doesn't. The token is a credential: it is never shown
 * on screen and never logged.
 */
data class QobuzCapturedAccount(
    val userAuthToken: String,
    val email: String?,
    val displayName: String?,
    val subscriptionLabel: String?,
)

private const val QOBUZ_LOGIN_URL = "https://play.qobuz.com/login"

/**
 * Desktop Chrome user agent for the login WebView. Google's sign-in
 * refuses embedded WebViews that identify as WebViews
 * (`disallowed_useragent`), which is exactly the sign-in path this
 * flow exists for — presenting a full desktop Chrome UA is the
 * standard workaround and lets Qobuz's own page (including its
 * "Continue with Google" button) work as it does in a browser.
 */
private const val QOBUZ_DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

/**
 * JS run in the Qobuz player page: scans localStorage and
 * sessionStorage for the persisted `user_auth_token` (the web player
 * keeps its session in redux-persist state after any sign-in, email
 * or Google), falling back to document.cookie. Returns a small JSON
 * string with the token plus whatever profile fields the same blob
 * exposes, or an empty string when the user isn't signed in yet.
 * Persisted values are often JSON strings nested inside JSON, so the
 * text is unescaped once before the field regexes run.
 */
private const val QOBUZ_EXTRACT_SCRIPT = """
(function () {
  function extractFromText(text) {
    if (!text || text.indexOf('user_auth_token') === -1) return null;
    var norm = text.replace(/\\(.)/g, function (m, c) { return c; });
    function grab(re) {
      var m = norm.match(re);
      return m ? m[1] : null;
    }
    var token = grab(/"user_auth_token"\s*:\s*"([^"]+)"/);
    if (!token) return null;
    var out = { token: token };
    var email = grab(/"email"\s*:\s*"([^"]+)"/);
    if (email) out.email = email;
    var first = grab(/"firstname"\s*:\s*"([^"]*)"/);
    var last = grab(/"lastname"\s*:\s*"([^"]*)"/);
    var display = ((first || '') + ' ' + (last || '')).trim();
    if (!display) display = grab(/"display_name"\s*:\s*"([^"]+)"/) || '';
    if (display) out.displayName = display;
    var label = grab(/"short_label"\s*:\s*"([^"]+)"/);
    if (label) out.subscriptionLabel = label;
    return out;
  }
  function scanStore(store) {
    try {
      for (var i = 0; i < store.length; i++) {
        var found = extractFromText(store.getItem(store.key(i)));
        if (found) return found;
      }
    } catch (e) {}
    return null;
  }
  var account = scanStore(window.localStorage) || scanStore(window.sessionStorage);
  if (!account && document.cookie && document.cookie.indexOf('user_auth_token') !== -1) {
    var m = document.cookie.match(/user_auth_token=([^;]+)/);
    if (m) {
      var cookieToken = m[1];
      try { cookieToken = decodeURIComponent(cookieToken); } catch (e) {}
      if (cookieToken) account = { token: cookieToken };
    }
  }
  return account ? JSON.stringify(account) : '';
})()
"""

/**
 * Full-screen WebView on Qobuz's own login page (play.qobuz.com) —
 * the user signs in there, with email or Google, never into this app.
 * While the dialog is open it polls the page's storage for the
 * session token; the "Use this account" button captures it manually.
 * A captured token is validated against the Qobuz API before it is
 * handed to the caller; a failed validation shows an error and keeps
 * the dialog open.
 */
@Composable
fun QobuzWebLoginDialog(
    onDismiss: () -> Unit,
    onTokenCaptured: (QobuzCapturedAccount) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val signInFailedText = stringResource(R.string.qobuz_sign_in_failed)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var validating by remember { mutableStateOf(false) }
    var handled by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun capture() {
        val view = webView ?: return
        if (validating || handled) return
        validating = true
        scope.launch {
            val account = runCatching { extractQobuzAccount(view) }.getOrNull()
            if (account == null) {
                // Not signed in yet — stay quiet and let polling retry.
                validating = false
                return@launch
            }
            if (QobuzStreamResolver.validateToken(account.userAuthToken)) {
                handled = true
                errorText = null
                onTokenCaptured(account)
            } else {
                errorText = signInFailedText
            }
            validating = false
        }
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
                            settings.userAgentString = QOBUZ_DESKTOP_USER_AGENT
                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)
                            webViewClient = WebViewClient()
                            loadUrl(QOBUZ_LOGIN_URL)
                            webView = this
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                )
                if (validating) {
                    Text(
                        text = stringResource(R.string.qobuz_checking_account),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                errorText?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton(
                        onClick = { capture() },
                        enabled = !validating,
                    ) {
                        Text(stringResource(R.string.qobuz_use_this_account))
                    }
                }
            }
        }
    }
}

/** Runs [QOBUZ_EXTRACT_SCRIPT] in the page and decodes what it returns. */
private suspend fun extractQobuzAccount(webView: WebView): QobuzCapturedAccount? =
    suspendCancellableCoroutine { continuation ->
        webView.evaluateJavascript(QOBUZ_EXTRACT_SCRIPT) { raw ->
            if (continuation.isActive) {
                continuation.resume(raw?.let { parseQobuzExtractResult(it) })
            }
        }
    }

private fun parseQobuzExtractResult(raw: String): QobuzCapturedAccount? {
    // evaluateJavascript hands the JS string back JSON-encoded (quoted
    // and escaped), so unwrap it before parsing the payload itself.
    val payload =
        runCatching { JSONObject("{\"v\":$raw}").optString("v") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
    val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
    val token = json.optString("token").takeIf { it.isNotBlank() } ?: return null
    return QobuzCapturedAccount(
        userAuthToken = token,
        email = json.optString("email").takeIf { it.isNotBlank() },
        displayName = json.optString("displayName").takeIf { it.isNotBlank() },
        subscriptionLabel = json.optString("subscriptionLabel").takeIf { it.isNotBlank() },
    )
}
