package com.musaiyaf.gitfile;

import android.annotation.SuppressLint;
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
import android.app.Activity;

import androidx.webkit.WebViewAssetLoader;

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

    private WebView web;

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

        setContentView(web);
        web.loadUrl(APP_ORIGIN + "/assets/index.html");
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
    public static class Native {
        private final SharedPreferences prefs;

        Native(android.content.Context ctx) {
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
