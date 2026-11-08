package com.pkall.atvcontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.pkall.atvcontrol.config.AppConfig;
import com.pkall.atvcontrol.config.SettingsOps;

import java.util.List;

/** One menu (USB / DNS / Reboot / Support...) built from its XML file. */
public class MenuActivity extends Activity {

    static final String EXTRA_MENU_ID = "menu_id";

    private AppConfig cfg;
    private AppConfig.MenuEntry entry;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            cfg = ConfigRepository.load(this); // already cached by MainActivity, so this is instant
        } catch (Exception e) {
            finish();
            return;
        }
        String id = getIntent().getStringExtra(EXTRA_MENU_ID);
        for (AppConfig.MenuEntry m : cfg.menus) if (m.id.equals(id)) entry = m;
        if (entry == null) {
            finish();
            return;
        }

        LinearLayout[] cols = Ui.screen(this);
        AppConfig.Menu menu = entry.menu;
        LinearLayout left = cols[0];
        Ui.add(left, Ui.text(this, menu.title.isEmpty() ? entry.title : menu.title, 38, 0xFFFFFFFF, true), 0);
        Ui.add(left, Ui.text(this, menu.description, 18, 0xDDFFFFFF, false), 6);

        if ("adb".equals(menu.status)) {
            statusView = Ui.text(this, "", 20, 0xFFFFFFFF, true);
            LinearLayout card = Ui.card(this);
            card.addView(statusView);
            Ui.add(left, card, 18);
        }
        if (!menu.usage.isEmpty()) {
            LinearLayout card = Ui.card(this);
            Ui.add(card, Ui.text(this, "วิธีใช้งาน", 18, Ui.ACCENT, true), 0);
            for (String line : menu.usage) Ui.add(card, Ui.text(this, line, 16, 0xEEFFFFFF, false), 6);
            Ui.add(left, card, 18);
        }
        boolean allDirect = SecureSettings.hasPermission(this);
        for (AppConfig.Item it : menu.items) {
            if ("command".equals(it.type) && SettingsOps.parse(cfg.commands.get(it.action)) == null) allDirect = false;
        }
        if (entry.requiresAdb && !allDirect && !AdbManager.get(this).isConnected()) {
            Ui.add(left, Ui.text(this, "● ยังไม่ได้เชื่อมต่อ ADB: กลับไปหน้าแรกแล้วกด CONNECT ก่อน", 16, Ui.ERROR_RED, true), 16);
        }

        LinearLayout right = cols[1];
        boolean first = true;
        for (AppConfig.Item it : menu.items) {
            final AppConfig.Item item = it;
            LinearLayout tile = Ui.tile(this, it.title, it.description, v -> onItem(item));
            Ui.add(right, tile, first ? 0 : 12);
            if (first) tile.requestFocus();
            first = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        if (statusView == null) return;
        int adb = SecureSettings.adbEnabled(this);
        boolean perm = SecureSettings.hasPermission(this);
        String a = adb == 1 ? "เปิดอยู่" : adb == 0 ? "ปิดอยู่" : "ไม่ทราบ";
        String p = perm ? "พร้อม (เปิด/ปิดได้เลย)" : "ยังไม่มี: กด CONNECT ที่หน้าแรกก่อน 1 ครั้ง";
        statusView.setText("USB Debugging: " + a + "\nสิทธิ์ของแอป: " + p);
        statusView.setTextColor(perm ? Ui.OK_GREEN : Ui.WAIT_AMBER);
    }

    private void onItem(AppConfig.Item item) {
        if ("text".equals(item.type)) {
            Ui.message(this, item.title, item.value == null ? item.description : item.value);
            return;
        }
        if ("url".equals(item.type)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.value)));
            } catch (ActivityNotFoundException e) {
                Ui.message(this, item.title, "กล่องนี้ไม่มีแอปเปิดลิงก์\n" + item.value);
            }
            return;
        }
        // command
        final String cmd = cfg.commands.get(item.action);
        if (cmd == null) {
            Ui.message(this, item.title, "ไม่พบคำสั่ง " + item.action + " ใน commands.xml");
            return;
        }
        final List<SettingsOps.Op> ops = SettingsOps.parse(cmd);
        final boolean direct = ops != null && SecureSettings.hasPermission(this);
        final boolean adb = AdbManager.get(this).isConnected();

        // Safety: never switch ADB off unless the app can switch it back on by itself.
        if (SettingsOps.turnsAdbOff(cmd) && !direct) {
            Ui.message(this, "ยังปิดไม่ได้",
                    "แอปยังไม่มีสิทธิ์เปิด USB Debugging กลับเอง ถ้าปิดตอนนี้อาจเปิดกลับไม่ได้\n\n"
                            + "กลับไปหน้าแรก กด CONNECT ให้ขึ้น ADB CONNECTED แล้วแอปจะขอสิทธิ์ให้อัตโนมัติ");
            return;
        }
        if (!direct && !adb) {
            Ui.message(this, item.title, "ยังไม่ได้เชื่อมต่อ ADB\nกลับไปหน้าแรกแล้วกด CONNECT ก่อน");
            return;
        }
        if (!item.confirm) {
            execute(item, cmd, direct ? ops : null);
            return;
        }
        StringBuilder msg = new StringBuilder(item.description);
        if (item.warning != null) msg.append("\n\n⚠ ").append(item.warning);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("ยืนยัน: " + item.title)
                .setMessage(msg.toString())
                .setNegativeButton("ยกเลิก", null)
                .setPositiveButton("ยืนยัน", (d, w) -> execute(item, cmd, direct ? ops : null))
                .show();
        // start on "Cancel" so a double-press of OK on the remote doesn't fire the command by accident
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }

    /** directOps != null: apply settings ourselves (no ADB needed). Otherwise run over ADB. */
    private void execute(AppConfig.Item item, String cmd, List<SettingsOps.Op> directOps) {
        if (directOps != null) {
            try {
                SecureSettings.apply(this, directOps);
                if (SettingsOps.turnsAdbOff(cmd)) AdbManager.get(this).disconnect();
                Toast.makeText(this, item.title + ": สำเร็จ", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Ui.message(this, "ไม่สำเร็จ", String.valueOf(e.getMessage()));
            }
            refreshStatus();
            return;
        }
        final boolean isReboot = cmd.trim().startsWith("reboot");
        @SuppressWarnings("deprecation")
        final ProgressDialog pd = ProgressDialog.show(this, item.title, "กำลังทำงาน...", true, false);
        AdbManager.get(this).run(cmd, (ok, disconnected, output) -> {
            if (isFinishing()) return;
            pd.dismiss();
            if (ok && (disconnected || isReboot)) {
                Toast.makeText(this, "สั่ง " + item.title + " แล้ว เครื่องกำลังรีสตาร์ท", Toast.LENGTH_LONG).show();
            } else if (ok) {
                String out = output == null ? "" : output.trim();
                Ui.message(this, "สำเร็จ", item.title + (out.isEmpty() ? "" : "\n\n" + out));
            } else {
                Ui.message(this, "ไม่สำเร็จ", output);
            }
            refreshStatus();
        });
    }
}
