package com.pkall.atvcontrol.adb;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal ADB client over TCP (talks to the box's own adbd, normally 127.0.0.1:5555).
 * Only what this app needs: connect + RSA auth + run one shell command at a time.
 * Pure Java so it can be tested on a PC.
 */
public final class AdbConnection implements Closeable {

    public interface Listener {
        void onStatus(Status status);
    }

    public enum Status { CONNECTING, WAITING_AUTH, CONNECTED }

    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;

    static final int AUTH_TOKEN = 1;
    static final int AUTH_SIGNATURE = 2;
    static final int AUTH_RSAPUBLICKEY = 3;

    private static final int VERSION = 0x01000000;
    private static final int MAX_DATA = 256 * 1024;

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 8000;
    /** Time the user has to press "Allow" on the USB debugging prompt. */
    private static final int AUTH_PROMPT_TIMEOUT_MS = 60000;
    private static final int COMMAND_TIMEOUT_MS = 20000;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;
    private volatile boolean connected;

    private AdbConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    public static AdbConnection open(String host, int port, AdbCrypto crypto, String keyName,
                                     Listener listener) throws Exception {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        try {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            closeQuietly(s);
            throw new IOException("เชื่อมต่อ " + host + ":" + port + " ไม่ได้ (ยังไม่ได้เปิด ADB ทางเครือข่าย?)", e);
        }
        AdbConnection c = new AdbConnection(s);
        try {
            c.handshake(crypto, keyName, listener);
        } catch (Exception e) {
            c.close();
            throw e;
        }
        return c;
    }

    private void handshake(AdbCrypto crypto, String keyName, Listener listener) throws Exception {
        notify(listener, Status.CONNECTING);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        send(A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));

        boolean sentSignature = false;
        boolean sentPublicKey = false;
        while (true) {
            Message m;
            try {
                m = read();
            } catch (SocketTimeoutException e) {
                if (sentPublicKey) {
                    throw new IOException("ไม่ได้กด Allow ภายในเวลาที่กำหนด", e);
                }
                throw new IOException("adbd ไม่ตอบกลับ", e);
            }
            if (m.command == A_CNXN) {
                connected = true;
                socket.setSoTimeout(COMMAND_TIMEOUT_MS);
                notify(listener, Status.CONNECTED);
                return;
            }
            if (m.command == A_AUTH && m.arg0 == AUTH_TOKEN) {
                if (!sentSignature) {
                    send(A_AUTH, AUTH_SIGNATURE, 0, crypto.signToken(m.data));
                    sentSignature = true;
                } else if (!sentPublicKey) {
                    // Key not trusted yet -> adbd shows "Allow USB debugging?" on the TV.
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, crypto.adbPublicKeyPayload(keyName));
                    sentPublicKey = true;
                    socket.setSoTimeout(AUTH_PROMPT_TIMEOUT_MS);
                    notify(listener, Status.WAITING_AUTH);
                } else {
                    throw new IOException("การอนุญาต ADB ถูกปฏิเสธ");
                }
            }
            // anything else during handshake is ignored
        }
    }

    public boolean isConnected() {
        return connected && !socket.isClosed();
    }

    /**
     * Run "shell:<cmd>" and return its output.
     * If the device drops the connection mid-command (e.g. reboot) a DisconnectedException is thrown.
     */
    public synchronized String shell(String cmd) throws IOException {
        if (!isConnected()) throw new IOException("ยังไม่ได้เชื่อมต่อ ADB");
        int localId = nextLocalId++;
        int remoteId = 0;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            send(A_OPEN, localId, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
            while (true) {
                Message m = read();
                if (m.arg1 != localId) continue; // not our stream
                if (m.command == A_OKAY) {
                    remoteId = m.arg0;
                } else if (m.command == A_WRTE) {
                    remoteId = m.arg0;
                    output.write(m.data);
                    send(A_OKAY, localId, remoteId, null);
                } else if (m.command == A_CLSE) {
                    if (remoteId != 0) send(A_CLSE, localId, remoteId, null);
                    break;
                }
            }
        } catch (EOFException | java.net.SocketException e) {
            connected = false;
            throw new DisconnectedException(output.toString("UTF-8"), e);
        }
        return output.toString("UTF-8");
    }

    /** The device closed the socket (expected after reboot commands). */
    public static final class DisconnectedException extends IOException {
        public final String partialOutput;

        DisconnectedException(String partialOutput, Throwable cause) {
            super("การเชื่อมต่อ ADB ถูกตัด", cause);
            this.partialOutput = partialOutput;
        }
    }

    // ---- wire format -------------------------------------------------------------------

    static final class Message {
        final int command, arg0, arg1;
        final byte[] data;

        Message(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }

    static byte[] encode(int command, int arg0, int arg1, byte[] data) {
        int len = data == null ? 0 : data.length;
        ByteBuffer bb = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(command).putInt(arg0).putInt(arg1).putInt(len).putInt(checksum(data)).putInt(~command);
        if (len > 0) bb.put(data);
        return bb.array();
    }

    static int checksum(byte[] data) {
        int sum = 0;
        if (data != null) for (byte b : data) sum += b & 0xff;
        return sum;
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        out.write(encode(command, arg0, arg1, data));
        out.flush();
    }

    private Message read() throws IOException {
        return readMessage(in);
    }

    static Message readMessage(InputStream stream) throws IOException {
        DataInputStream din = stream instanceof DataInputStream ? (DataInputStream) stream : new DataInputStream(stream);
        byte[] header = new byte[24];
        din.readFully(header);
        ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = bb.getInt();
        int arg0 = bb.getInt();
        int arg1 = bb.getInt();
        int len = bb.getInt();
        bb.getInt(); // checksum (not verified; newer adbd sends 0)
        int magic = bb.getInt();
        if (magic != ~command) throw new IOException("ข้อมูล ADB ผิดรูปแบบ");
        if (len < 0 || len > 1024 * 1024) throw new IOException("ขนาดข้อมูล ADB ผิดปกติ");
        byte[] data = new byte[len];
        din.readFully(data);
        return new Message(command, arg0, arg1, data);
    }

    private static void notify(Listener l, Status s) {
        if (l != null) l.onStatus(s);
    }

    @Override
    public void close() {
        connected = false;
        closeQuietly(socket);
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
