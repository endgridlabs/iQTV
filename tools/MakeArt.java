import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Draws the EndGrid iQTV+ icon and TV banner from the logo (docs/brand/iqtv-logo.png), and the white glyphs the Top
 * Shelf needs (mic, search, not_interested). Usage (JDK only): java tools/MakeArt.java .
 * (The About page shows the logo itself, as app/res/drawable-nodpi/logo.webp.)
 */
public class MakeArt {
    static final Color NAVY_TOP = new Color(0x0A, 0x1A, 0x44), NAVY_BOTTOM = new Color(0x03, 0x07, 0x18);
    static final Color BLUE = new Color(0x2E, 0x9B, 0xFF), ORANGE = new Color(0xFF, 0x7A, 0x1A);   // the logo's two ends

    public static void main(String[] args) throws Exception {
        File repo = new File(args[0]);
        Font inter = Font.createFont(Font.TRUETYPE_FONT, new File(repo, "app/assets/fonts/InterVariable.ttf"));
        BufferedImage logo = ImageIO.read(new File(repo, "docs/brand/iqtv-logo.png"));
        File res = new File(repo, "app/res/drawable-nodpi"), sym = new File(repo, "app/assets/symbols");
        ImageIO.write(scaled(logo, 192), "png", new File(res, "icon.png"));
        ImageIO.write(banner(640, 360, logo, inter), "png", new File(res, "banner.png"));
        ImageIO.write(mic(), "png", new File(sym, "mic.png"));
        ImageIO.write(search(), "png", new File(sym, "search.png"));
        ImageIO.write(notInterested(), "png", new File(sym, "not_interested.png"));
        System.out.println("assets written");
    }

    static Graphics2D g(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    /** The logo at s × s, halved step by step (one big jump would alias its thin edges). */
    static BufferedImage scaled(BufferedImage src, int s) {
        BufferedImage cur = src;
        int size = Math.max(src.getWidth(), src.getHeight());
        while (size / 2 >= s) {
            size /= 2;
            cur = draw(cur, size);
        }
        return draw(cur, s);
    }

    private static BufferedImage draw(BufferedImage src, int s) {
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g(out);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, s, s, null);
        g.dispose();
        return out;
    }

    /** The TV banner (Android TV's 16:9 app card): the logo on deep navy, "EndGrid" and "iQTV+" beside it. */
    static BufferedImage banner(int w, int h, BufferedImage logo, Font inter) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g(img);
        g.setPaint(new GradientPaint(0, 0, NAVY_TOP, w, h, NAVY_BOTTOM));
        g.fillRect(0, 0, w, h);
        // The logo's own light: blue at the left, a warm glow behind its play shape.
        g.setPaint(new RadialGradientPaint(new Point2D.Float(w * 0.24f, h * 0.5f), h * 0.75f,
                new float[] {0, 1}, new Color[] {new Color(0x2E, 0x6B, 0xFF, 70), new Color(0, 0, 0, 0)}));
        g.fillRect(0, 0, w, h);
        int m = Math.round(h * 0.66f);
        int lx = Math.round(w * 0.06f), ly = (h - m) / 2;
        g.drawImage(scaled(logo, m), lx, ly, null);
        float x = lx + m + w * 0.035f;
        g.setFont(inter.deriveFont(Font.BOLD, h * 0.17f));
        float base = h * 0.5f + g.getFontMetrics().getAscent() * 0.05f;
        g.setColor(Color.WHITE);
        g.drawString("EndGrid", x, base);
        g.setFont(inter.deriveFont(Font.BOLD, h * 0.13f));
        float tw = g.getFontMetrics().stringWidth("iQTV+");
        g.setPaint(new GradientPaint(x, 0, BLUE, x + tw, 0, ORANGE));
        g.drawString("iQTV+", x + 2, base + h * 0.16f);
        g.dispose();
        return img;
    }

    /** White on transparent, trimmed to the glyph like the other symbols. */
    static BufferedImage glyph(int w, int h, java.util.function.Consumer<Graphics2D> draw) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g(img);
        g.setColor(Color.WHITE);
        draw.accept(g);
        g.dispose();
        return img;
    }

    static BufferedImage mic() {
        return glyph(96, 140, g -> {
            g.fill(new RoundRectangle2D.Float(26, 2, 44, 82, 44, 44));
            g.setStroke(new BasicStroke(11, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Arc2D.Float(7, 22, 82, 82, 180, 180, Arc2D.OPEN));
            g.draw(new Line2D.Float(48, 106, 48, 132));
        });
    }

    static BufferedImage search() {
        return glyph(140, 140, g -> {
            g.setStroke(new BasicStroke(14, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Ellipse2D.Float(9, 9, 84, 84));
            g.draw(new Line2D.Float(84, 84, 128, 128));
        });
    }

    static BufferedImage notInterested() {
        return glyph(140, 140, g -> {
            g.setStroke(new BasicStroke(13, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Ellipse2D.Float(8, 8, 124, 124));
            g.draw(new Line2D.Float(30, 110, 110, 30));
        });
    }
}
