package com.pkall.atvcontrol;

import android.content.Context;

import com.pkall.atvcontrol.config.AppConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Where the XML menus come from, in this order:
 *  1. REMOTE_BASE_URL (your own GitHub raw folder), if set and reachable
 *  2. the last copy downloaded from there (cache)
 *  3. the copy built into the app (assets/config)
 * If the remote files are broken the app falls back to the built-in copy, so a bad edit on GitHub
 * can never leave customers' boxes with a dead app.
 */
public final class ConfigRepository {

    /**
     * Leave empty to use only the menus built into the app.
     * To edit menus without rebuilding, put the XML files in YOUR OWN GitHub repo and set e.g.
     * "https://raw.githubusercontent.com/<your-user>/<your-repo>/main/ATV-Control/"
     */
    public static final String REMOTE_BASE_URL = "";

    private static AppConfig cached;

    public static synchronized AppConfig load(Context ctx) throws Exception {
        if (cached != null) return cached;
        Context app = ctx.getApplicationContext();
        if (!REMOTE_BASE_URL.isEmpty()) {
            try {
                cached = AppConfig.load(name -> remoteOrCache(app, name));
                return cached;
            } catch (Exception ignored) {
                // fall through to built-in menus
            }
        }
        cached = AppConfig.load(name -> app.getAssets().open("config/" + name));
        return cached;
    }

    private static InputStream remoteOrCache(Context app, String name) throws IOException {
        File dir = new File(app.getCacheDir(), "config");
        dir.mkdirs();
        File cache = new File(dir, name);
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(REMOTE_BASE_URL + name).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            byte[] data;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                data = bo.toByteArray();
            }
            try (FileOutputStream fo = new FileOutputStream(cache)) {
                fo.write(data);
            }
            return new ByteArrayInputStream(data);
        } catch (IOException e) {
            if (cache.isFile()) return new FileInputStream(cache);
            throw e;
        }
    }
}
