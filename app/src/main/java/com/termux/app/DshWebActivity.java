package com.termux.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.termux.R;

/**
 * Thin WebView client for the DSH server hosted by Termux.
 *
 * <p>Termux/proot-distro owns installation and the server process. This APK
 * deliberately does not start a Service, deploy a rootfs, or execute proot.
 */
public class DshWebActivity extends Activity {
    private static final String DSH_URL = "http://127.0.0.1:3080";
    private static final String WAIT_PAGE = "file:///android_asset/wait.html";

    private WebView webView;
    private boolean destroyed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable connect = () -> {
        if (!destroyed) webView.loadUrl(DSH_URL);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dsh_web);
        webView = findViewById(R.id.dsh_webview);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (DSH_URL.equals(url)) handler.removeCallbacks(connect);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame() && !destroyed) scheduleConnect();
            }
        });

        webView.loadUrl(WAIT_PAGE);
        scheduleConnect();
    }

    private void scheduleConnect() {
        handler.removeCallbacks(connect);
        handler.postDelayed(connect, 1500);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem refresh = menu.add("刷新");
        refresh.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        refresh.setOnMenuItemClickListener(item -> {
            webView.loadUrl(DSH_URL);
            return true;
        });
        MenuItem help = menu.add("Termux 启动说明");
        help.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        help.setOnMenuItemClickListener(item -> {
            webView.loadUrl(WAIT_PAGE);
            scheduleConnect();
            return true;
        });
        return true;
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        webView.stopLoading();
        webView.destroy();
        super.onDestroy();
    }
}
