package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that keeps the dsh web server alive inside the proot
 * container. Spawns: proot -r rootfs -0 -w / /bin/sh /opt/start-dsh.sh
 * and restarts the process if it dies while the service is running.
 */
public class DshServerService extends Service {

    private static final String LOG_TAG = "DshServer";

    public static final String CHANNEL_ID = "dsh_server";
    private static final int NOTIF_ID = 1001;

    public static final String PREFS = "dsh_prefs";
    public static final String KEY_API_KEY = "deepseek_api_key";
    public static final String KEY_LAN = "lan_mode";

    private Process process;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private PowerManager.WakeLock wakeLock;
    private Thread supervisor;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotification(false));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running.getAndSet(true)) return START_STICKY;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh:server");
            wakeLock.acquire();
        }

        supervisor = new Thread(this::supervise, "dsh-supervisor");
        supervisor.start();
        return START_STICKY;
    }

    private void supervise() {
        while (running.get()) {
            try {
                DshSetup.ensureBinaries(this);
            } catch (Throwable t) {
                Log.e(LOG_TAG, "ensureBinaries failed", t);
                sleep(2000);
                continue;
            }
            if (!DshSetup.deployBase(this)) {
                Log.e(LOG_TAG, "base not deployed, retrying");
                sleep(3000);
                continue;
            }
            if (!DshSetup.isDshInstalled(this)) {
                Log.i(LOG_TAG, "dsh not installed — running online installer (China mirrors)");
                long rc = DshSetup.runInstaller(this);
                Log.i(LOG_TAG, "installer returned " + rc);
                sleep(5000);
                continue;
            }
            Log.i(LOG_TAG, "starting dsh web inside container");
            process = spawnServerProcess();
            if (process == null) {
                sleep(3000);
                continue;
            }
            drainOutput(process);
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            Log.w(LOG_TAG, "dsh process exited with " + process.exitValue() + "; restarting");
            process = null;
            sleep(2000);
        }
    }

    private Process spawnServerProcess() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        File filesDir = getFilesDir();
        File binDir = new File(filesDir, DshSetup.BIN_DIR);
        File libDir = new File(filesDir, DshSetup.LIB_DIR);
        File rootfs = DshSetup.rootfsDir(this);
        File logFile = new File(filesDir, "dsh/server.log");
        boolean lan = prefs.getBoolean(KEY_LAN, false);
        File cacheDir = new File(filesDir, "cache");
        cacheDir.mkdirs();

        // Mirrors proot-distro's proven argv. The fake kernel-release string
        // (with arch suffix) is REQUIRED for sane guest uname/elf handling.
        String kernelRelease = "\\Linux\\localhost\\6.17.0-PRoot-Distro"
                + "\\#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000"
                + "\\aarch64\\localdomain\\-1\\";
        ProcessBuilder pb = new ProcessBuilder(
                new File(binDir, "proot").getAbsolutePath(),
                "--link2symlink",
                "--sysvipc",
                "--kernel-release=" + kernelRelease,
                "-L",
                "--change-id=0:0",
                "--rootfs=" + rootfs.getAbsolutePath(),
                "--cwd=/",
                "--bind=/dev",
                "--bind=/proc",
                "--bind=/sys",
                "--bind=/dev/urandom:/dev/random",
                "/bin/sh", "/opt/start-dsh.sh"
        );
        pb.environment().put("PATH", binDir.getAbsolutePath() + ":/system/bin");
        pb.environment().put("LD_LIBRARY_PATH", libDir.getAbsolutePath());
        pb.environment().put("HOME", "/root");
        // TMPDIR must live INSIDE the container: proot canonicalizes host
        // paths through its virtual fs and falls back to its compiled-in
        // termux default (unwritable here), which breaks its exec shim.
        pb.environment().put("TMPDIR", "/tmp");
        // ...but that default (/data/data/com.termux/files/usr/tmp/) is ALSO
        // used for proot's internal workspace before the guest sees TMPDIR,
        // and this standalone app has no access to it. Point PROOT_TMP_DIR at
        // our own writable cache so proot can start at all.
        pb.environment().put("PROOT_TMP_DIR", cacheDir.getAbsolutePath());
        pb.environment().remove("LD_PRELOAD");
        String apiKey = prefs.getString(KEY_API_KEY, null);
        if (apiKey != null && !apiKey.isEmpty()) {
            pb.environment().put("DEEPSEEK_API_KEY", apiKey);
        }
        if (lan) {
            pb.environment().put("DSH_WEB_HOST", "0.0.0.0");
        }
        pb.redirectErrorStream(true);
        try {
            return pb.start();
        } catch (Exception e) {
            Log.e(LOG_TAG, "spawn failed", e);
            return null;
        }
    }

    private void drainOutput(Process p) {
        final File logFile = new File(getFilesDir(), "dsh/server.log");
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                FileOutputStream out = new FileOutputStream(logFile, true);
                String line;
                while ((line = r.readLine()) != null) {
                    out.write((line + "\n").getBytes("UTF-8"));
                }
                out.close();
            } catch (Exception ignored) {
            }
        }, "dsh-log-drain");
        t.setDaemon(true);
        t.start();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onDestroy() {
        running.set(false);
        if (process != null) {
            process.destroy();
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "DSH 服务器", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(boolean update) {
        Intent openIntent = new Intent(this, DshWebActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, DshServerService.class);
        stopIntent.setAction("STOP");
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("DSH 运行中")
                .setContentText("localhost:3080 — 点击打开")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(0, "打开", openPi)
                .addAction(0, "停止", stopPi)
                .build();
    }

    /** Gracefully stops the service (used by the "停止" action). */
    public static void stop(Context context) {
        context.stopService(new Intent(context, DshServerService.class));
    }
}