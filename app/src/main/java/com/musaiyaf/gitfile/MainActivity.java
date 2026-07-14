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
import android.util.Base64;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.app.Activity;

import androidx.documentfile.provider.DocumentFile;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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

    /** Held between opening the system file picker and the result coming back. */
    private ValueCallback<Uri[]> pickerCallback;
    private static final int PICK_FILES  = 1;
    private static final int PICK_FOLDER = 2;

    /** The folder the user last chose, flattened. Read lazily, one file at a time. */
    private final List<DocumentFile> folderFiles = new ArrayList<>();
    private final List<String>       folderPaths = new ArrayList<>();

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
        // Off by default; the Desktop view setting turns it on, because a
        // desktop-width layout on a phone screen is unusable without pinch-zoom.
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        // Deliberately left off: setAllowFileAccessFromFileURLs and
        // setAllowUniversalAccessFromFileURLs. See the class comment.

        // Expose native storage to the page. This is only safe because the page is
        // our own bundled asset served from APP_ORIGIN and nothing else is ever
        // allowed to load in this WebView (see shouldOverrideUrlLoading below).
        bridge = new Native(this);
        web.addJavascriptInterface(bridge, "Native");

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

        if (requestCode == PICK_FOLDER) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                bridge.js("onFolderPicked", "[]");   // user backed out
                return;
            }
            final Uri tree = data.getData();

            // Walking a folder tree over the SAF is slow enough to jank the UI, and a
            // deep tree can take seconds. Do it off the main thread.
            new Thread(() -> {
                folderFiles.clear();
                folderPaths.clear();
                try {
                    DocumentFile root = DocumentFile.fromTreeUri(MainActivity.this, tree);
                    String base = (root != null && root.getName() != null) ? root.getName() : "";
                    walkFolder(root, base);

                    // Hand back names and sizes only. The bytes come later, per file.
                    JSONArray arr = new JSONArray();
                    for (int i = 0; i < folderPaths.size(); i++) {
                        JSONObject o = new JSONObject();
                        o.put("path",  folderPaths.get(i));
                        o.put("size",  folderFiles.get(i).length());
                        o.put("index", i);
                        arr.put(o);
                    }
                    bridge.js("onFolderPicked", arr.toString());
                } catch (Exception e) {
                    bridge.js("onFolderPicked", "[]");
                }
            }).start();
            return;
        }

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

        /** Open a link in the real browser rather than inside the app. */
        @JavascriptInterface
        public void openUrl(String url) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }

        /** Pinch-zoom, on only when the page is in desktop mode. */
        @JavascriptInterface
        public void setZoom(boolean on) {
            runOnUiThread(() -> {
                WebSettings st = web.getSettings();
                st.setSupportZoom(on);
                st.setBuiltInZoomControls(on);
                st.setDisplayZoomControls(false);   // the +/- overlay is hideous
                st.setUseWideViewPort(on);
                st.setLoadWithOverviewMode(on);
            });
        }

        @JavascriptInterface
        public void copy(String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("gitfile", text));
        }

        /**
         * Open Android's folder picker.
         *
         * A web <input webkitdirectory> does nothing useful here: Android's WebView has
         * no directory mode, so FileChooserParams.createIntent() quietly produces an
         * ordinary single-file intent — which is why "upload folder" was only ever
         * uploading one file. The Storage Access Framework is the only real folder
         * picker on Android, and it is native-only.
         */
        @JavascriptInterface
        public void pickFolder() {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER);
        }

        /**
         * Read one file from the chosen folder, base64-encoded.
         *
         * Deliberately lazy. Slurping a whole folder into one JSON string would mean
         * holding every file in memory at once, in both Java and JavaScript, and would
         * make the progress bar a lie — it would already be done by the time it showed.
         * The page asks for files one at a time, as it uploads them.
         */
        @JavascriptInterface
        public String readEntry(int index) {
            try {
                Uri uri = folderFiles.get(index).getUri();
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] chunk = new byte[16384];
                    for (int n; (n = in.read(chunk)) > 0; ) out.write(chunk, 0, n);
                    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                }
            } catch (Exception e) {
                return null;   // the page turns a null into a readable error
            }
        }

        /** Call a global JS function on the UI thread. */
        void js(String fn, String... args) {
            StringBuilder call = new StringBuilder(fn).append("(");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) call.append(",");
                call.append(JSONObject.quote(args[i]));
            }
            call.append(")");
            runOnUiThread(() -> web.evaluateJavascript(call.toString(), null));
        }
    }

    private Native bridge;

    /** Walk a picked folder depth-first, flattening it into paths the repo can use. */
    private void walkFolder(DocumentFile dir, String prefix) {
        DocumentFile[] children = dir.listFiles();
        if (children == null) return;

        for (DocumentFile f : children) {
            String name = f.getName();
            if (name == null) continue;

            String path = prefix.isEmpty() ? name : prefix + "/" + name;

            if (f.isDirectory()) {
                if (name.equals(".git")) continue;   // never upload the repo's own guts
                walkFolder(f, path);
            } else {
                folderFiles.add(f);
                folderPaths.add(path);
            }
        }
    }

    /**
     * Back.
     *
     * web.canGoBack() is useless here. The app is one page that swaps views in
     * JavaScript, so the WebView's history is always empty — which meant every
     * back press fell straight through to super and killed the activity, even
     * when you were four folders deep.
     *
     * So ask the page. It answers true if it had somewhere to go (closed a sheet,
     * stepped up a directory, left the editor), and only when it answers false do
     * we actually leave.
     */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.appBack ? window.appBack() : false", value -> {
            if (!"true".equals(value)) super.onBackPressed();
        });
    }
}
