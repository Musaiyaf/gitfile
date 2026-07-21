package com.musaiyaf.gitfile;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
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

import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import androidx.webkit.WebViewAssetLoader;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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

    // ── USB / ESP flashing ────────────────────────────────────────────────────
    private static final String ACTION_USB_PERMISSION = "com.musaiyaf.gitfile.USB_PERMISSION";
    private UsbManager usbManager;
    private BroadcastReceiver usbReceiver;
    /** The flash currently running, so cancelFlash() can reach it. */
    private volatile EspLoader currentLoader;
    private volatile UsbSerialPort currentPort;

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

        // The system USB-permission dialog answers back through a broadcast. Relay
        // that yes/no to the page so the flash wizard can proceed or stop.
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (bridge != null) bridge.js("onUsbPermission", granted ? "true" : "false");
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    @Override
    protected void onDestroy() {
        if (usbReceiver != null) {
            try { unregisterReceiver(usbReceiver); } catch (Exception ignore) { }
        }
        super.onDestroy();
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

        /**
         * The version actually installed, read from the APK itself.
         *
         * Not a constant in the JavaScript: that would be a number someone has to
         * remember to bump, and therefore a number that is eventually a lie. The
         * build number comes from the Actions run that produced this APK.
         */
        @JavascriptInterface
        public String appVersion() {
            try {
                android.content.pm.PackageInfo p =
                        getPackageManager().getPackageInfo(getPackageName(), 0);
                long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? p.getLongVersionCode()
                        : p.versionCode;
                return p.versionName + " (build " + code + ")";
            } catch (Exception e) {
                return "unknown";
            }
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

        /**
         * Fetch an authenticated URL as text, natively.
         *
         * This exists for one reason: GitHub's job-log endpoint answers with a 302
         * to blob storage, and that storage sends no Access-Control-Allow-Origin
         * header. A fetch() from the page follows the redirect, hits the missing
         * header, and dies with "Failed to fetch" — the same wall the OAuth
         * endpoints put up. Java has no CORS, so the request simply works.
         *
         * Logs run to megabytes. Only the tail is worth reading, and only the tail
         * is sent across the bridge.
         */
        @JavascriptInterface
        public void fetchLog(String url, String token, String callback) {
            new Thread(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestProperty("Authorization", "Bearer " + token);
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    c.setInstanceFollowRedirects(true);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(30000);

                    InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
                    if (in == null) { js(callback, "", "Empty response."); return; }

                    // Keep a rolling window of the last 1200 lines. A whole build log
                    // can be tens of megabytes; nobody scrolls up that far, and the
                    // failure is always at the end.
                    java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
                    try (BufferedReader r = new BufferedReader(
                             new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        for (String line; (line = r.readLine()) != null; ) {
                            tail.addLast(line);
                            if (tail.size() > 1200) tail.removeFirst();
                        }
                    }
                    js(callback, String.join("\n", tail), "");

                } catch (Exception e) {
                    js(callback, "", "Could not fetch the log: " + e.getMessage());
                }
            }).start();
        }

        /**
         * Download an Actions artifact and, if it contains an APK, hand it to
         * Android's installer.
         *
         * Three things make this native rather than a fetch() from the page:
         *
         *   1. The download URL is authenticated, then 302s to blob storage, and
         *      blob storage sends no Access-Control-Allow-Origin header. A WebView
         *      cannot follow that redirect. This is the same wall the job logs and
         *      the OAuth endpoints put up.
         *   2. Every GitHub artifact is a ZIP, even when it holds one file. The APK
         *      has to be pulled out of it.
         *   3. Installing needs a content:// URI from a FileProvider. Handing the
         *      installer a file:// path throws on anything since Android 7.
         */
        @JavascriptInterface
        public void download(String url, String token, String accept, String name, boolean unzip) {
            new Thread(() -> {
                try {
                    File dir = new File(getCacheDir(), "dl");
                    deleteTree(dir);                 // last build's APK is dead weight
                    dir.mkdirs();

                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestProperty("Authorization", "Bearer " + token);
                    // Artifacts come back as JSON-negotiated zips; a release asset needs
                    // octet-stream, or GitHub hands you the asset's metadata instead of
                    // the asset.
                    c.setRequestProperty("Accept", accept);
                    c.setInstanceFollowRedirects(true);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(60000);

                    if (c.getResponseCode() >= 400) {
                        js("onDownloadError", "GitHub refused the download (" + c.getResponseCode()
                            + "). The token needs Actions: read.");
                        return;
                    }

                    long total = c.getContentLengthLong();
                    File dest = new File(dir, unzip ? "artifact.zip" : safe(name));

                    try (InputStream in = c.getInputStream();
                         FileOutputStream out = new FileOutputStream(dest)) {
                        byte[] buf = new byte[32768];
                        long got = 0;
                        int lastPct = -1;
                        for (int n; (n = in.read(buf)) > 0; ) {
                            out.write(buf, 0, n);
                            got += n;
                            if (total > 0) {
                                int pct = (int) (got * 100 / total);
                                // Only speak when the number actually changes; otherwise
                                // this floods the JS bridge with identical messages.
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    js("onDownloadProgress", String.valueOf(pct));
                                }
                            }
                        }
                    }

                    // A release asset is already the file. Nothing to unpack.
                    if (!unzip) {
                        if (dest.getName().toLowerCase().endsWith(".apk")) {
                            js("onDownloadDone", "apk", dest.getAbsolutePath(), dest.getName());
                        } else {
                            String saved = saveToDownloads(dest, dest.getName());
                            js("onDownloadDone", "file", "", saved);
                        }
                        return;
                    }

                    // Unpack. An APK inside is the thing we are really after.
                    File zip = dest;
                    File apk = null;
                    try (ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(zip))) {
                        for (ZipEntry e; (e = zis.getNextEntry()) != null; ) {
                            // getName() on the File strips any directory part, so a
                            // hostile entry called "../../evil" cannot escape our folder.
                            String entryName = new File(e.getName()).getName();
                            if (e.isDirectory() || entryName.isEmpty()) continue;

                            File unpacked = new File(dir, entryName);
                            try (FileOutputStream fo = new FileOutputStream(unpacked)) {
                                byte[] buf = new byte[32768];
                                for (int n; (n = zis.read(buf)) > 0; ) fo.write(buf, 0, n);
                            }
                            if (entryName.toLowerCase().endsWith(".apk")) apk = unpacked;
                        }
                    }

                    if (apk != null) {
                        js("onDownloadDone", "apk", apk.getAbsolutePath(), apk.getName());
                    } else {
                        String saved = saveToDownloads(zip, "artifact.zip");
                        js("onDownloadDone", "zip", "", saved);
                    }

                } catch (Exception e) {
                    js("onDownloadError", "Download failed: " + e.getMessage());
                }
            }).start();
        }

        /**
         * Attach a file to a release.
         *
         * Assets do not go to api.github.com — they go to uploads.github.com, whose
         * CORS behaviour is not something to bet on after everything else on this
         * host has refused a WebView. Native, then, like the rest.
         */
        @JavascriptInterface
        public void uploadAsset(String uploadUrl, String token, String name,
                                String contentType, String base64) {
            new Thread(() -> {
                try {
                    // The template arrives as ".../assets{?name,label}". Strip the
                    // placeholder and put the real name on.
                    String base = uploadUrl.replaceAll("\\{.*\\}$", "");
                    String url  = base + "?name=" + Uri.encode(name);

                    byte[] body = Base64.decode(base64, Base64.DEFAULT);

                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Authorization", "Bearer " + token);
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    c.setRequestProperty("Content-Type", contentType);
                    c.setFixedLengthStreamingMode(body.length);
                    c.setDoOutput(true);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(120000);

                    try (OutputStream out = c.getOutputStream()) { out.write(body); }

                    int code = c.getResponseCode();
                    if (code >= 400) {
                        InputStream err = c.getErrorStream();
                        StringBuilder sb = new StringBuilder();
                        if (err != null) {
                            try (BufferedReader r = new BufferedReader(
                                     new InputStreamReader(err, StandardCharsets.UTF_8))) {
                                for (String l; (l = r.readLine()) != null; ) sb.append(l);
                            }
                        }
                        js("onAssetUploaded", "", "Upload failed (" + code + "). " + sb);
                        return;
                    }
                    js("onAssetUploaded", name, "");

                } catch (Exception e) {
                    js("onAssetUploaded", "", "Upload failed: " + e.getMessage());
                }
            }).start();
        }

        /**
         * Copy a downloaded file out of our cache and into the user's Downloads.
         *
         * The cache is wiped on the next download, so "install now" and "keep a
         * copy" are genuinely different choices — which is why the app asks.
         */
        @JavascriptInterface
        public void saveFile(String path) {
            new Thread(() -> {
                try {
                    File f = new File(path);
                    String saved = saveToDownloads(f, f.getName());
                    js("onFileSaved", saved, "");
                } catch (Exception e) {
                    js("onFileSaved", "", "Could not save: " + e.getMessage());
                }
            }).start();
        }

        /** Ask Android to install an APK we just downloaded. */
        @JavascriptInterface
        public void install(String path) {
            try {
                File f = new File(path);
                Uri uri = FileProvider.getUriForFile(
                        MainActivity.this, getPackageName() + ".files", f);

                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                js("onDownloadError", "Could not open the installer: " + e.getMessage());
            }
        }

        // ══ ESP flashing ══════════════════════════════════════════════════════
        // The whole flasher is native for the same reason downloads are: a WebView
        // has no way to reach USB. EspLoader speaks esptool's serial protocol; these
        // methods are just the bridge — enumerate devices, get permission, pull the
        // .bin files out of the artifact, and run a flash on a background thread.

        /** USB devices that look like a serial adapter, as JSON for the picker. */
        @JavascriptInterface
        public String listSerialDevices() {
            JSONArray arr = new JSONArray();
            try {
                for (UsbDevice dev : usbManager.getDeviceList().values()) {
                    if (probeDriver(dev) == null) continue;
                    JSONObject o = new JSONObject();
                    o.put("id",  dev.getDeviceId());
                    o.put("vid", dev.getVendorId());
                    o.put("pid", dev.getProductId());
                    String name = null;
                    try { name = dev.getProductName(); } catch (Exception ignore) { }
                    if (name == null || name.isEmpty())
                        name = String.format("USB %04X:%04X", dev.getVendorId(), dev.getProductId());
                    o.put("name", name);
                    o.put("hasPermission", usbManager.hasPermission(dev));
                    arr.put(o);
                }
            } catch (Exception ignore) { /* return whatever we managed to list */ }
            return arr.toString();
        }

        /** Ask Android for permission to talk to a device; answer via onUsbPermission. */
        @JavascriptInterface
        public void requestUsbPermission(int deviceId) {
            UsbDevice dev = findDevice(deviceId);
            if (dev == null) { js("onUsbPermission", "false"); return; }
            if (usbManager.hasPermission(dev)) { js("onUsbPermission", "true"); return; }
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(MainActivity.this, 0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);
            usbManager.requestPermission(dev, pi);
        }

        /**
         * Download the build artifact and dig every .bin out of it (including out of
         * a nested zip), so the wizard can show them with editable offsets. The bytes
         * stay in the app's cache; flash() reads them straight from there.
         */
        @JavascriptInterface
        public void prepareFlash(String url, String token, String accept) {
            new Thread(() -> {
                try {
                    File dir = new File(getCacheDir(), "flash");
                    deleteTree(dir);
                    dir.mkdirs();

                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestProperty("Authorization", "Bearer " + token);
                    c.setRequestProperty("Accept", accept);
                    c.setInstanceFollowRedirects(true);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(60000);
                    if (c.getResponseCode() >= 400) {
                        js("onFlashError", "GitHub refused the download (" + c.getResponseCode()
                            + "). The token needs Actions: read.");
                        return;
                    }

                    long total = c.getContentLengthLong();
                    File zip = new File(dir, "artifact.zip");
                    try (InputStream in = c.getInputStream();
                         FileOutputStream out = new FileOutputStream(zip)) {
                        byte[] buf = new byte[32768];
                        long got = 0; int lastPct = -1;
                        for (int n; (n = in.read(buf)) > 0; ) {
                            out.write(buf, 0, n);
                            got += n;
                            if (total > 0) {
                                int pct = (int) (got * 100 / total);
                                if (pct != lastPct) { lastPct = pct; js("onFlashPrepProgress", String.valueOf(pct)); }
                            }
                        }
                    }

                    JSONArray files = new JSONArray();
                    collectBins(zip, dir, files, 0);
                    zip.delete();

                    if (files.length() == 0) {
                        js("onFlashError", "No .bin files were found in this artifact.");
                        return;
                    }
                    js("onFlashFilesReady", files.toString());
                } catch (Exception e) {
                    js("onFlashError", "Couldn't prepare the firmware: " + e.getMessage());
                }
            }).start();
        }

        /**
         * Run the flash. config: {deviceId, chip, baud, stub, compress,
         * files:[{name, path, offset}]}. Streams onFlashStage / onFlashProgress /
         * onFlashLog while it runs, then onFlashDone or onFlashError.
         */
        @JavascriptInterface
        public void flash(String configJson) {
            new Thread(() -> {
                UsbSerialPort port = null;
                try {
                    JSONObject cfg = new JSONObject(configJson);
                    int deviceId    = cfg.getInt("deviceId");
                    String chip     = cfg.optString("chip", "auto");
                    int baud        = cfg.optInt("baud", 115200);
                    boolean stub    = cfg.optBoolean("stub", true);
                    boolean compress= cfg.optBoolean("compress", true);
                    JSONArray fs    = cfg.getJSONArray("files");

                    UsbDevice dev = findDevice(deviceId);
                    if (dev == null) { js("onFlashError", "USB device not found. Reconnect it and try again."); return; }
                    if (!usbManager.hasPermission(dev)) { js("onFlashError", "USB permission was not granted."); return; }
                    UsbSerialDriver drv = probeDriver(dev);
                    if (drv == null) { js("onFlashError", "No serial driver matches this device."); return; }
                    UsbDeviceConnection conn = usbManager.openDevice(dev);
                    if (conn == null) { js("onFlashError", "Couldn't open the USB device."); return; }

                    port = drv.getPorts().get(0);
                    port.open(conn);
                    currentPort = port;

                    List<EspLoader.Segment> segs = new ArrayList<>();
                    for (int i = 0; i < fs.length(); i++) {
                        JSONObject f = fs.getJSONObject(i);
                        File file = new File(f.getString("path"));
                        int offset = (int) Long.parseLong(
                                f.getString("offset").trim().replaceFirst("(?i)^0x", ""), 16);
                        segs.add(new EspLoader.Segment(
                                f.optString("name", file.getName()), offset, readAll(file)));
                    }

                    EspLoader.Ui ui = new EspLoader.Ui() {
                        @Override public void stage(String s) { js("onFlashStage", s); }
                        @Override public void log(String l)   { js("onFlashLog", l); }
                        @Override public void progress(int idx, String name, long sent, long total, int overall) {
                            js("onFlashProgress", String.valueOf(idx), name,
                               String.valueOf(sent), String.valueOf(total), String.valueOf(overall));
                        }
                    };
                    EspLoader loader = new EspLoader(port, dev.getVendorId(), dev.getProductId(),
                            ui, key -> readStub(key));
                    currentLoader = loader;
                    loader.flash(chip, baud, stub, compress, segs);
                    js("onFlashDone", "Flashed successfully — the board is rebooting.");
                } catch (Exception e) {
                    String m = e.getMessage();
                    js("onFlashError", m == null ? e.toString() : m);
                } finally {
                    currentLoader = null;
                    if (port != null) { try { port.close(); } catch (Exception ignore) { } }
                    currentPort = null;
                }
            }).start();
        }

        /** Abort a running flash. */
        @JavascriptInterface
        public void cancelFlash() {
            EspLoader l = currentLoader;
            if (l != null) l.cancel = true;
            UsbSerialPort p = currentPort;
            if (p != null) { try { p.close(); } catch (Exception ignore) { } }
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

    /** Never let a remote name decide where on disk we write. */
    private static String safe(String name) {
        if (name == null || name.isEmpty()) return "download.bin";
        return new File(name).getName().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    // ── ESP flashing helpers ──────────────────────────────────────────────────

    private UsbDevice findDevice(int deviceId) {
        try {
            for (UsbDevice d : usbManager.getDeviceList().values())
                if (d.getDeviceId() == deviceId) return d;
        } catch (Exception ignore) { }
        return null;
    }

    /**
     * Find a serial driver for a device. The library's default table covers the
     * common USB-UART bridges (CP210x, CH34x, FTDI, Prolific). Boards with a native
     * USB port (ESP32-S3/C3/C6/…) show up as a plain CDC device the table doesn't
     * list, so fall back to CDC-ACM for Espressif's VID or anything exposing a CDC
     * comms interface.
     */
    private UsbSerialDriver probeDriver(UsbDevice dev) {
        UsbSerialDriver drv = UsbSerialProber.getDefaultProber().probeDevice(dev);
        if (drv != null) return drv;
        if (dev.getVendorId() == 0x303A || hasCdcInterface(dev)) return new CdcAcmSerialDriver(dev);
        return null;
    }

    private static boolean hasCdcInterface(UsbDevice dev) {
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            int cls = dev.getInterface(i).getInterfaceClass();
            if (cls == UsbConstants.USB_CLASS_COMM || cls == UsbConstants.USB_CLASS_CDC_DATA) return true;
        }
        return false;
    }

    /** Load a bundled stub loader (assets/stubs/<key>.json), or null if absent. */
    private JSONObject readStub(String key) {
        try (InputStream in = getAssets().open("stubs/" + key + ".json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return new JSONObject(out.toString("UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[32768];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /**
     * Extract a zip into {@code dir}, recording every .bin as {name, path, size}.
     * If an entry is itself a zip (some builds nest one), recurse one level so a
     * zipped set of bins still works. Entry names are flattened so a hostile
     * "../.." can't escape the cache dir — the same guard download() uses.
     */
    private void collectBins(File zip, File dir, JSONArray out, int depth) {
        if (depth > 2) return;
        try (ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(zip))) {
            for (ZipEntry e; (e = zis.getNextEntry()) != null; ) {
                String name = new File(e.getName()).getName();
                if (e.isDirectory() || name.isEmpty()) continue;
                File unpacked = new File(dir, name);
                try (FileOutputStream fo = new FileOutputStream(unpacked)) {
                    byte[] buf = new byte[32768];
                    for (int n; (n = zis.read(buf)) > 0; ) fo.write(buf, 0, n);
                }
                String lower = name.toLowerCase();
                if (lower.endsWith(".bin")) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("name", name);
                        o.put("path", unpacked.getAbsolutePath());
                        o.put("size", unpacked.length());
                        out.put(o);
                    } catch (Exception ignore) { }
                } else if (lower.endsWith(".zip")) {
                    collectBins(unpacked, dir, out, depth + 1);
                    unpacked.delete();
                }
            }
        } catch (Exception ignore) { /* a partial extract still yields usable bins */ }
    }

    /**
     * Put a file into the user's Downloads folder.
     *
     * Android 10 rewrote how this works: MediaStore, no permission needed. Below
     * that, it is a direct write to public storage and needs WRITE_EXTERNAL_STORAGE,
     * which the manifest asks for only up to API 28.
     */
    private String saveToDownloads(File src, String name) throws Exception {
        String mime = name.toLowerCase().endsWith(".apk")
                ? "application/vnd.android.package-archive"
                : "application/zip";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            try (OutputStream out = getContentResolver().openOutputStream(uri);
                 InputStream in = new java.io.FileInputStream(src)) {
                byte[] buf = new byte[32768];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            v.clear();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, v, null, null);
            return name;
        }

        File dst = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
        try (InputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[32768];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
        return dst.getName();
    }

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
