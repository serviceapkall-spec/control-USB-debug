package com.pkall.atvcontrol;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.pkall.atvcontrol.adb.AdbConnection;
import com.pkall.atvcontrol.adb.AdbCrypto;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One ADB connection shared by all screens. All network work runs on a single background thread. */
public final class AdbManager {

    public static final String HOST = "127.0.0.1";
    public static final int PORT = 5555;

    public interface StatusListener {
        void onStatus(AdbConnection.Status status);
        void onFailed(String message);
    }

    public interface ResultListener {
        void onResult(boolean ok, boolean disconnected, String output);
    }

    private static AdbManager instance;

    private final Context app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile AdbConnection connection;

    private AdbManager(Context ctx) {
        app = ctx.getApplicationContext();
    }

    public static synchronized AdbManager get(Context ctx) {
        if (instance == null) instance = new AdbManager(ctx);
        return instance;
    }

    public boolean isConnected() {
        AdbConnection c = connection;
        return c != null && c.isConnected();
    }

    /** Drop the link (used right after the app itself turns USB debugging off). */
    public void disconnect() {
        AdbConnection c = connection;
        connection = null;
        if (c != null) worker.execute(c::close);
    }

    public void connect(final StatusListener l) {
        worker.execute(() -> {
            if (isConnected()) {
                post(() -> l.onStatus(AdbConnection.Status.CONNECTED));
                return;
            }
            try {
                AdbCrypto crypto = AdbCrypto.loadOrCreate(app.getFilesDir());
                connection = AdbConnection.open(HOST, PORT, crypto, "ATVControl@" + Build.MODEL,
                        s -> post(() -> l.onStatus(s)));
            } catch (Exception e) {
                connection = null;
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                post(() -> l.onFailed(msg));
            }
        });
    }

    public void run(final String command, final ResultListener l) {
        worker.execute(() -> {
            AdbConnection c = connection;
            if (c == null || !c.isConnected()) {
                post(() -> l.onResult(false, true, "ยังไม่ได้เชื่อมต่อ ADB"));
                return;
            }
            try {
                String out = c.shell(command);
                post(() -> l.onResult(true, false, out));
            } catch (AdbConnection.DisconnectedException e) {
                connection = null;
                post(() -> l.onResult(true, true, e.partialOutput));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                post(() -> l.onResult(false, false, msg));
            }
        });
    }

    private void post(Runnable r) {
        main.post(r);
    }
}
