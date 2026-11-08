package com.pkall.atvcontrol;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import com.pkall.atvcontrol.config.SettingsOps;

import java.util.List;

/** Applies "settings put/delete" directly, once the app has been granted WRITE_SECURE_SETTINGS via ADB. */
final class SecureSettings {

    static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";

    private SecureSettings() {
    }

    static boolean hasPermission(Context c) {
        return c.checkCallingOrSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** "pm grant" command that gives this app the permission (must be run over ADB, once per box). */
    static String grantCommand(Context c) {
        return "pm grant " + c.getPackageName() + " " + PERMISSION;
    }

    static void apply(Context c, List<SettingsOps.Op> ops) {
        ContentResolver cr = c.getContentResolver();
        for (SettingsOps.Op o : ops) {
            if (o.namespace.equals("global")) Settings.Global.putString(cr, o.key, o.value);
            else Settings.Secure.putString(cr, o.key, o.value);
        }
    }

    /** 1 = on, 0 = off, -1 = unknown */
    static int adbEnabled(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), "adb_enabled");
        } catch (Exception e) {
            return -1;
        }
    }
}
