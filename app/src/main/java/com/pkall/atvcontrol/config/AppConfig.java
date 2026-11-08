package com.pkall.atvcontrol.config;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Everything the app shows comes from these XML files (main.xml, welcome.xml, commands.xml, menu files).
 * Pure Java DOM parsing, so it is testable on a PC.
 */
public final class AppConfig implements Serializable {

    public interface Source {
        InputStream open(String fileName) throws IOException;
    }

    public String title = "ATV Control";
    public final Welcome welcome = new Welcome();
    public final Map<String, String> commands = new LinkedHashMap<>();
    public final List<MenuEntry> menus = new ArrayList<>();

    public static final class Welcome implements Serializable {
        public String title = "ATV Control";
        public String description = "";
        public final List<String> instructions = new ArrayList<>();
        public String checking = "Checking ADB...";
        public String connecting = "Connecting...";
        public String waiting = "Waiting for authorization...";
        public String connected = "ADB CONNECTED";
        public String failed = "ADB connection failed";
        public String connectButton = "CONNECT";
        public String retryButton = "RETRY";
    }

    public static final class MenuEntry implements Serializable {
        public String id, title, description, file;
        public boolean enabled = true;
        public boolean requiresAdb = true;
        public Menu menu; // parsed content of file
    }

    public static final class Menu implements Serializable {
        public String id, title = "", description = "";
        /** Optional live status block, e.g. status="adb" shows USB debugging state. */
        public String status;
        public final List<String> usage = new ArrayList<>();
        public final List<Item> items = new ArrayList<>();
    }

    public static final class Item implements Serializable {
        public String id, title, description;
        /** "command" (default, uses action), "url", "text" */
        public String type = "command";
        public String action;
        public String value;
        public boolean confirm = true;
        /** Optional extra warning shown in the confirm dialog. */
        public String warning;
    }

    // ---------------------------------------------------------------------------------------

    public static AppConfig load(Source src) throws Exception {
        AppConfig cfg = new AppConfig();
        Element main = parse(src, "main.xml");
        cfg.title = text(main, "title", cfg.title);

        Element welcomeRef = child(main, "welcome");
        cfg.parseWelcome(parse(src, attr(welcomeRef, "file", "welcome.xml")));

        Element commandsRef = child(main, "commands");
        cfg.parseCommands(parse(src, attr(commandsRef, "file", "commands.xml")));

        for (Element m : children(main, "menu")) {
            MenuEntry e = new MenuEntry();
            e.id = attr(m, "id", "");
            e.title = attr(m, "title", e.id);
            e.description = attr(m, "description", "");
            e.file = attr(m, "file", e.id + ".xml");
            e.enabled = !"false".equalsIgnoreCase(attr(m, "enabled", "true"));
            e.requiresAdb = !"false".equalsIgnoreCase(attr(m, "requiresAdb", "true"));
            if (!e.enabled) continue;
            e.menu = parseMenu(parse(src, e.file));
            cfg.menus.add(e);
        }
        return cfg;
    }

    /** Lists problems such as menu items whose action has no command (the original reboot.xml had one). */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        for (MenuEntry e : menus) {
            for (Item it : e.menu.items) {
                if ("command".equals(it.type) && !commands.containsKey(it.action)) {
                    problems.add(e.file + ": \"" + it.title + "\" ใช้ action=" + it.action + " ซึ่งไม่มีใน commands.xml");
                }
            }
        }
        return problems;
    }

    private void parseWelcome(Element w) {
        welcome.title = text(w, "title", welcome.title);
        welcome.description = text(w, "description", "");
        Element ins = child(w, "instructions");
        if (ins != null) for (Element l : children(ins, "line")) welcome.instructions.add(clean(l.getTextContent()));
        Element st = child(w, "status");
        if (st != null) {
            welcome.checking = text(st, "checking", welcome.checking);
            welcome.connecting = text(st, "connecting", welcome.connecting);
            welcome.waiting = text(st, "waiting", welcome.waiting);
            welcome.connected = text(st, "connected", welcome.connected);
            welcome.failed = text(st, "failed", welcome.failed);
        }
        welcome.connectButton = text(w, "connectButton", welcome.connectButton);
        welcome.retryButton = text(w, "retryButton", welcome.retryButton);
    }

    private void parseCommands(Element root) {
        for (Element c : children(root, "command")) {
            // multi-line commands become "a; b" so they run as one shell line
            commands.put(attr(c, "id", ""), clean(c.getTextContent()).replace("\n", "; "));
        }
    }

    static Menu parseMenu(Element root) {
        Menu m = new Menu();
        m.id = attr(root, "id", "");
        m.status = attr(root, "status", null);
        m.title = text(root, "title", "");
        m.description = text(root, "description", "");
        Element usage = child(root, "usage");
        if (usage != null) for (Element l : children(usage, "line")) m.usage.add(clean(l.getTextContent()));
        for (Element i : children(root, "item")) {
            Item it = new Item();
            it.id = attr(i, "id", "");
            it.title = attr(i, "title", it.id);
            it.description = attr(i, "description", "");
            it.type = attr(i, "type", "command");
            if ("internal".equals(it.type)) it.type = "text";
            it.action = attr(i, "action", null);
            it.value = attr(i, "value", null);
            it.confirm = !"false".equalsIgnoreCase(attr(i, "confirm", "true"));
            it.warning = attr(i, "warning", null);
            m.items.add(it);
        }
        return m;
    }

    // ---- small DOM helpers -------------------------------------------------------------

    private static Element parse(Source src, String name) throws Exception {
        try (InputStream in = src.open(name)) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            DocumentBuilder b = f.newDocumentBuilder();
            Document d = b.parse(in);
            return d.getDocumentElement();
        } catch (Exception e) {
            throw new Exception("อ่านไฟล์ " + name + " ไม่ได้: " + e.getMessage(), e);
        }
    }

    private static Element child(Element parent, String tag) {
        if (parent == null) return null;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element && tag.equals(((Element) n).getTagName())) return (Element) n;
        }
        return null;
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element && tag.equals(((Element) n).getTagName())) out.add((Element) n);
        }
        return out;
    }

    private static String text(Element parent, String tag, String def) {
        Element c = child(parent, tag);
        return c == null ? def : clean(c.getTextContent());
    }

    private static String attr(Element e, String name, String def) {
        if (e == null || !e.hasAttribute(name)) return def;
        return e.getAttribute(name).trim();
    }

    /** Collapse the indentation inside XML text blocks, keep line breaks. */
    static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        return sb.toString();
    }
}
