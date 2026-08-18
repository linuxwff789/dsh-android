package com.termux.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.termux.shared.net.uri.UriUtils;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Thin WebView client for the DSH server hosted by Termux.
 *
 * <p>Termux/proot-distro owns installation and the server process. This APK
 * deliberately does not start a Service, deploy a rootfs, or execute proot.
 */
public class DshWebActivity extends Activity {
    private static final String DSH_URL = "http://127.0.0.1:3080";
    private static final String WAIT_PAGE = "file:///android_asset/wait.html";
    private static final String SETUP_MARKER = ".dsh-setup-complete";

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
        startBundledTermuxSetupIfNeeded();
    }

    /**
     * Run the bundled installer in a visible Termux terminal session. Termux
     * owns the shell, so apt/pnpm/build output is rendered live to the user.
     */
    private void startBundledTermuxSetupIfNeeded() {
        File home = new File(getFilesDir(), "home");
        File marker = new File(home, SETUP_MARKER);
        if (marker.exists()) return;
        final File script;
        final File patch;
        try {
            home.mkdirs();
            script = new File(home, "dsh-android/scripts/termux-setup-dsh.sh");
            patch = new File(home, "dsh-android/scripts/patches/dsh-on-android.patch");
            copyAsset("termux-setup/termux-setup-dsh.sh", script, true);
            copyAsset("termux-setup/patches/dsh-on-android.patch", patch, false);
        } catch (Exception e) {
            android.util.Log.e("DshWebActivity", "Unable to copy bundled Termux installer assets", e);
            return;
        }
        // The embedded Termux runtime (files/usr) is populated by
        // TermuxInstaller, which TermuxActivity only triggers while it has NO
        // sessions. We start the installer session ourselves below, so ensure
        // the bootstrap exists first — otherwise the script shebang
        // (#!.../files/usr/bin/bash) fails with "script not found" because
        // bash is missing.
        TermuxInstaller.setupBootstrapIfNeeded(this, () -> {
            if (destroyed) return;
            try {
                Intent intent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE,
                        UriUtils.getFileUri(script.getAbsolutePath()));
                intent.setClass(this, TermuxService.class);
                intent.putExtra(TERMUX_SERVICE.EXTRA_RUNNER, "terminal-session");
                // Do not ask the service to launch TermuxActivity: that path requires
                // SYSTEM_ALERT_WINDOW on Android 10+. We are already foreground, so
                // launch the terminal activity ourselves below.
                intent.putExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION,
                        TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY);
                intent.putExtra(TERMUX_SERVICE.EXTRA_SHELL_CREATE_MODE, "always");
                intent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "Install DSH");
                intent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION,
                        "Install Debian, Node.js and DeepSeek Harness");
                intent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[0]);
                startService(intent);
                handler.postDelayed(() -> {
                    if (destroyed) return;
                    Intent terminal = new Intent(this, TermuxActivity.class);
                    terminal.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(terminal);
                }, 500);
            } catch (Exception e) {
                android.util.Log.e("DshWebActivity", "Unable to start bundled Termux installer", e);
            }
        });
    }

    private void copyAsset(String assetName, File destination, boolean executable) throws Exception {
        destination.getParentFile().mkdirs();
        try (InputStream in = getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        // The bundled script must use this APK's private Termux prefix. The
        // original Termux shebang (/data/data/com.termux/...) is invalid when
        // the embedded Termux runtime belongs to dev.lwff.dsh.
        if (assetName.endsWith("termux-setup-dsh.sh")) {
            byte[] bytes = java.nio.file.Files.readAllBytes(destination.toPath());
            String text = new String(bytes, StandardCharsets.UTF_8);
            String bash = new File(getFilesDir(), "usr/bin/bash").getAbsolutePath();
            if (text.startsWith("#!")) {
                int newline = text.indexOf('\n');
                text = "#!" + bash + (newline >= 0 ? text.substring(newline) : "\n");
                try (FileOutputStream out = new FileOutputStream(destination)) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        destination.setExecutable(executable, false);
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
