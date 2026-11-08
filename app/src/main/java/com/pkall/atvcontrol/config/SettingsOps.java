package com.pkall.atvcontrol.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Recognises commands that only do "settings put|delete global|secure KEY [VALUE]".
 * Those can be applied by the app itself (with WRITE_SECURE_SETTINGS) without ADB,
 * which is what lets the app turn USB debugging back ON after it was turned off.
 */
public final class SettingsOps {

    public static final class Op {
        public final String namespace; // "global" or "secure"
        public final String key;
        public final String value;     // null = delete

        Op(String namespace, String key, String value) {
            this.namespace = namespace;
            this.key = key;
            this.value = value;
        }
    }

    private SettingsOps() {
    }

    /** Returns the list of operations, or null if the command contains anything else. */
    public static List<Op> parse(String command) {
        if (command == null) return null;
        List<Op> ops = new ArrayList<>();
        for (String part : command.split(";")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            String[] t = p.split("\\s+");
            if (t.length < 4 || !t[0].equals("settings")) return null;
            String ns = t[2];
            if (!ns.equals("global") && !ns.equals("secure")) return null; // "system" needs another permission
            if (t[1].equals("put") && t.length == 5) {
                ops.add(new Op(ns, t[3], t[4]));
            } else if (t[1].equals("delete") && t.length == 4) {
                ops.add(new Op(ns, t[3], null));
            } else {
                return null;
            }
        }
        return ops.isEmpty() ? null : ops;
    }

    /** True if running this command would switch ADB off (the app's own link). */
    public static boolean turnsAdbOff(String command) {
        List<Op> ops = parse(command);
        if (ops == null) return command != null && command.matches(".*adb_enabled\\s+0.*");
        for (Op o : ops) if (o.key.equals("adb_enabled") && "0".equals(o.value)) return true;
        return false;
    }
}
