package com.termux.shared.termux.shell.command.environment;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.AndroidShellEnvironment;
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils;
import com.termux.shared.shell.command.environment.ShellCommandShellEnvironment;
import com.termux.shared.termux.TermuxBootstrap;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxShellUtils;

import java.nio.charset.Charset;
import java.util.HashMap;

/**
 * Environment for Termux.
 */
public class TermuxShellEnvironment extends AndroidShellEnvironment {

    private static final String LOG_TAG = "TermuxShellEnvironment";

    /** Environment variable for the termux {@link TermuxConstants#TERMUX_PREFIX_DIR_PATH}. */
    public static final String ENV_PREFIX = "PREFIX";

    public TermuxShellEnvironment() {
        super();
        shellCommandShellEnvironment = new TermuxShellCommandShellEnvironment();
    }


    /** Init {@link TermuxShellEnvironment} constants and caches. */
    public synchronized static void init(@NonNull Context currentPackageContext) {
        TermuxAppShellEnvironment.setTermuxAppEnvironment(currentPackageContext);
    }

    /** Init {@link TermuxShellEnvironment} constants and caches. */
    public synchronized static void writeEnvironmentToFile(@NonNull Context currentPackageContext) {
        HashMap<String, String> environmentMap = new TermuxShellEnvironment().getEnvironment(currentPackageContext, false);
        String environmentString = ShellEnvironmentUtils.convertEnvironmentToDotEnvFile(environmentMap);

        // Write environment string to temp file and then move to final location since otherwise
        // writing may happen while file is being sourced/read
        Error error = FileUtils.writeTextToFile("termux.env.tmp", TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH,
            Charset.defaultCharset(), environmentString, false);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
            return;
        }

        error = FileUtils.moveRegularFile("termux.env.tmp", TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH, TermuxConstants.TERMUX_ENV_FILE_PATH, true);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
        }
    }

    /** Get shell environment for Termux. */
    @NonNull
    @Override
    public HashMap<String, String> getEnvironment(@NonNull Context currentPackageContext, boolean isFailSafe) {

        // Termux environment builds upon the Android environment
        HashMap<String, String> environment = super.getEnvironment(currentPackageContext, isFailSafe);

        HashMap<String, String> termuxAppEnvironment = TermuxAppShellEnvironment.getEnvironment(currentPackageContext);
        if (termuxAppEnvironment != null)
            environment.putAll(termuxAppEnvironment);

        HashMap<String, String> termuxApiAppEnvironment = TermuxAPIShellEnvironment.getEnvironment(currentPackageContext);
        if (termuxApiAppEnvironment != null)
            environment.putAll(termuxApiAppEnvironment);

        environment.put(ENV_HOME, TermuxConstants.TERMUX_HOME_DIR_PATH);
        environment.put(ENV_PREFIX, TermuxConstants.TERMUX_PREFIX_DIR_PATH);

        // If failsafe is not enabled, then we keep default PATH and TMPDIR so that system binaries can be used
        if (!isFailSafe) {
            environment.put(ENV_TMPDIR, TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            if (TermuxBootstrap.isAppPackageVariantAPTAndroid5()) {
                // Termux in android 5/6 era shipped busybox binaries in applets directory
                environment.put(ENV_PATH, TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/applets");
                environment.put(ENV_LD_LIBRARY_PATH, currentPackageContext.getFilesDir().getAbsolutePath() + "/usr/lib");
            } else {
                environment.put(ENV_PATH, TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
                // The official termux-packages bootstrap binaries carry an
                // absolute DT_RUNPATH of "/data/data/com.termux/files/usr/lib"
                // (the stock package path baked in at build time). In this fork
                // the prefix lives under dev.lwff.dsh, so that RUNPATH is stale
                // and exec'ing any binary fails with:
                //   CANNOT LINK EXECUTABLE ...: library "libandroid-support.so"
                //   not found: needed by main executable
                // bionic searches LD_LIBRARY_PATH BEFORE DT_RUNPATH, so pointing
                // it at $PREFIX/lib makes every bootstrap binary (and every
                // package later installed from termux-packages) resolve its
                // libraries from the fork's own prefix.
                //
                // Use the app's REAL files dir (context.getFilesDir() returns
                // /data/user/0/<pkg>/files on Android 10+), NOT the
                // TermuxConstants.TERMUX_* constants which bake in the
                // /data/data/<pkg> prefix. The app's linker namespace only
                // permits /data/user/0/<pkg> (see nativeloader
                // "permitted_path"), so LD_LIBRARY_PATH=/data/data/<pkg>/...
                // silently fails to resolve libraries and every exec of a
                // bootstrap binary fails with:
                //   CANNOT LINK EXECUTABLE ...: library "libncursesw.so.6"
                //   not found ... in namespace (default)
                environment.put(ENV_LD_LIBRARY_PATH,
                    currentPackageContext.getFilesDir().getAbsolutePath() + "/usr/lib");
                // Same baked-in-path problem for TLS: the stock binaries carry
                // /data/data/com.termux/.../cert.pem as their CA bundle path,
                // which the fork app cannot read. Point curl/openssl tools at
                // the fork's own bundle (apt's gnutls backend ignores this env
                // var and is handled via Acquire::https::CAInfo in the setup
                // script instead).
                String certPath = TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH + "/tls/cert.pem";
                environment.put("SSL_CERT_FILE", certPath);
                environment.put("CURL_CA_BUNDLE", certPath);
                // apt/dpkg binaries are compiled with /data/data/com.termux/...
                // baked in as their DEFAULT config/state/cache dirs. Without an
                // explicit APT_CONFIG the app would read/write the stock
                // Termux paths (another app's private dir -> EACCES or worse,
                // cross-talk with a real Termux install). dsh-apt.conf is
                // generated by termux-setup-dsh.sh and redirects every Dir::*
                // to this fork's own prefix, so point apt at it for every
                // terminal session.
                environment.put("APT_CONFIG",
                    currentPackageContext.getFilesDir().getAbsolutePath() + "/usr/etc/apt/dsh-apt.conf");
            }
        }

        return environment;
    }


    @NonNull
    @Override
    public String getDefaultWorkingDirectoryPath() {
        return TermuxConstants.TERMUX_HOME_DIR_PATH;
    }

    @NonNull
    @Override
    public String getDefaultBinPath() {
        return TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
    }

    @NonNull
    @Override
    public String[] setupShellCommandArguments(@NonNull String executable, String[] arguments) {
        return TermuxShellUtils.setupShellCommandArguments(executable, arguments);
    }

}
