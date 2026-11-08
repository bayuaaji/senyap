package app.senyap.media.internal;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Gambar placeholder galeri bawaan, dibuat secara prosedural (gradien + lingkaran lembut) dengan seed
 * deterministik per kategori + nomor. Bebas lisensi karena dibuat sendiri. <b>Aset final</b> (foto/ilustrasi
 * berlisensi jelas, ±300 per kategori) belum disiapkan; lihat catatan serah terima Fase 3 di PROGRESS.md.
 */
final class GalleryArt {

    /** Sama dengan kategori post (functional spec §2, tabel posts). */
    static final List<String> CATEGORIES =
            List.of("curhat", "cinta", "kerja", "kampus", "keluarga", "pengakuan", "tanya", "lainnya");

    private static final Map<String, float[]> HUES = Map.of(
            "curhat", new float[] {0.60f, 0.70f},
            "cinta", new float[] {0.93f, 0.02f},
            "kerja", new float[] {0.55f, 0.48f},
            "kampus", new float[] {0.12f, 0.08f},
            "keluarga", new float[] {0.30f, 0.22f},
            "pengakuan", new float[] {0.78f, 0.85f},
            "tanya", new float[] {0.50f, 0.58f},
            "lainnya", new float[] {0.0f, 0.66f});

    static final int WIDTH = 720;
    static final int HEIGHT = 900;

    private GalleryArt() {
    }

    static BufferedImage render(String category, int number) {
        var rnd = new Random(31L * category.hashCode() + number);
        float[] hues = HUES.getOrDefault(category, new float[] {0.6f, 0.7f});
        float h1 = (hues[0] + (rnd.nextFloat() - 0.5f) * 0.08f + 1f) % 1f;
        float h2 = (hues[1] + (rnd.nextFloat() - 0.5f) * 0.08f + 1f) % 1f;
        Color c1 = Color.getHSBColor(h1, 0.45f + rnd.nextFloat() * 0.35f, 0.30f + rnd.nextFloat() * 0.25f);
        Color c2 = Color.getHSBColor(h2, 0.40f + rnd.nextFloat() * 0.40f, 0.15f + rnd.nextFloat() * 0.20f);

        var img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(rnd.nextInt(WIDTH), 0, c1, rnd.nextInt(WIDTH), HEIGHT, c2));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        int circles = 6 + rnd.nextInt(8);
        for (int i = 0; i < circles; i++) {
            float hue = rnd.nextBoolean() ? h1 : h2;
            Color c = Color.getHSBColor(hue, 0.3f + rnd.nextFloat() * 0.4f, 0.6f + rnd.nextFloat() * 0.4f);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 18 + rnd.nextInt(40)));
            double d = 80 + rnd.nextDouble() * 360;
            g.fill(new Ellipse2D.Double(rnd.nextDouble() * WIDTH - d / 2, rnd.nextDouble() * HEIGHT - d / 2, d, d));
        }
        g.dispose();
        BufferedImage soft = ImagePipeline.blur(img, 8);
        ImagePipeline.overlay(soft, 0.15);
        return soft;
    }
}
