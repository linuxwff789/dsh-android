package com.termux.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * First-run deployment of the dsh-in-proot runtime.
 *
 * <p>Extracts the bundled proot/xz binaries and libraries into filesDir/dsh
 * and (on first run only) unpacks the rootfs archive into filesDir/dsh/rootfs.
 * All operations are idempotent.
 */
public final class DshSetup {

    private static final String LOG_TAG = "DshSetup";

    public static final String DSH_DIR = "dsh";
    public static final String BIN_DIR = DSH_DIR + "/bin";
    public static final String LIB_DIR = DSH_DIR + "/lib";
    public static final String ROOTFS_DIR = DSH_DIR + "/rootfs";
    public static final String ROOTFS_MARKER = DSH_DIR + "/.rootfs-ready";

    private DshSetup() {}

    /** True once the rootfs has been unpacked at least once. */
    public static boolean isRootfsDeployed(Context context) {
        return new File(context.getFilesDir(), ROOTFS_MARKER).exists();
    }

    public static File rootfsDir(Context context) {
        return new File(context.getFilesDir(), ROOTFS_DIR);
    }

    public static File etcResolvConf(Context context) {
        return new File(rootfsDir(context), "etc/resolv.conf");
    }

    /**
     * Copies bundled small binaries/libraries from assets into filesDir/dsh.
     * Cheap; safe to run on every start (idempotent overwrite).
     */
    public static void ensureBinaries(Context context) {
        File binDir = new File(context.getFilesDir(), BIN_DIR);
        File libDir = new File(context.getFilesDir(), LIB_DIR);
        binDir.mkdirs();
        libDir.mkdirs();
        String[] bins = {"proot", "xz"};
        String[] libs = {"liblzma.so.5.8.3", "libtalloc.so.2.4.3", "libandroid-shmem.so", "libandroid.so"};
        for (String name : bins) {
            extractAsset(context, "opt/dsh/bin/" + name, new File(binDir, name), true);
        }
        for (String name : libs) {
            extractAsset(context, "opt/dsh/lib/" + name, new File(libDir, name), false);
        }
    }

    /**
     * Unpacks rootfs.tar.xz (asset or pre-staged file) into filesDir/dsh/rootfs.
     * Requires the termux bootstrap (xz + tar) to be installed, since the
     * android app process cannot decode xz itself.
     * Returns true on success.
     */
    public static synchronized boolean deployRootfs(Context context) {
        if (isRootfsDeployed(context)) return true;
        File rootfs = rootfsDir(context);
        rootfs.mkdirs();

        File archive = new File(context.getFilesDir(), "dsh/rootfs.tar.xz");
        if (!archive.exists()) {
            // Asset lands directly if the APK bundled it; otherwise the caller
            // must pre-stage it (e.g. downloaded from a release).
            extractAsset(context, "opt/dsh/rootfs.tar.xz", archive, false);
        }
        if (!archive.exists() || archive.length() == 0) {
            Log.e(LOG_TAG, "rootfs archive missing");
            return false;
        }

        try {
            File usrBin = new File(context.getFilesDir(), BIN_DIR);
            // Use Android's system shell + toybox tar (always present); no
            // termux bootstrap required.
            ProcessBuilder pb = new ProcessBuilder(
                "/system/bin/sh", "-c",
                "LD_LIBRARY_PATH=" + quote(new File(context.getFilesDir(), LIB_DIR).getAbsolutePath())
                    + " " + quote(new File(usrBin, "xz").getAbsolutePath())
                    + " -dc " + quote(archive.getAbsolutePath())
                    + " | /system/bin/toybox tar -x -C " + quote(rootfs.getAbsolutePath())
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.MINUTES)) {
                p.destroyForcibly();
                Log.e(LOG_TAG, "rootfs extraction timed out");
                return false;
            }
            if (p.exitValue() != 0) {
                Log.e(LOG_TAG, "rootfs extraction failed with exit " + p.exitValue());
                return false;
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "rootfs extraction error", e);
            return false;
        }

        // Android DNS: containers need a resolv.conf; Android's own resolver
        // is not visible inside the container.
        writeFile(etcResolvConf(context), "nameserver 223.5.5.5\nnameserver 8.8.8.8\n");
        // hosts with localhost mapping, mirrors the proot-distro behaviour
        File hosts = new File(rootfs, "etc/hosts");
        if (!hosts.exists()) {
            writeFile(hosts, "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n");
        }

        new File(context.getFilesDir(), ROOTFS_MARKER).delete();
        try {
            new File(context.getFilesDir(), ROOTFS_MARKER).createNewFile();
        } catch (Exception ignored) {
        }
        Log.i(LOG_TAG, "rootfs deployed");
        return true;
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
        if (executable) {
            try {
                dest.setExecutable(true, true);
            } catch (Exception ignored) {
            }
        }
    }

    private static void writeFile(File file, String content) {
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes("UTF-8"));
        } catch (Exception e) {
            Log.e(LOG_TAG, "write failed: " + file, e);
        }
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}