import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs the LG and Samsung web app (a folder made by tools/webapp.sh) without LG's or Samsung's tools. JDK only:
 *
 *   java tools/WebPack.java ipk  <app folder> <out.ipk> <app id> <version>   # LG webOS, as ares-package makes it
 *   java tools/WebPack.java wgt  <app folder> <out.wgt>                      # Samsung Tizen, not signed yet
 *   java tools/WebPack.java icons <repo>                                      # the TV icons from docs/brand/iqtv-logo.png
 *
 * An .ipk is an ar archive of debian-binary, control.tar.gz and data.tar.gz, with the app under
 * usr/palm/applications/<id>/ and its packageinfo.json under usr/palm/packages/<id>/, every file owned by root.
 * A .wgt is a zip of the app with config.xml at its root.
 */
public class WebPack {
    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "";
        switch (cmd) {
            case "ipk": ipk(Path.of(args[1]), Path.of(args[2]), args[3], args[4]); break;
            case "wgt": wgt(Path.of(args[1]), Path.of(args[2])); break;
            case "icons": icons(new File(args[1])); break;
            default:
                System.err.println("usage: java tools/WebPack.java ipk|wgt|icons ...");
                System.exit(2);
        }
    }

    /** The app's files, relative paths with "/" in sorted order. */
    static List<String> files(Path dir) throws IOException {
        TreeSet<String> out = new TreeSet<>();
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(Files::isRegularFile).forEach(p -> out.add(dir.relativize(p).toString().replace('\\', '/')));
        }
        return new ArrayList<>(out);
    }

    // MARK: - LG

    static void ipk(Path dir, Path out, String id, String version) throws IOException {
        long now = System.currentTimeMillis() / 1000;
        String base = "usr/palm/applications/" + id;
        Tar data = new Tar(now);
        for (String d : new String[] {"usr", "usr/palm", "usr/palm/applications", base}) data.dir(d);
        TreeSet<String> dirs = new TreeSet<>();
        long size = 0;
        for (String f : files(dir)) {
            int slash = f.lastIndexOf('/');
            if (slash > 0) {
                // Each folder once, its parents first (symbols/, fonts/, icons/ are one level deep).
                String parent = f.substring(0, slash), sofar = "";
                for (String part : parent.split("/")) {
                    sofar = sofar.isEmpty() ? part : sofar + "/" + part;
                    if (dirs.add(sofar)) data.dir(base + "/" + sofar);
                }
            }
            byte[] b = Files.readAllBytes(dir.resolve(f));
            size += b.length;
            data.file(base + "/" + f, b);
        }
        byte[] pkg = ("{\n  \"id\": \"" + id + "\",\n  \"version\": \"" + version + "\",\n  \"app\": \"" + id + "\"\n}")
                .getBytes(StandardCharsets.UTF_8);
        size += pkg.length;
        data.dir("usr/palm/packages");
        data.dir("usr/palm/packages/" + id);
        data.file("usr/palm/packages/" + id + "/packageinfo.json", pkg);

        String control = "Package: " + id + "\nVersion: " + version + "\nSection: misc\nPriority: optional\n"
                + "Architecture: all\nInstalled-Size: " + size + "\nMaintainer: EndGrid Labs <https://endgrid.in>\n"
                + "Description: EndGrid iQTV+, a cinematic, ad-free home for your TV.\n"
                + "webOS-Package-Format-Version: 2\nwebOS-Packager-Version: 1.0.0\n";
        Tar ctl = new Tar(now);
        ctl.file("control", control.getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream ar = new ByteArrayOutputStream();
        ar.write("!<arch>\n".getBytes(StandardCharsets.US_ASCII));
        arMember(ar, "debian-binary", "2.0\n".getBytes(StandardCharsets.US_ASCII), now);
        arMember(ar, "control.tar.gz", ctl.gzip(), now);
        arMember(ar, "data.tar.gz", data.gzip(), now);
        Files.write(out, ar.toByteArray());
    }

    static void arMember(ByteArrayOutputStream ar, String name, byte[] data, long mtime) throws IOException {
        String header = String.format("%-16s%-12d%-6d%-6d%-8s%-10d`\n", name, mtime, 0, 0, "100644", data.length);
        ar.write(header.getBytes(StandardCharsets.US_ASCII));
        ar.write(data);
        if (data.length % 2 == 1) ar.write('\n');
    }

    /** A ustar archive in memory: folders 0755, files 0644, owned by root. */
    static final class Tar {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final long mtime;

        Tar(long mtime) { this.mtime = mtime; }

        void dir(String name) throws IOException { entry(name + "/", null); }

        void file(String name, byte[] data) throws IOException { entry(name, data); }

        void entry(String name, byte[] data) throws IOException {
            byte[] h = new byte[512];
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            if (n.length > 100) {
                // Longer paths: the folder part goes in the 155-byte prefix field.
                int cut = name.lastIndexOf('/', name.length() - 2);
                byte[] prefix = name.substring(0, cut).getBytes(StandardCharsets.UTF_8);
                n = name.substring(cut + 1).getBytes(StandardCharsets.UTF_8);
                if (n.length > 100 || prefix.length > 155) throw new IOException("path too long for tar: " + name);
                System.arraycopy(prefix, 0, h, 345, prefix.length);
            }
            System.arraycopy(n, 0, h, 0, n.length);
            octal(h, 100, 8, data == null ? 0755 : 0644);
            octal(h, 108, 8, 0);
            octal(h, 116, 8, 0);
            octal(h, 124, 12, data == null ? 0 : data.length);
            octal(h, 136, 12, mtime);
            h[156] = (byte) (data == null ? '5' : '0');
            System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 6);
            h[263] = '0';
            h[264] = '0';
            System.arraycopy("root".getBytes(StandardCharsets.US_ASCII), 0, h, 265, 4);
            System.arraycopy("root".getBytes(StandardCharsets.US_ASCII), 0, h, 297, 4);
            for (int i = 148; i < 156; i++) h[i] = ' ';
            long sum = 0;
            for (byte b : h) sum += b & 0xFF;
            byte[] c = String.format("%06o", sum).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(c, 0, h, 148, 6);
            h[154] = 0;
            h[155] = ' ';
            out.write(h);
            if (data != null) {
                out.write(data);
                int pad = (512 - data.length % 512) % 512;
                out.write(new byte[pad]);
            }
        }

        static void octal(byte[] h, int at, int len, long value) {
            byte[] v = String.format("%0" + (len - 1) + "o", value).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(v, 0, h, at, len - 1);
            h[at + len - 1] = 0;
        }

        byte[] gzip() throws IOException {
            out.write(new byte[1024]);   // two empty blocks end the archive
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (GZIPOutputStream z = new GZIPOutputStream(b)) {
                z.write(out.toByteArray());
            }
            return b.toByteArray();
        }
    }

    // MARK: - Samsung

    static void wgt(Path dir, Path out) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(out))) {
            for (String f : files(dir)) {
                z.putNextEntry(new ZipEntry(f));
                z.write(Files.readAllBytes(dir.resolve(f)));
                z.closeEntry();
            }
        }
    }

    // MARK: - Icons

    /** LG's icon (80 px) and large icon (130 px), Samsung's (117 px), from the logo. */
    static void icons(File repo) throws IOException {
        BufferedImage logo = ImageIO.read(new File(repo, "docs/brand/iqtv-logo.png"));
        ImageIO.write(scaled(logo, 80), "png", new File(repo, "webos/icon.png"));
        ImageIO.write(scaled(logo, 130), "png", new File(repo, "webos/largeIcon.png"));
        ImageIO.write(scaled(logo, 117), "png", new File(repo, "tizen/icon.png"));
        System.out.println("icons written");
    }

    /** Halving steps, then one bicubic step: a smooth downscale from a large logo. */
    static BufferedImage scaled(BufferedImage src, int size) {
        BufferedImage img = src;
        int w = src.getWidth();
        while (w / 2 >= size) {
            w /= 2;
            img = draw(img, w);
        }
        return draw(img, size);
    }

    static BufferedImage draw(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }
}
