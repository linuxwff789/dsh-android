package com.termux.app;

import android.app.Application;
import android.content.Context;
import android.util.Log;

/**
 * Minimal application class for DSH for Android.
 *
 * Deliberately does NOT wire up any termux bootstrap / am-socket machinery:
 * this app runs deepseek-harness inside a proot container via DshServerService
 * and does not depend on a termux prefix. The heavy TermuxApplication from
 * upstream is not used.
 */
public class DshApplication extends Application {

    private static final String LOG_TAG = "DshApplication";
    private static Context appContext;

    public static Context getAppContext() {
        return appContext;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        Log.i(LOG_TAG, "DSH for Android starting (pkg=" + getPackageName() + ")");
    }
}