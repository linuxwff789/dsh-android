package com.termux.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * First-run deployment of the dsh-in-proot runtime.
 *
 * <p>Two stages:
 * 1. deployBase() — extract the small bundled base rootfs (offline) and
 *    widen its permissions.
 * 2. runInstaller() — boot the container with proot and run install.sh
 *    ONLINE (China mirrors: TUNA apt + npmmirror node/npm), which installs
 *    node + deepseek-harness and writes /opt/start-dsh.sh.
 *
 * All operations idempotent.
 */
public final class DshSetup {

    private static final String LOG_TAG = "DshSetup";

    public static final String DSH_DIR = "dsh";
    public static final String BIN_DIR = DSH_DIR + "/bin";
    public static final String LIB_DIR = DSH_DIR + "/lib";
    public static final String ROOTFS_DIR = DSH_DIR + "/rootfs";
    public static final String BASE_MARKER = DSH_DIR + "/.base-ready";

    private DshSetup() {}

    /** Base rootfs extracted at least once. */
    public static boolean isBaseExtracted(Context context) {
        return new File(context.getFilesDir(), BASE_MARKER).exists();
    }

    /** dsh fully installed via the online installer (opt/.dsh-installed). */
    public static boolean isDshInstalled(Context context) {
        return new File(rootfsDir(context), "opt/.dsh-installed").exists();
    }

    /** Resolves the real rootfs dir (bare base rootfs: etc/ at top). */
    public static File rootfsDir(Context context) {
        File dir = new File(context.getFilesDir(), ROOTFS_DIR);
        File inner = new File(dir, "rootfs");
        if (new File(inner, "etc").isDirectory()) return inner;
        return dir;
    }

    /**
     * Copies bundled binaries/libs + install script + base archive from
     * assets into filesDir/dsh. Cheap, idempotent.
     */
    public static void ensureBinaries(Context context) {
        File binDir = new File(context.getFilesDir(), BIN_DIR);
        File libDir = new File(context.getFilesDir(), LIB_DIR);
        binDir.mkdirs();
        libDir.mkdirs();
        String[] bins = {"proot", "xz", "busybox"};
        String[] libs = {"libtalloc.so.2", "libandroid-shmem.so", "libandroid.so",
                "liblzma.so.5", "libbusybox.so.1.38.0", "libandroid-selinux.so",
                "libpcre2-8.so", "libtermux-exec.so"};
        for (String name : bins) {
            extractAsset(context, "opt/dsh/bin/" + name, new File(binDir, name), true);
        }
        for (String name : libs) {
            extractAsset(context, "opt/dsh/lib/" + name, new File(libDir, name), false);
        }
        // installer script + small bundled base
        extractAsset(context, "opt/dsh/install.sh", new File(filesDsh(context), "install.sh"), true);
        extractAsset(context, "opt/dsh/base.tar.xz", new File(filesDsh(context), "base.tar.xz"), false);
    }

    /** Stage 1: extract the bundled minimal base rootfs (offline). */
    public static synchronized boolean deployBase(Context context) {
        File rootfs = rootfsDir(context);
        if (rootfs.exists()) widenPermissions(context, rootfs);
        if (isBaseExtracted(context)) return validateBase(context);

        rootfs.mkdirs();
        File archive = new File(filesDsh(context), "base.tar.xz");
        if (!archive.exists() || archive.length() == 0) {
            Log.e(LOG_TAG, "base archive missing");
            return false;
        }

        if (!runShell(context, "busybox tar -xJf " + quote(archive.getAbsolutePath())
                + " -C " + quote(rootfs.getAbsolutePath()))) {
            Log.e(LOG_TAG, "base extraction failed");
            return false;
        }
        widenPermissions(context, rootfs);

        // container DNS + hosts
        writeFile(new File(rootfsDir(context), "etc/resolv.conf"),
                "nameserver 223.5.5.5\nnameserver 8.8.8.8\n");
        File hosts = new File(rootfsDir(context), "etc/hosts");
        if (!hosts.exists()) {
            writeFile(hosts, "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n");
        }

        if (!validateBase(context)) {
            Log.e(LOG_TAG, "base invalid (no /bin/sh or install.sh target)");
            return false;
        }
        touch(context, BASE_MARKER);
        Log.i(LOG_TAG, "base deployed");
        return true;
    }

    /** Stage 2: boot the container and run the online installer. Blocking. */
    public static synchronized long runInstaller(Context context) {
        File script = new File(filesDsh(context), "install.sh");
        if (!script.exists()) {
            Log.e(LOG_TAG, "install.sh missing");
            return -1;
        }
        // place the installer inside the container
        File guestInstaller = new File(rootfsDir(context), "opt/install-dsh.sh");
        copyFile(script, guestInstaller, true);
        // also drop the base archive out of the container path (not needed after deploy)
        new File(filesDsh(context), "base.tar.xz").delete();

        File rootfs = rootfsDir(context);
        File binDir = new File(context.getFilesDir(), BIN_DIR);
        File libDir = new File(context.getFilesDir(), LIB_DIR);
        File cacheDir = new File(filesDsh(context), "cache");
        cacheDir.mkdirs();

        String kernelRelease = "\\Linux\\localhost\\6.17.0-PRoot-Distro"
                + "\\#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000"
                + "\\aarch64\\localdomain\\-1\\";

        ProcessBuilder pb = new ProcessBuilder(
                new File(binDir, "proot").getAbsolutePath(),
                "--link2symlink", "--sysvipc",
                "--kernel-release=" + kernelRelease, "-L",
                "--change-id=0:0",
                "--rootfs=" + rootfs.getAbsolutePath(), "--cwd=/",
                "--bind=/dev", "--bind=/proc", "--bind=/sys",
                "--bind=/dev/urandom:/dev/random",
                "/bin/sh", "/opt/install-dsh.sh"
        );
        pb.environment().put("PATH", binDir.getAbsolutePath() + ":/system/bin");
        pb.environment().put("LD_LIBRARY_PATH", libDir.getAbsolutePath());
        pb.environment().put("HOME", "/root");
        pb.environment().put("TMPDIR", "/tmp");
        pb.environment().remove("LD_PRELOAD");
        pb.redirectErrorStream(true);

        File log = new File(filesDsh(context), "install.log");
        try {
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (FileOutputStream fo = new FileOutputStream(log);
                 InputStream is = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    out.append(new String(buf, 0, n, "UTF-8"));
                    fo.write(buf, 0, n);
                }
            }
            if (!p.waitFor(45, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                Log.e(LOG_TAG, "installer timed out");
                return -2;
            }
            Log.i(LOG_TAG, "installer exited " + p.exitValue());
            if (p.exitValue() != 0) {
                Log.e(LOG_TAG, "install failed: " + out);
                return p.exitValue();
            }
            return 0;
        } catch (Exception e) {
            Log.e(LOG_TAG, "installer error", e);
            return -1;
        }
    }

    private static boolean validateBase(Context context) {
        File r = rootfsDir(context);
        return new File(r, "bin/sh").exists() || new File(r, "usr/bin/sh").exists();
    }

    /** Widens rootfs permissions to world-readable (u=rwx,go+rX). */
    private static void widenPermissions(Context context, File rootfs) {
        runShell(context, "busybox chmod -R u=rwx,go+rX " + quote(rootfs.getAbsolutePath()));
    }

    /** Runs a command via the bundled busybox with our lib path. */
    private static boolean runShell(Context context, String inner) {
        File libDir = new File(context.getFilesDir(), LIB_DIR);
        File binDir = new File(context.getFilesDir(), BIN_DIR);
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "/system/bin/sh", "-c",
                    "LD_LIBRARY_PATH=" + quote(libDir.getAbsolutePath())
                        + " " + new File(binDir, "busybox").getAbsolutePath() + " " + inner
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(120, TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (Exception e) {
            Log.e(LOG_TAG, "runShell failed: " + inner, e);
            return false;
        }
    }

    private static File filesDsh(Context context) {
        return new File(context.getFilesDir(), DSH_DIR);
    }

    private static void extractAsset(Context context, String assetPath, File dest, boolean executable) {
        if (dest.exists() && dest.length() > 0) return;
        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (Exception e) {
            Log.e(LOG_TAG, "asset extract failed: " + assetPath, e);
            return;
        }
        if (executable) dest.setExecutable(true, true);
    }

    private static void copyFile(File src, File dst, boolean executable) {
        src = src; // keep signature
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (Exception e) {
            Log.e(LOG_TAG, "copy failed: " + dst, e);
            return;
        }
        if (executable) dst.setExecutable(true, true);
    }

    private static void writeFile(File file, String content) {
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes("UTF-8"));
        } catch (Exception e) {
            Log.e(LOG_TAG, "write failed: " + file, e);
        }
    }

    private static void touch(Context context, String rel) {
        File f = new File(context.getFilesDir(), rel);
        try {
            if (!f.exists()) f.createNewFile();
        } catch (Exception ignored) {
        }
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}