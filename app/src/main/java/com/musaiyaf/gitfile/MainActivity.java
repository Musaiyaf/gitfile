package com.musaiyaf.gitfile;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.app.Activity;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The whole app is one HTML file in assets/. This class exists only to put a
 * WebView on screen and point it at that file.
 *
 * The one non-obvious decision: the page is NOT loaded from file:///android_asset.
 *
 * A file:// page has a null origin, and a null origin cannot make cross-origin
 * fetch() calls — which would kill every request to api.github.com. The usual
 * workaround is setAllowUniversalAccessFromFileURLs(true), which switches CORS
 * off entirely for the page. That works, but it means any bug that ever lets
 * foreign HTML into this WebView gets to read anything on the internet with the
 * app's privileges.
 *
 * WebViewAssetLoader avoids the whole problem. It serves the same assets over
 * https://appassets.androidplatform.net/ — a real, secure origin that never
 * leaves the device. Normal CORS then applies, GitHub's API allows it, and
 * localStorage (where the token lives) persists properly, which it does not do
 * reliably on file:// origins.
 */
public class MainActivity extends Activity {

    private static final String APP_ORIGIN = "https://appassets.androidplatform.net";

    /**
     * The GitHub App's client ID. NOT a secret — it is meant to be embedded in a
     * distributed app, which is the whole reason the device flow exists. The client
     * SECRET must never appear here; the device flow does not need one, and anyone
     * can unzip an APK.
     *
     * Create a GitHub App (not an OAuth App) at
     * Settings -> Developer settings -> GitHub Apps, and tick "Enable Device Flow".
     *
     * A GitHub App is the right choice over an OAuth App because it gives the user
     * the "All repositories / Only select repositories" picker at install time, and
     * its permissions are the fine-grained ones (Contents, Pull requests,
     * Administration) rather than the all-or-nothing `repo` scope.
     */
    private static final String CLIENT_ID = "REPLACE_WITH_YOUR_CLIENT_ID";

    private WebView web;

    /** Held between opening the system file picker and the result coming back. */
    private ValueCallback<Uri[]> pickerCallback;
    private static final int PICK_FILES = 1;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Serve /assets/* from the APK's assets folder under APP_ORIGIN.
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);   // no white flash before first paint

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage — the token lives here
        s.setDatabaseEnabled(true);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        // Deliberately left off: setAllowFileAccessFromFileURLs and
        // setAllowUniversalAccessFromFileURLs. See the class comment.

        // Expose native storage to the page. This is only safe because the page is
        // our own bundled asset served from APP_ORIGIN and nothing else is ever
        // allowed to load in this WebView (see shouldOverrideUrlLoading below).
        web.addJavascriptInterface(new Native(this), "Native");

        web.setWebViewClient(new WebViewClient() {

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();

                // Anything that isn't our own bundled page — a link to GitHub's
                // web UI, say — belongs in the real browser, not in here.
                if (!APP_ORIGIN.equals(url.getScheme() + "://" + url.getHost())) {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                    return true;
                }
                return false;
            }
        });

        // Without a WebChromeClient, <input type="file"> is inert in a WebView —
        // tapping it does nothing at all, with no error. This hands the request
        // to Android's document picker and passes the chosen URIs back to the page.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pickerCallback != null) pickerCallback.onReceiveValue(null);
                pickerCallback = callback;
                try {
                    // createIntent() honours the input's multiple and accept
                    // attributes, so one path covers both single and multi-select.
                    startActivityForResult(params.createIntent(), PICK_FILES);
                    return true;
                } catch (Exception e) {
                    pickerCallback = null;
                    return false;
                }
            }
        });

        setContentView(web);
        web.loadUrl(APP_ORIGIN + "/assets/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_FILES || pickerCallback == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        // parseResult copes with both a single URI and a multi-select ClipData,
        // and returns null when the user backs out — which the page must be told,
        // or the file input stays wedged and will never open again.
        pickerCallback.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(resultCode, data));
        pickerCallback = null;
    }

    /**
     * A key/value store the page can reach from JavaScript as window.Native.
     *
     * Why not just use localStorage? Because a WebView flushes it to disk lazily.
     * Write the token, background the app, let Android reclaim the process to free
     * memory, and the write can be lost — you come back and the app has forgotten
     * you. SharedPreferences.apply() commits on its own thread and survives that.
     *
     * The file lives in the app's private data directory, which no other app can
     * read, and is covered by the device's disk encryption.
     */
    public class Native {
        private final SharedPreferences prefs;
        private volatile boolean cancelled = false;

        Native(Context ctx) {
            this.prefs = ctx.getSharedPreferences("gitfile", MODE_PRIVATE);
        }

        @JavascriptInterface
        public String get(String key) {
            return prefs.getString(key, null);
        }

        @JavascriptInterface
        public void set(String key, String value) {
            prefs.edit().putString(key, value).apply();
        }

        @JavascriptInterface
        public void del(String key) {
            prefs.edit().remove(key).apply();
        }

        @JavascriptInterface
        public void openUrl(String url) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }

        @JavascriptInterface
        public void copy(String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("code", text));
        }

        @JavascriptInterface
        public void cancelDeviceLogin() {
            cancelled = true;
        }

        /**
         * The OAuth device flow, start to finish.
         *
         * It lives here rather than in JavaScript for one reason: github.com's OAuth
         * endpoints send no Access-Control-Allow-Origin header, so a fetch() from the
         * page is refused by the browser before it ever reaches the network. Java has
         * no such rule.
         *
         * Ask GitHub for a code, hand it to the page to display, then poll until the
         * user approves it in their browser.
         */
        @JavascriptInterface
        public void startDeviceLogin() {
            cancelled = false;
            new Thread(() -> {
                try {
                    // No scope parameter: a GitHub App's permissions are declared on
                    // the app itself, and the repositories it can see are chosen by the
                    // user when they install it.
                    JSONObject start = postForm(
                            "https://github.com/login/device/code",
                            "client_id=" + CLIENT_ID);

                    if (start.has("error")) {
                        // Almost always: Device Flow isn't enabled on the OAuth App.
                        js("onDeviceError", start.optString("error_description", start.getString("error")));
                        return;
                    }

                    String deviceCode = start.getString("device_code");
                    String userCode   = start.getString("user_code");
                    String verifyUrl  = start.getString("verification_uri");
                    int    interval   = start.optInt("interval", 5);
                    long   deadline   = System.currentTimeMillis() + start.optInt("expires_in", 900) * 1000L;

                    js("onDeviceCode", userCode, verifyUrl);

                    while (System.currentTimeMillis() < deadline) {
                        Thread.sleep(interval * 1000L);
                        if (cancelled) return;

                        JSONObject poll = postForm(
                                "https://github.com/login/oauth/access_token",
                                "client_id=" + CLIENT_ID
                                        + "&device_code=" + deviceCode
                                        + "&grant_type=urn:ietf:params:oauth:grant-type:device_code");

                        if (poll.has("access_token")) {
                            // A GitHub App's user token expires after 8 hours unless the
                            // app has token expiry switched off. The refresh token, good
                            // for 6 months, is how we avoid making the user sign in again.
                            js("onDeviceToken",
                               poll.getString("access_token"),
                               poll.optString("refresh_token", ""));
                            return;
                        }

                        String err = poll.optString("error", "");
                        switch (err) {
                            case "authorization_pending":
                                break;                  // the user hasn't finished yet
                            case "slow_down":
                                interval += 5;          // GitHub says we're polling too fast
                                break;
                            case "expired_token":
                                js("onDeviceError", "That code expired. Start again.");
                                return;
                            case "access_denied":
                                js("onDeviceError", "Approval was declined.");
                                return;
                            default:
                                js("onDeviceError", poll.optString("error_description", "Sign-in failed."));
                                return;
                        }
                    }
                    js("onDeviceError", "That code expired. Start again.");

                } catch (Exception e) {
                    js("onDeviceError", "Could not reach GitHub: " + e.getMessage());
                }
            }).start();
        }

        /**
         * Trade an expiring refresh token for a fresh access token. Called when the
         * saved token comes back 401 on startup, so the user never sees a sign-in
         * screen they didn't ask for.
         */
        @JavascriptInterface
        public void refresh(String refreshToken) {
            new Thread(() -> {
                try {
                    JSONObject r = postForm(
                            "https://github.com/login/oauth/access_token",
                            "client_id=" + CLIENT_ID
                                    + "&grant_type=refresh_token"
                                    + "&refresh_token=" + Uri.encode(refreshToken));

                    if (r.has("access_token")) {
                        js("onDeviceToken",
                           r.getString("access_token"),
                           r.optString("refresh_token", ""));
                    } else {
                        js("onRefreshFailed");   // refresh token dead: sign in properly
                    }
                } catch (Exception e) {
                    js("onRefreshFailed");
                }
            }).start();
        }

        /** POST a form body and read the JSON reply. */
        private JSONObject postForm(String url, String body) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);

            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            // GitHub returns its error payloads with a 4xx status, so read whichever
            // stream is actually there.
            java.io.InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) sb.append(line);
            }
            return new JSONObject(sb.toString());
        }

        /** Call a global JS function on the UI thread, with string arguments. */
        private void js(String fn, String... args) {
            StringBuilder call = new StringBuilder(fn).append("(");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) call.append(",");
                call.append(JSONObject.quote(args[i]));   // quotes and escapes properly
            }
            call.append(")");
            runOnUiThread(() -> web.evaluateJavascript(call.toString(), null));
        }
    }

    /** Back goes back through the app's own history before it leaves the app. */
    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
