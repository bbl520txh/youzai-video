package com.youzai.video;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int PLAYER_REQUEST = 1002;
    private final ExecutorService httpPool = Executors.newCachedThreadPool();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            if (getActionBar() != null) getActionBar().hide();
        } catch (Exception ignored) {
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.addJavascriptInterface(new Bridge(), "NativeBridge");
        webView.setWebViewClient(new WebViewClient() {
            // Poster images go through the native HTTP stack (same path as the
            // API calls): some pic CDNs reject the WebView's own requests
            // (hotlink/referer rules), and this keeps one network behaviour.
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    if (request == null || !"GET".equalsIgnoreCase(request.getMethod())) return null;
                    String url = request.getUrl() == null ? "" : request.getUrl().toString();
                    String lower = url.toLowerCase();
                    int q = lower.indexOf('?');
                    String path = q >= 0 ? lower.substring(0, q) : lower;
                    if (!(path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png")
                            || path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".avif")
                            || path.endsWith(".bmp") || path.endsWith(".jfif"))) return null;
                    HttpURLConnection conn = null;
                    try {
                        URL u = new URL(url);
                        conn = (HttpURLConnection) u.openConnection();
                        conn.setConnectTimeout(12000);
                        conn.setReadTimeout(12000);
                        conn.setInstanceFollowRedirects(true);
                        conn.setRequestProperty("User-Agent",
                                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
                        conn.setRequestProperty("Referer", u.getProtocol() + "://" + u.getHost() + "/");
                        if (conn.getResponseCode() != 200) return null;
                        String mime = conn.getContentType();
                        if (mime == null || !mime.startsWith("image/")) {
                            if (path.endsWith(".png")) mime = "image/png";
                            else if (path.endsWith(".webp")) mime = "image/webp";
                            else if (path.endsWith(".gif")) mime = "image/gif";
                            else if (path.endsWith(".avif")) mime = "image/avif";
                            else mime = "image/jpeg";
                        } else {
                            int semi = mime.indexOf(';');
                            if (semi >= 0) mime = mime.substring(0, semi).trim();
                        }
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        InputStream is = conn.getInputStream();
                        byte[] chunk = new byte[16384];
                        int n;
                        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
                        is.close();
                        java.util.Map<String, String> headers = new java.util.HashMap<>();
                        headers.put("Access-Control-Allow-Origin", "*");
                        headers.put("Cache-Control", "public, max-age=86400");
                        WebResourceResponse resp = new WebResourceResponse(
                                mime, null, new java.io.ByteArrayInputStream(buf.toByteArray()));
                        resp.setStatusCodeAndReasonPhrase(200, "OK");
                        resp.setResponseHeaders(headers);
                        return resp;
                    } finally {
                        if (conn != null) conn.disconnect();
                    }
                } catch (Exception e) {
                    return null;
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                filePathCallback = callback;
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
        } else if (requestCode == PLAYER_REQUEST) {
            if (data != null && webView != null) {
                String payload = data.getStringExtra("result");
                if (payload != null) {
                    webView.evaluateJavascript(
                            "window.__onPlayerExit && window.__onPlayerExit(" + payload + ")", null);
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript(
                    "window.__onNativeBack && window.__onNativeBack()", null);
            return; // the page decides: close a layer or exit
        }
        super.onBackPressed();
    }

    public void exitApp() {
        moveTaskToBack(true);
    }

    // ------------------------------------------------------------------
    // JS bridge: unrestricted HTTP (no CORS) + native player launch.
    // ------------------------------------------------------------------
    public class Bridge {
        private String builtinCache = null;

        @JavascriptInterface
        public String builtinConfig() {
            if (builtinCache != null) return builtinCache;
            try {
                InputStream is = getAssets().open("default_config.json");
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
                is.close();
                builtinCache = buf.toString("UTF-8");
            } catch (Exception e) {
                builtinCache = "";
            }
            return builtinCache;
        }

        @JavascriptInterface
        public void request(final String id, final String optionsJson) {
            httpPool.execute(() -> {
                JSONObject out = new JSONObject();
                int status = 0;
                String bodyText = "";
                String error = "";
                HttpURLConnection conn = null;
                try {
                    JSONObject opt = new JSONObject(optionsJson);
                    String urlStr = opt.getString("url");
                    String method = opt.optString("method", "GET").toUpperCase();
                    int timeout = opt.optInt("timeout", 20000);
                    URL url = new URL(urlStr);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod(method);
                    conn.setConnectTimeout(timeout);
                    conn.setReadTimeout(timeout);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
                    JSONObject headers = opt.optJSONObject("headers");
                    if (headers != null) {
                        Iterator<String> keys = headers.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            conn.setRequestProperty(k, headers.optString(k, ""));
                        }
                    }
                    String body = opt.optString("body", "");
                    if (!body.isEmpty() && (method.equals("POST") || method.equals("PUT"))) {
                        conn.setDoOutput(true);
                        byte[] bytes = body.getBytes(Charset.forName("UTF-8"));
                        conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                        OutputStream os = conn.getOutputStream();
                        os.write(bytes);
                        os.close();
                    }
                    status = conn.getResponseCode();
                    InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    if (is != null) {
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        byte[] chunk = new byte[16384];
                        int n;
                        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
                        is.close();
                        String charset = "UTF-8";
                        String ctype = conn.getContentType();
                        if (ctype != null) {
                            for (String part : ctype.split(";")) {
                                part = part.trim();
                                if (part.toLowerCase().startsWith("charset=")) {
                                    charset = part.substring(8).trim();
                                }
                            }
                        }
                        try {
                            bodyText = new String(buf.toByteArray(), Charset.forName(charset));
                        } catch (Exception e) {
                            bodyText = new String(buf.toByteArray(), Charset.forName("UTF-8"));
                        }
                    }
                } catch (Exception e) {
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                } finally {
                    if (conn != null) conn.disconnect();
                }
                try {
                    out.put("status", status);
                    out.put("body", bodyText);
                    out.put("error", error);
                } catch (Exception ignored) {
                }
                final String payload = out.toString();
                runOnUiThread(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript(
                                "window.__onNativeResponse && window.__onNativeResponse("
                                        + JSONObject.quote(id) + "," + payload + ")", null);
                    }
                });
            });
        }

        // payloadJson: {title, lineName, names:[], urls:[], index, positions:{idx:ms}}
        @JavascriptInterface
        public void openPlayer(String payloadJson) {
            try {
                JSONObject p = new JSONObject(payloadJson);
                Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                i.putExtra("payload", payloadJson);
                startActivityForResult(i, PLAYER_REQUEST);
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void hideKeyboard() {
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(() -> MainActivity.this.exitApp());
        }
    }
}
