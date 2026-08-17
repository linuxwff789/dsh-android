package com.termux.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * First-run deployment of the dsh-in-proot runtime.
 *
 * <p>Builds a CLEAN Debian minbase on-device (no bundled/termux-path rootfs):
 * <ol>
 *   <li>deployBase() — run bundled {@code bootstrap.sh} (busybox) which
 *       downloads the essential .debs from TUNA and unpacks them with
 *       busybox ar+tar, then registers them via dpkg under proot. Produces a
 *       rootfs with NO termux paths baked in.</li>
 *   <li>runInstaller() — boot the container with proot and run install.sh
 *       ONLINE (TUNA apt + npmmirror), installing node + deepseek-harness and
 *       writing /opt/start-dsh.sh.</li>
 * </ol>
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

    /** Clean Debian rootfs bootstrapped at least once. */
    public static boolean isBaseExtracted(Context context) {
        return new File(context.getFilesDir(), BASE_MARKER).exists();
    }

    /** dsh fully installed via the online installer (opt/.dsh-installed). */
    public static boolean isDshInstalled(Context context) {
        return new File(rootfsDir(context), "opt/.dsh-installed").exists();
    }

    /** Resolves the real rootfs dir (clean minbase: etc/ at top). */
    public static File rootfsDir(Context context) {
        File dir = new File(context.getFilesDir(), ROOTFS_DIR);
        File inner = new File(dir, "rootfs");
        if (new File(inner, "etc").isDirectory()) return inner;
        return dir;
    }

    /**
     * Copies bundled binaries/libs + bootstrap.sh + pkglist + install.sh from
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
        extractAsset(context, "opt/dsh/bootstrap.sh", new File(filesDsh(context), "bootstrap.sh"), true);
        extractAsset(context, "opt/dsh/pkglist.txt", new File(filesDsh(context), "pkglist.txt"), false);
        extractAsset(context, "opt/dsh/install.sh", new File(filesDsh(context), "install.sh"), true);
    }

    /**
     * Stage 1: bootstrap a clean Debian minbase from TUNA into the rootfs
     * (download + busybox-unpack + dpkg-finalize). Blocking.
     */
    public static synchronized boolean deployBase(Context context) {
        if (isBaseExtracted(context)) return validateBase(context);

        File rootfs = rootfsDir(context);
        rootfs.mkdirs();
        File filesDsh = filesDsh(context);
        File cacheDir = new File(filesDsh, "cache");
        cacheDir.mkdirs();
        String mirror = System.getProperty("dsh.mirror", "http://mirrors.tuna.tsinghua.edu.cn/debian");

        ProcessBuilder pb = new ProcessBuilder(
                new File(context.getFilesDir(), BIN_DIR + "/busybox").getAbsolutePath(),
                "sh", new File(filesDsh, "bootstrap.sh").getAbsolutePath(), rootfs.getAbsolutePath(), "all"
        );
        Map<String, String> e = pb.environment();
        e.put("PATH", new File(context.getFilesDir(), BIN_DIR).getAbsolutePath() + ":/system/bin");
        e.put("LD_LIBRARY_PATH", new File(context.getFilesDir(), LIB_DIR).getAbsolutePath());
        e.put("TMPDIR", "/tmp");
        e.put("HOME", "/root");
        e.put("DSH_BUSYBOX", new File(context.getFilesDir(), BIN_DIR + "/busybox").getAbsolutePath());
        e.put("DSH_PROOT", new File(context.getFilesDir(), BIN_DIR + "/proot").getAbsolutePath());
        e.put("DSH_LD_LIBRARY_PATH", new File(context.getFilesDir(), LIB_DIR).getAbsolutePath());
        e.put("DSH_PKGLIST", new File(filesDsh, "pkglist.txt").getAbsolutePath());
        e.put("DSH_MIRROR", mirror);
        e.put("PROOT_TMP_DIR", cacheDir.getAbsolutePath());
        e.remove("LD_PRELOAD");
        pb.redirectErrorStream(true);

        File log = new File(filesDsh, "bootstrap.log");
        long rc = runProcess(pb, log, 60, TimeUnit.MINUTES);
        if (rc != 0) {
            Log.e(LOG_TAG, "bootstrap failed rc=" + rc);
            return false;
        }
        if (!validateBase(context)) {
            Log.e(LOG_TAG, "bootstrap invalid (no /bin/sh)");
            return false;
        }
        // container DNS + hosts (bootstrap already wrote resolv.conf; ensure hosts)
        File hosts = new File(rootfsDir(context), "etc/hosts");
        if (!hosts.exists()) {
            writeFile(hosts, "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n");
        }
        touch(context, BASE_MARKER);
        Log.i(LOG_TAG, "clean Debian base bootstrapped");
        return true;
    }

    /** Stage 2: boot the container and run the online installer. Blocking. */
    public static synchronized long runInstaller(Context context) {
        File script = new File(filesDsh(context), "install.sh");
        if (!script.exists()) {
            Log.e(LOG_TAG, "install.sh missing");
            return -1;
        }
        File rootfs = rootfsDir(context);
        // place the installer inside the container
        File guestInstaller = new File(rootfs, "opt/install-dsh.sh");
        guestInstaller.getParentFile().mkdirs();
        copyFile(script, guestInstaller, true);

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
        Map<String, String> e = pb.environment();
        e.put("PATH", binDir.getAbsolutePath() + ":/system/bin");
        e.put("LD_LIBRARY_PATH", libDir.getAbsolutePath());
        e.put("HOME", "/root");
        e.put("TMPDIR", "/tmp");
        e.put("PROOT_TMP_DIR", cacheDir.getAbsolutePath());
        e.remove("LD_PRELOAD");
        pb.redirectErrorStream(true);

        File log = new File(filesDsh(context), "install.log");
        long rc = runProcess(pb, log, 45, TimeUnit.MINUTES);
        if (rc == 0) Log.i(LOG_TAG, "install.sh finished cleanly");
        return rc;
    }

    private static boolean validateBase(Context context) {
        File r = rootfsDir(context);
        return new File(r, "bin/sh").exists() || new File(r, "usr/bin/sh").exists();
    }

    /** Runs a process, draining output into {@code log}. Returns exit code (or negative on error/timeout). */
    private static long runProcess(ProcessBuilder pb, File log, long timeout, TimeUnit unit) {
        try {
            File logDir = log.getParentFile();
            if (logDir != null) logDir.mkdirs();
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
            if (!p.waitFor(timeout, unit)) {
                p.destroyForcibly();
                Log.e(LOG_TAG, "process timed out: " + pb.command());
                return -2;
            }
            Log.i(LOG_TAG, "process exited " + p.exitValue() + ": " + pb.command().get(0));
            if (p.exitValue() != 0) Log.e(LOG_TAG, Arrays.toString(pb.command().toArray()) + "\n" + out);
            return p.exitValue();
        } catch (Exception e) {
            Log.e(LOG_TAG, "process error: " + pb.command(), e);
            return -1;
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
        dst.getParentFile().mkdirs();
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (Exception e) {
            Log.e(LOG_TAG, "copy failed: " + dst, e);
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
}
