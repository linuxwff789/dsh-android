package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * Launcher activity: full-screen WebView pointing at the dsh web UI on
 * http://127.0.0.1:3080. Starts the DshServerService on create so the
 * server keeps running while the app is in the foreground, and polls the
 * local URL until the container is up.
 */
public class DshWebActivity extends Activity {

    private static final String DSH_URL = "http://127.0.0.1:3080";
    private static final String WAIT_PAGE = "file:///android_asset/wait.html";

    private WebView webView;
    private int attempt = 0;
    private boolean userStopped = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dsh_web);

        webView = findViewById(R.id.dsh_webview);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (DSH_URL.equals(url)) {
                    attempt = 0;
                } else if (!userStopped && attempt < 120) {
                    retryConnect();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (userStopped) return;
                if (attempt < 120) {
                    retryConnect();
                }
            }
        });

        startService(new Intent(this, DshServerService.class));
        webView.loadUrl(WAIT_PAGE);
        retryConnect();
    }

    private void retryConnect() {
        attempt++;
        webView.postDelayed(() -> {
            if (!userStopped && attempt < 120) {
                webView.loadUrl(DSH_URL);
            }
        }, 1500);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        userStopped = true;
        super.onDestroy();
        // Keep the server running in the background; user stops it via the
        // notification or the menu.
    }

    private void showApiKeyDialog() {
        SharedPreferences prefs = getSharedPreferences(DshServerService.PREFS, MODE_PRIVATE);
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(prefs.getString(DshServerService.KEY_API_KEY, ""));
        new AlertDialog.Builder(this)
                .setTitle("DeepSeek API Key")
                .setMessage("写入后注入容器环境变量，重启服务生效")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    prefs.edit().putString(DshServerService.KEY_API_KEY,
                            input.getText().toString().trim()).apply();
                    restartServer();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toggleLanMode() {
        SharedPreferences prefs = getSharedPreferences(DshServerService.PREFS, MODE_PRIVATE);
        boolean lan = !prefs.getBoolean(DshServerService.KEY_LAN, false);
        prefs.edit().putBoolean(DshServerService.KEY_LAN, lan).apply();
        restartServer();
        new AlertDialog.Builder(this)
                .setMessage(lan
                        ? "LAN 模式已开启：容器监听 0.0.0.0，局域网设备可访问（无鉴权，注意安全）"
                        : "LAN 模式已关闭：仅本机 localhost 可访问")
                .setPositiveButton("好", null)
                .show();
    }

    private void restartServer() {
        stopService(new Intent(this, DshServerService.class));
        startService(new Intent(this, DshServerService.class));
    }

    private void openTerminal() {
        Intent i = new Intent(this, com.termux.app.TermuxActivity.class);
        startActivity(i);
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add("设置 API Key").setOnMenuItemClickListener(m -> {
            showApiKeyDialog();
            return true;
        });
        menu.add("切换 LAN 模式").setOnMenuItemClickListener(m -> {
            toggleLanMode();
            return true;
        });
        menu.add("重启服务").setOnMenuItemClickListener(m -> {
            restartServer();
            return true;
        });
        menu.add("打开终端").setOnMenuItemClickListener(m -> {
            openTerminal();
            return true;
        });
        menu.add("停止服务").setOnMenuItemClickListener(m -> {
            userStopped = true;
            stopService(new Intent(this, DshServerService.class));
            return true;
        });
        return super.onCreateOptionsMenu(menu);
    }
}