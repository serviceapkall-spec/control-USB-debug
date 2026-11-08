package com.pkall.atvcontrol;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.pkall.atvcontrol.adb.AdbConnection;
import com.pkall.atvcontrol.config.AppConfig;

import java.util.concurrent.Executors;

/** Welcome screen: ADB status + CONNECT button on the left, menus on the right. */
public class MainActivity extends Activity implements AdbManager.StatusListener {

    private AppConfig cfg;
    private TextView status;
    private TextView connectLabel;
    private LinearLayout connectTile;
    private LinearLayout menuColumn;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout[] cols = Ui.screen(this);
        menuColumn = cols[1];
        Ui.add(cols[0], Ui.text(this, "กำลังโหลด...", 20, 0xFFFFFFFF, false), 0);

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                AppConfig loaded = ConfigRepository.load(this);
                runOnUiThread(() -> build(cols[0], loaded));
            } catch (Exception e) {
                runOnUiThread(() -> Ui.message(this, "โหลดเมนูไม่สำเร็จ", e.getMessage()));
            }
        });
    }

    private void build(LinearLayout left, AppConfig loaded) {
        cfg = loaded;
        AppConfig.Welcome w = cfg.welcome;
        left.removeAllViews();

        Ui.add(left, Ui.text(this, w.title, 40, 0xFFFFFFFF, true), 0);
        Ui.add(left, Ui.text(this, w.description, 18, 0xDDFFFFFF, false), 4);

        status = Ui.text(this, w.checking, 24, Ui.WAIT_AMBER, true);
        Ui.add(left, status, 24);

        connectTile = Ui.tile(this, w.connectButton, "เชื่อมต่อ " + AdbManager.HOST + ":" + AdbManager.PORT, v -> connect());
        connectLabel = (TextView) connectTile.getChildAt(0);
        Ui.add(left, connectTile, 14);

        LinearLayout help = Ui.card(this);
        Ui.add(help, Ui.text(this, "วิธีใช้งาน", 18, Ui.ACCENT, true), 0);
        for (String line : w.instructions) Ui.add(help, Ui.text(this, line, 16, 0xEEFFFFFF, false), 6);
        Ui.add(help, Ui.text(this,
                "ถ้าเชื่อมต่อไม่ได้: เปิด Developer options → USB debugging และ ADB over network (พอร์ต 5555) หรือสั่งจากคอมครั้งเดียว: adb tcpip 5555",
                14, 0xAAFFFFFF, false), 12);
        Ui.add(left, help, 20);

        Ui.add(menuColumn, Ui.text(this, "เมนู", 18, Ui.ACCENT, true), 0);
        for (AppConfig.MenuEntry m : cfg.menus) {
            final AppConfig.MenuEntry entry = m;
            String sub = m.description + (m.requiresAdb ? "" : "  •  ไม่ต้องใช้ ADB");
            Ui.add(menuColumn, Ui.tile(this, m.title, sub, v -> openMenu(entry)), 12);
        }
        for (String p : cfg.validate()) {
            Ui.add(menuColumn, Ui.text(this, "⚠ " + p, 14, Ui.ERROR_RED, false), 8);
        }

        connectTile.requestFocus();
        connect(); // silent attempt: works instantly if this box already allowed the key
    }

    private void openMenu(AppConfig.MenuEntry entry) {
        Intent i = new Intent(this, MenuActivity.class);
        i.putExtra(MenuActivity.EXTRA_MENU_ID, entry.id);
        startActivity(i);
    }

    private void connect() {
        if (busy || cfg == null) return;
        busy = true;
        status.setText(cfg.welcome.checking);
        status.setTextColor(Ui.WAIT_AMBER);
        AdbManager.get(this).connect(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cfg != null && !busy && !AdbManager.get(this).isConnected()) {
            onFailed(null); // e.g. came back after a reboot command dropped the link
        }
    }

    @Override
    public void onStatus(AdbConnection.Status s) {
        if (cfg == null) return;
        switch (s) {
            case CONNECTING:
                status.setText(cfg.welcome.connecting);
                status.setTextColor(Ui.WAIT_AMBER);
                break;
            case WAITING_AUTH:
                status.setText(cfg.welcome.waiting + "\nกด \"Always allow\" แล้วกด Allow บนหน้าจอ");
                status.setTextColor(Ui.WAIT_AMBER);
                break;
            case CONNECTED:
                busy = false;
                status.setText("● " + cfg.welcome.connected);
                status.setTextColor(Ui.OK_GREEN);
                connectLabel.setText("เชื่อมต่อแล้ว");
                if (connectTile.hasFocus() && menuColumn.getChildCount() > 1) menuColumn.getChildAt(1).requestFocus();
                grantSelfIfNeeded();
                break;
        }
    }

    /** Once ADB works, give the app WRITE_SECURE_SETTINGS so it can turn USB debugging on/off by itself. */
    private void grantSelfIfNeeded() {
        if (SecureSettings.hasPermission(this)) return;
        AdbManager.get(this).run(SecureSettings.grantCommand(this), (ok, disconnected, output) -> {
            if (SecureSettings.hasPermission(this)) {
                Toast.makeText(this, "ได้สิทธิ์เปิด/ปิด USB Debugging แล้ว", Toast.LENGTH_LONG).show();
            } else {
                Ui.message(this, "ขอสิทธิ์ไม่สำเร็จ",
                        "กล่องนี้ไม่ยอมให้สิทธิ์ WRITE_SECURE_SETTINGS จึงยังใช้เมนูปิด USB Debugging ไม่ได้\n\n"
                                + (output == null ? "" : output.trim()));
            }
        });
    }

    @Override
    public void onFailed(String message) {
        busy = false;
        if (cfg == null) return;
        if (SecureSettings.adbEnabled(this) == 0) {
            message = "USB Debugging ปิดอยู่ เปิดได้ที่เมนู USB Debugging";
        }
        status.setText("● " + cfg.welcome.failed + (message == null ? "" : "\n" + message));
        status.setTextColor(Ui.ERROR_RED);
        connectLabel.setText(cfg.welcome.retryButton);
    }
}
