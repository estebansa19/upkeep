package io.github.estebansa19.upkeep.ui;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Upkeep logo, drawn in code so the window icons and the .exe icon (written at build time) match favicon.svg:
 * a 3x3 patch of chunks, the kept cross in green, fresh corners in blue, an up arrow in the middle.
 */
public final class AppIcon {
    private AppIcon() {}

    private static final Color BG = new Color(0x1e222b), KEEP = new Color(0x34d399), FRESH = new Color(0x60a5fa);

    public static BufferedImage draw(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(size / 32.0, size / 32.0);
        g.setColor(BG);
        g.fill(new RoundRectangle2D.Double(1, 1, 30, 30, 12, 12));
        double cell = 6.5, step = 7.25, x0 = 5.5;
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) {
                g.setColor(r == 1 || c == 1 ? KEEP : FRESH);
                g.fill(new Rectangle2D.Double(x0 + c * step, x0 + r * step, cell, cell));
            }
        Path2D.Double arrow = new Path2D.Double();
        arrow.moveTo(13.4, 17.3);
        arrow.lineTo(16, 14.7);
        arrow.lineTo(18.6, 17.3);
        g.setColor(BG);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(arrow);
        g.dispose();
        return img;
    }

    public static List<Image> windowIcons() {
        List<Image> l = new ArrayList<>();
        for (int s : new int[] { 16, 20, 24, 32, 40, 48, 64 }) l.add(draw(s));
        return l;
    }

    /** A Windows .ico holding the logo as PNG images of the given sizes (up to 256). */
    public static void writeIco(Path file, int... sizes) throws IOException {
        List<byte[]> pngs = new ArrayList<>();
        for (int s : sizes) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(draw(s), "png", bos);
            pngs.add(bos.toByteArray());
        }
        int offset = 6 + 16 * sizes.length, total = offset;
        for (byte[] p : pngs) total += p.length;
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            b.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0);
            b.putShort((short) 1).putShort((short) 32).putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        for (byte[] p : pngs) b.put(p);
        Files.write(file, b.array());
    }

    /** Build step: {@code AppIcon <file.ico>} writes the icon that jpackage puts on Upkeep.exe. */
    public static void main(String[] args) throws IOException {
        writeIco(Path.of(args[0]), 16, 20, 24, 32, 40, 48, 64, 128, 256);
    }
}
