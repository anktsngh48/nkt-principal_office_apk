package com.deeksha.principalsoffice;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.splashscreen.SplashScreen;

import android.app.Activity;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Principal's Office: one full-screen WebView that opens a fixed page.
 * No address bar, no page addresses in pop-ups or error screens.
 */
public class MainActivity extends Activity {

    // >>> TYPE THE PAGE ADDRESS THE APP OPENS HERE (between the quotes) <<<
    private static final String START_URL = "https://anktsngh48.github.io/nkt-principal_office/";

    private static final String APP_NAME = "Principal's Office";
    private static final long SPLASH_MAX_MS = 8000;

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_PERM_CAMERA_FOR_CHOOSER = 2001;
    private static final int REQ_PERM_WEB = 2002;
    private static final int REQ_PERM_STORAGE = 2003;

    private WebView web;
    private View errorView;

    // Splash stays up until the first page has loaded (or the time cap passes).
    private volatile boolean ready = false;

    private String failedUrl = null;
    private boolean pageHadError = false;

    // File chooser state
    private ValueCallback<Uri[]> filePathCallback;
    private WebChromeClient.FileChooserParams pendingChooserParams;
    private File cameraFile;
    private Uri cameraUri;

    // Camera / microphone requests from pages (live classes etc.)
    private PermissionRequest pendingWebRequest;

    // Download waiting for the storage permission (Android 8 and 9 only)
    private String[] pendingDownload; // url, userAgent, contentDisposition, mimeType

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        splash.setKeepOnScreenCondition(() -> !ready);
        new Handler(Looper.getMainLooper()).postDelayed(() -> ready = true, SPLASH_MAX_MS);

        setContentView(R.layout.activity_main);
        web = findViewById(R.id.web);
        errorView = findViewById(R.id.error);
        findViewById(R.id.retry).setOnClickListener(v -> retry());

        setUpWebView();

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        }
        if (web.getUrl() == null) {
            web.loadUrl(START_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onPause() {
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelFileChooser();
        if (web != null) {
            web.stopLoading();
            ((FrameLayout) web.getParent()).removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (errorView.getVisibility() == View.VISIBLE) {
            finish();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ WebView

    private void setUpWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSupportMultipleWindows(false);          // links that open a new window load here instead
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(getResources().getColor(R.color.bg));
        web.addJavascriptInterface(new Bridge(), "PrincipalsOffice");

        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener(this::startDownload);
    }

    private void retry() {
        errorView.setVisibility(View.GONE);
        pageHadError = false;
        String url = failedUrl != null ? failedUrl : START_URL;
        failedUrl = null;
        web.loadUrl(url);
    }

    private void showError(String url) {
        failedUrl = url;
        pageHadError = true;
        errorView.setVisibility(View.VISIBLE);
        ready = true;
    }

    private class Client extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageHadError = false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            ready = true;
            if (!pageHadError) errorView.setVisibility(View.GONE);
            CookieManager.getInstance().flush();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) showError(request.getUrl().toString());
        }

        @Override
        public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
            handler.cancel();                         // never continue past a bad certificate
            showError(error.getUrl());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl());
        }
    }

    /** Returns true when the app handled the link itself. */
    private boolean handleUrl(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "http":
            case "https":
            case "about":
            case "blob":
            case "data":
            case "javascript":
                return false;                          // stay in the app; downloads are caught by the DownloadListener
            case "intent":
                try {
                    Intent in = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                    in.addCategory(Intent.CATEGORY_BROWSABLE);
                    in.setComponent(null);
                    in.setSelector(null);
                    startActivity(in);
                } catch (URISyntaxException | ActivityNotFoundException | SecurityException e) {
                    toast("No app found to open this link.");
                }
                return true;
            default:                                   // tel:, mailto:, sms:, geo:, upi:, whatsapp: ...
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException | SecurityException e) {
                    toast("No app found to open this link.");
                }
                return true;
        }
    }

    // ------------------------------------------------------------------ Chrome client (dialogs, uploads, permissions)

    private class Chrome extends WebChromeClient {

        // --- JavaScript dialogs: shown at the top, titled with the app name, never with the page address
        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            AlertDialog d = baseDialog().setMessage(message)
                    .setPositiveButton(R.string.ok, (x, w) -> result.confirm())
                    .setOnCancelListener(x -> result.cancel())
                    .create();
            showAtTop(d);
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            AlertDialog d = baseDialog().setMessage(message)
                    .setPositiveButton(R.string.ok, (x, w) -> result.confirm())
                    .setNegativeButton(R.string.cancel, (x, w) -> result.cancel())
                    .setOnCancelListener(x -> result.cancel())
                    .create();
            showAtTop(d);
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
            EditText input = new EditText(MainActivity.this);
            input.setText(defaultValue);
            input.setSingleLine(true);
            AlertDialog d = baseDialog().setMessage(message).setView(input)
                    .setPositiveButton(R.string.ok, (x, w) -> result.confirm(input.getText().toString()))
                    .setNegativeButton(R.string.cancel, (x, w) -> result.cancel())
                    .setOnCancelListener(x -> result.cancel())
                    .create();
            showAtTop(d);
            return true;
        }

        @Override
        public boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result) {
            result.confirm();                          // never show the "leave this page?" prompt with an address
            return true;
        }

        // --- File uploads (gallery, files, camera)
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            cancelFileChooser();
            filePathCallback = callback;
            pendingChooserParams = params;
            if (wantsCamera(params) && !has(Manifest.permission.CAMERA) && Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM_CAMERA_FOR_CHOOSER);
            } else {
                launchFileChooser();
            }
            return true;
        }

        // --- Camera and microphone for live classes, on any site
        @Override
        public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> handleWebPermission(request));
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (pendingWebRequest == request) pendingWebRequest = null;
        }
    }

    private AlertDialog.Builder baseDialog() {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int theme = night ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        return new AlertDialog.Builder(this, theme).setTitle(APP_NAME);
    }

    private void showAtTop(AlertDialog d) {
        Window w = d.getWindow();
        if (w != null) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = (int) (48 * getResources().getDisplayMetrics().density);
            w.setAttributes(lp);
        }
        if (!isFinishing()) d.show();
    }

    // ------------------------------------------------------------------ uploads

    private boolean wantsCamera(WebChromeClient.FileChooserParams p) {
        return p != null && acceptsImages(p);
    }

    private boolean acceptsImages(WebChromeClient.FileChooserParams p) {
        String[] types = p.getAcceptTypes();
        if (types == null || types.length == 0) return true;
        for (String t : types) {
            if (t == null || t.isEmpty() || t.startsWith("image/") || t.equals("*/*") || t.startsWith(".jpg")
                    || t.startsWith(".jpeg") || t.startsWith(".png")) return true;
        }
        return false;
    }

    private void launchFileChooser() {
        WebChromeClient.FileChooserParams p = pendingChooserParams;
        pendingChooserParams = null;
        if (filePathCallback == null || p == null) return;

        Intent camera = null;
        if (wantsCamera(p) && (Build.VERSION.SDK_INT < 23 || has(Manifest.permission.CAMERA))) {
            camera = buildCameraIntent();
        }

        try {
            if (p.isCaptureEnabled() && camera != null) {
                startActivityForResult(camera, REQ_FILE_CHOOSER);        // "take a photo" input
                return;
            }
            Intent content = p.createIntent();
            Intent chooser = new Intent(Intent.ACTION_CHOOSER);
            chooser.putExtra(Intent.EXTRA_INTENT, content);
            chooser.putExtra(Intent.EXTRA_TITLE, p.getTitle() != null && p.getTitle().length() > 0 ? p.getTitle() : "Choose a file");
            if (camera != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
            startActivityForResult(chooser, REQ_FILE_CHOOSER);
        } catch (ActivityNotFoundException | SecurityException e) {
            cancelFileChooser();
            toast("Can't open the file picker.");
        }
    }

    private Intent buildCameraIntent() {
        try {
            File dir = new File(getCacheDir(), "captures");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
            cameraFile = new File(dir, "photo_" + System.currentTimeMillis() + ".jpg");
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", cameraFile);
            Intent in = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            in.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            in.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return in;
        } catch (RuntimeException e) {
            cameraFile = null;
            cameraUri = null;
            return null;
        }
    }

    private void cancelFileChooser() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        pendingChooserParams = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE_CHOOSER) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (filePathCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK) {
            boolean nothingPicked = data == null || (data.getData() == null && data.getClipData() == null);
            if (nothingPicked) {
                if (cameraFile != null && cameraFile.length() > 0 && cameraUri != null) {
                    results = new Uri[]{cameraUri};
                }
            } else {
                results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    // ------------------------------------------------------------------ camera / mic for pages

    private void handleWebPermission(PermissionRequest request) {
        List<String> needed = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && !has(Manifest.permission.CAMERA)) {
                needed.add(Manifest.permission.CAMERA);
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && !has(Manifest.permission.RECORD_AUDIO)) {
                needed.add(Manifest.permission.RECORD_AUDIO);
            }
        }
        if (needed.isEmpty()) {
            grantWebPermission(request);
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (pendingWebRequest != null) pendingWebRequest.deny();
            pendingWebRequest = request;
            requestPermissions(needed.toArray(new String[0]), REQ_PERM_WEB);
        } else {
            grantWebPermission(request);
        }
    }

    /** Grants only camera and microphone, only when Android has allowed them. */
    private void grantWebPermission(PermissionRequest request) {
        List<String> ok = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && has(Manifest.permission.CAMERA)) ok.add(r);
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && has(Manifest.permission.RECORD_AUDIO)) ok.add(r);
        }
        if (ok.isEmpty()) request.deny();
        else request.grant(ok.toArray(new String[0]));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case REQ_PERM_CAMERA_FOR_CHOOSER:
                launchFileChooser();                    // with or without the camera option
                break;
            case REQ_PERM_WEB:
                if (pendingWebRequest != null) {
                    grantWebPermission(pendingWebRequest);
                    pendingWebRequest = null;
                }
                break;
            case REQ_PERM_STORAGE:
                String[] d = pendingDownload;
                pendingDownload = null;
                if (d != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startDownload(d[0], d[1], d[2], d[3], 0);
                } else if (d != null) {
                    toast("Allow storage access to download files.");
                }
                break;
            default:
                break;
        }
    }

    private boolean has(String permission) {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------ downloads (any site)

    private void startDownload(String url, String userAgent, String contentDisposition, String mimeType, long length) {
        if (url == null) return;
        String lower = url.toLowerCase(Locale.ROOT);
        boolean isFile = lower.startsWith("blob:") || lower.startsWith("data:")
                || lower.startsWith("http://") || lower.startsWith("https://");
        if (isFile && Build.VERSION.SDK_INT < 29 && !has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            pendingDownload = new String[]{url, userAgent, contentDisposition, mimeType};
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM_STORAGE);
            return;
        }
        try {
            if (lower.startsWith("blob:")) {
                downloadBlob(url, mimeType);
            } else if (lower.startsWith("data:")) {
                saveDataUrl(url);
            } else if (lower.startsWith("http://") || lower.startsWith("https://")) {
                enqueueDownload(url, userAgent, contentDisposition, mimeType);
            } else {
                handleUrl(Uri.parse(url));
            }
        } catch (RuntimeException e) {
            toast("Download failed.");
        }
    }

    private void enqueueDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
        if (mimeType != null && !mimeType.isEmpty()) r.setMimeType(mimeType);
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) r.addRequestHeader("Cookie", cookies);
        if (userAgent != null) r.addRequestHeader("User-Agent", userAgent);
        String referer = web.getUrl();
        if (referer != null && referer.startsWith("http")) r.addRequestHeader("Referer", referer);
        r.setTitle(name);
        r.setDescription(APP_NAME);
        r.allowScanningByMediaScanner();
        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            toast("Download failed.");
            return;
        }
        dm.enqueue(r);
        toast("Downloading " + name);
    }

    /** blob: links only exist inside the page, so the page itself reads the file and hands it over. */
    private void downloadBlob(String blobUrl, String mimeType) {
        String js = "(function(){try{var x=new XMLHttpRequest();x.open('GET'," + jsString(blobUrl) + ",true);"
                + "x.responseType='blob';x.onload=function(){var r=new FileReader();r.onloadend=function(){"
                + "var s=String(r.result||'');PrincipalsOffice.saveBase64(s.substring(s.indexOf(',')+1),"
                + jsString(mimeType == null ? "" : mimeType) + ");};r.readAsDataURL(x.response);};"
                + "x.onerror=function(){PrincipalsOffice.saveFailed();};x.send();}catch(e){PrincipalsOffice.saveFailed();}})();";
        web.evaluateJavascript(js, null);
    }

    private static String jsString(String s) {
        StringBuilder b = new StringBuilder("'");
        for (char c : s.toCharArray()) {
            if (c == '\\' || c == '\'') b.append('\\').append(c);
            else if (c == '\n' || c == '\r') b.append(' ');
            else b.append(c);
        }
        return b.append('\'').toString();
    }

    private void saveDataUrl(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) return;
        String head = dataUrl.substring(5, comma);              // after "data:"
        String body = dataUrl.substring(comma + 1);
        String mime = head.split(";")[0];
        byte[] bytes = head.contains(";base64")
                ? Base64.decode(body, Base64.DEFAULT)
                : Uri.decode(body).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        saveBytes(bytes, mime);
    }

    private void saveBytes(byte[] bytes, String mime) {
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String name = "Download-" + stamp + (ext != null ? "." + ext : "");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("insert failed");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("no stream");
                    out.write(bytes);
                }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                try (OutputStream out = new java.io.FileOutputStream(new File(dir, name))) {
                    out.write(bytes);
                }
            }
            toast("Saved to Downloads: " + name);
        } catch (IOException | RuntimeException e) {
            toast("Download failed.");
        }
    }

    /** Called by the page for blob: downloads. Only ever writes into the Downloads folder. */
    private class Bridge {
        @JavascriptInterface
        public void saveBase64(String base64, String mime) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                runOnUiThread(() -> saveBytes(bytes, mime));
            } catch (IllegalArgumentException e) {
                runOnUiThread(() -> toast("Download failed."));
            }
        }

        @JavascriptInterface
        public void saveFailed() {
            runOnUiThread(() -> toast("Download failed."));
        }
    }

    // ------------------------------------------------------------------ misc

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
