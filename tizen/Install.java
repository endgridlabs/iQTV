import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Installs EndGrid iQTV+ on a Samsung TV in Developer Mode, over your Wi-Fi, without Samsung's sdb. JDK only:
 *
 *   java tizen/Install.java 192.168.1.50              # install or update a signed iQTV-tizen.wgt, then open it
 *   java tizen/Install.java 192.168.1.50 --uninstall  # remove it
 *
 * First build and sign it: ./tizen/build.sh --sign <profile> (see docs/LG-SAMSUNG.md). Before the first install, on
 * the TV: open Apps, type 1 2 3 4 5 on the remote, turn Developer mode on with this computer's IP as Host PC IP, and
 * restart the TV.
 *
 * It speaks SDB (Samsung's ADB, port 26101) itself. Like Samsung's tools it pushes the package to the folder the TV
 * names in its capabilities and installs it with `0 vd_appinstall` (the command TizenBrew Installer uses on every
 * Tizen); `0 was_execute` opens it.
 */
public class Install {
    static final int PORT = 26101;
    static final int MAX_DATA = 4096;   // what this side announces; the TV keeps each packet within both sides' maximum
    static final String ON_TV = "Apps, Settings, EndGrid iQTV+, Delete";   // removing it on the TV itself

    public static void main(String[] args) throws Exception {
        String ip = null;
        boolean uninstall = false;
        for (String a : args) {
            if (a.equals("--uninstall")) uninstall = true;
            else if (!a.startsWith("--")) ip = a;
        }
        if (ip == null) fail("Usage: java tizen/Install.java <TV IP> [--uninstall]");
        Path root = Path.of("").toAbsolutePath();   // run from the repository: java tizen/Install.java
        if (!Files.exists(root.resolve("tizen/config.xml"))) fail("Run it from the EndGrid iQTV+ folder: java tizen/Install.java <TV IP>");
        Path wgt = root.resolve("iQTV-tizen.wgt");
        String manifest;
        if (uninstall) {
            // Removing needs only the app's ids: the manifest the package is built from has them, so no build is needed.
            manifest = Files.readString(root.resolve("tizen/config.xml"));
        } else {
            if (!Files.exists(wgt)) fail("Build and sign it first: ./tizen/build.sh --sign <profile>");
            try (ZipFile z = new ZipFile(wgt.toFile())) {
                if (z.getEntry("author-signature.xml") == null)
                    fail("iQTV-tizen.wgt isn't signed, and the TV only installs signed apps: ./tizen/build.sh --sign <profile>");
                manifest = new String(z.getInputStream(z.getEntry("config.xml")).readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        Matcher tag = Pattern.compile("<tizen:application[^>]*>").matcher(manifest);
        if (!tag.find()) fail("No <tizen:application> in config.xml.");
        String appId = attr(tag.group(), "id"), pkg = attr(tag.group(), "package");

        Sdb sdb;
        try {
            sdb = new Sdb(ip);
        } catch (IOException e) {
            fail("Couldn't reach the TV on port " + PORT + " (" + e.getMessage() + "). On the TV: Apps, 1 2 3 4 5, Developer mode "
                    + "on with Host PC IP " + thisComputer(ip) + ", then restart the TV.");
            return;
        }
        Map<String, String> caps = sdb.capabilities();
        String[] banner = sdb.banner.split(":");
        System.out.println("Connected: " + (banner.length >= 3 ? banner[2] : sdb.banner) + ", Tizen "
                + caps.getOrDefault("platform_version", "?") + ".");

        if (uninstall) {
            String out = sdb.shell("0 vd_appuninstall " + pkg, "spend time", 120).trim();
            // Some TVs' sdbd refuses this command and answers nothing.
            if (!out.contains("uninstall completed"))
                fail((out.isEmpty() ? "The TV didn't answer." : "The TV said:\n" + out) + "\nRemove it on the TV instead: " + ON_TV + ".");
            System.out.println("Removed.");
            return;
        }

        String remote = caps.getOrDefault("sdk_toolpath", "/home/owner/share/tmp/sdk_tools").replaceAll("/+$", "") + "/iQTV-tizen.wgt";
        System.out.println("Copying the app to the TV ...");
        sdb.push(remote, Files.readAllBytes(wgt));
        System.out.println("Installing ...");
        String out = sdb.shell("0 vd_appinstall " + pkg + " " + remote, "spend time", 120);
        // Only "install completed" counts: cmd_ret:0 comes after failures too.
        if (!out.contains("install completed")) {
            String hint = out.contains("different certificate")
                    ? "\nThe EndGrid iQTV+ on the TV was signed with another certificate, and the TV only updates it with the same one. "
                    + "Remove it on the TV (" + ON_TV + "), then run this again." : "";
            fail("The TV didn't finish installing. It said:\n" + out.trim() + hint);
        }
        sdb.shell("0 was_execute " + appId, "launched|spend time", 20);
        System.out.println("Installed and open on the TV.");
        System.out.println("Tip: turn on Autorun Last App in the TV's settings (General, Smart Features; where it is changes by year) "
                + "and the TV turns on into EndGrid iQTV+.");
    }

    static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]+)\"").matcher(tag);
        if (!m.find()) fail("config.xml: <tizen:application> has no " + name + ".");
        return m.group(1);
    }

    /** This computer's address on the TV's network (what Host PC IP must be). */
    static String thisComputer(String ip) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName(ip), PORT);
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            return "this computer's IP";
        }
    }

    static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }

    /**
     * The pieces of the SDB wire protocol this needs: ADB's framing (a 24-byte header whose checksum is a byte sum),
     * streams opened with OPEN, and every WRTE answered with OKAY before the next one.
     */
    static final class Sdb {
        static final int CNXN = cmd("CNXN"), OPEN = cmd("OPEN"), OKAY = cmd("OKAY"), WRTE = cmd("WRTE"), CLSE = cmd("CLSE");

        final Socket sock;
        final InputStream in;
        final OutputStream out;
        final String banner;
        final int max;
        int nextId = 1;
        final Map<Integer, Integer> okay = new HashMap<>();
        final Map<Integer, ByteArrayOutputStream> inbox = new HashMap<>();
        final Set<Integer> closed = new HashSet<>();

        static int cmd(String s) {
            return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).getInt();
        }

        Sdb(String ip) throws IOException {
            sock = new Socket();
            sock.connect(new InetSocketAddress(ip, PORT), 15000);
            sock.setSoTimeout(15000);
            in = sock.getInputStream();
            out = sock.getOutputStream();
            send(CNXN, 0x01000000, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            Packet p = read();
            if (p.cmd != CNXN) throw new IOException("the TV answered something else than CNXN");
            max = Math.min(p.arg1, MAX_DATA);
            banner = new String(p.data, StandardCharsets.UTF_8).replace("\0", "");
        }

        void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
            long sum = 0;
            for (byte b : data) sum += b & 0xFF;
            ByteBuffer h = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
            h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt((int) sum).putInt(cmd ^ 0xFFFFFFFF).put(data);
            out.write(h.array());
            out.flush();
        }

        static final class Packet {
            int cmd, arg0, arg1;
            byte[] data;
        }

        byte[] readN(int n) throws IOException {
            byte[] b = in.readNBytes(n);
            if (b.length < n) throw new IOException("the TV closed the connection");
            return b;
        }

        Packet read() throws IOException {
            ByteBuffer h = ByteBuffer.wrap(readN(24)).order(ByteOrder.LITTLE_ENDIAN);
            Packet p = new Packet();
            p.cmd = h.getInt();
            p.arg0 = h.getInt();
            p.arg1 = h.getInt();
            int n = h.getInt();
            p.data = n > 0 ? readN(n) : new byte[0];
            return p;
        }

        /** Reads one packet and files it under its stream: OKAY, data (answered with OKAY) or close. */
        void pump() throws IOException {
            Packet p = read();
            int remote = p.arg0, local = p.arg1;
            if (p.cmd == OKAY) {
                okay.put(local, remote);
            } else if (p.cmd == WRTE) {
                inbox.computeIfAbsent(local, k -> new ByteArrayOutputStream()).write(p.data);
                send(OKAY, local, remote, new byte[0]);
            } else if (p.cmd == CLSE) {
                closed.add(local);
            }
        }

        int[] open(String service) throws IOException {
            int local = nextId++;
            send(OPEN, local, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
            while (!okay.containsKey(local)) {
                if (closed.contains(local)) throw new IOException("the TV refused " + service);
                pump();
            }
            return new int[] {local, okay.remove(local)};
        }

        void write(int[] stream, byte[] data) throws IOException {
            send(WRTE, stream[0], stream[1], data);
            while (!okay.containsKey(stream[0])) {
                if (closed.contains(stream[0])) throw new IOException("the TV closed the stream");
                pump();
            }
            okay.remove(stream[0]);
        }

        /** The stream's bytes until done(text) is true, the TV closes it, or time runs out. */
        byte[] readUntil(int[] stream, Predicate<byte[]> done, int seconds) throws IOException {
            int local = stream[0];
            long end = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < end && !closed.contains(local) && !done.test(bytes(local))) {
                sock.setSoTimeout((int) Math.max(100, end - System.currentTimeMillis()));
                try {
                    pump();
                } catch (SocketTimeoutException e) {
                    break;
                }
            }
            sock.setSoTimeout(15000);
            byte[] b = bytes(local);
            inbox.remove(local);
            return b;
        }

        byte[] bytes(int local) {
            ByteArrayOutputStream b = inbox.get(local);
            return b == null ? new byte[0] : b.toByteArray();
        }

        String shell(String command, String until, int seconds) throws IOException {
            Pattern p = Pattern.compile(until);
            byte[] out = readUntil(open("shell:" + command), b -> p.matcher(new String(b, StandardCharsets.UTF_8)).find(), seconds);
            return new String(out, StandardCharsets.UTF_8);
        }

        Map<String, String> capabilities() throws IOException {
            String text = new String(readUntil(open("capability:"), b -> false, 5), StandardCharsets.UTF_8);
            Map<String, String> caps = new HashMap<>();
            for (String line : text.split("\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) caps.put(line.substring(0, colon), line.substring(colon + 1).trim());
            }
            return caps;
        }

        /** Copies data to path on the TV (sync SEND), then checks the TV's answer. */
        void push(String path, byte[] data) throws IOException {
            int[] stream = open("sync:");
            byte[] spec = (path + "," + 0100644).getBytes(StandardCharsets.UTF_8);
            write(stream, concat("SEND", spec.length, spec));
            int step = max - 8;
            for (int i = 0; i < data.length; i += step) {
                int n = Math.min(step, data.length - i);
                byte[] part = new byte[n];
                System.arraycopy(data, i, part, 0, n);
                write(stream, concat("DATA", n, part));
            }
            write(stream, concat("DONE", (int) (System.currentTimeMillis() / 1000), new byte[0]));
            byte[] answer = readUntil(stream, b -> b.length >= 8, 60);
            if (answer.length < 4 || !new String(answer, 0, 4, StandardCharsets.US_ASCII).equals("OKAY")) {
                String why = answer.length > 8 ? new String(answer, 8, answer.length - 8, StandardCharsets.UTF_8) : "";
                throw new IOException("the TV didn't take the file: " + why);
            }
            send(WRTE, stream[0], stream[1], concat("QUIT", 0, new byte[0]));
        }

        static byte[] concat(String id, int value, byte[] data) {
            ByteBuffer b = ByteBuffer.allocate(8 + data.length).order(ByteOrder.LITTLE_ENDIAN);
            b.put(id.getBytes(StandardCharsets.US_ASCII)).putInt(value).put(data);
            return b.array();
        }
    }
}
