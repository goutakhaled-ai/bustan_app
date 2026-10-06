package com.personal.bustanalhuruf;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.util.Locale;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private static final int REQ_MIC = 77;
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";

    private WebView web;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private volatile int arabicStatus = -99;
    private PermissionRequest pendingRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (Build.VERSION.SDK_INT < 23 ||
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.getResources());
                    } else {
                        pendingRequest = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                    }
                });
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidTTS");

        tts = new TextToSpeech(this, this);

        web.loadUrl(START_URL);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int r = tts.setLanguage(new Locale("ar"));
            if (r < 0) {
                int r2 = tts.setLanguage(new Locale("ar", "SA"));
                if (r2 >= 0) r = r2;
            }
            arabicStatus = r;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { notifyDone(id); }
                @Override public void onError(String id) { notifyDone(id); }
                @Override public void onError(String id, int code) { notifyDone(id); }
                @Override public void onStop(String id, boolean interrupted) { notifyDone(id); }
            });
            ttsReady = true;
        } else {
            arabicStatus = -3;
            ttsReady = true;
        }
    }

    private void notifyDone(final String id) {
        if (id == null || web == null) return;
        final String safe = id.replaceAll("[^A-Za-z0-9_]", "");
        web.post(() -> web.evaluateJavascript("window.__ttsDone&&window.__ttsDone('" + safe + "')", null));
    }

    private class Bridge {
        @JavascriptInterface
        public boolean ready() { return ttsReady; }

        @JavascriptInterface
        public int arabic() { return arabicStatus; }

        @JavascriptInterface
        public String engine() {
            try { return tts != null && tts.getDefaultEngine() != null ? tts.getDefaultEngine() : ""; }
            catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public void speak(String text, float rate, String id) {
            if (tts == null || !ttsReady || arabicStatus == -3) { notifyDone(id); return; }
            try {
                tts.setSpeechRate(rate);
                tts.setPitch(1.05f);
                Bundle params = new Bundle();
                int res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id);
                if (res != TextToSpeech.SUCCESS) notifyDone(id);
            } catch (Exception e) {
                notifyDone(id);
            }
        }

        @JavascriptInterface
        public void stop() {
            try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void openInstall() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                    i.setPackage("com.google.android.tts");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    return;
                } catch (Exception ignored) { }
                try {
                    Intent i = new Intent("com.android.settings.TTS_SETTINGS");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    return;
                } catch (Exception ignored) { }
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=com.google.android.tts"));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception ignored) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && pendingRequest != null) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingRequest.grant(pendingRequest.getResources());
            } else {
                pendingRequest.deny();
            }
            pendingRequest = null;
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.appBack&&window.appBack())?'1':'0'", value -> {
            if (value == null || !value.contains("1")) {
                finish();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
