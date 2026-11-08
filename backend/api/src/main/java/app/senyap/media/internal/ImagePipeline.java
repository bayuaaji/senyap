package app.senyap.media.internal;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.stereotype.Component;

/**
 * Pipeline gambar latar (functional spec FS-02), Java murni (ImageIO + Java2D), tanpa libvips:
 * cek magic bytes → cek dimensi sebelum decode (bom dekompresi) → decode → koreksi orientasi EXIF →
 * resize sisi terpanjang ke {@code maxDimension} → blur → overlay gelap → encode ulang JPEG (semua metadata,
 * termasuk EXIF/GPS, ikut terbuang) → BlurHash + perceptual hash (dHash 64-bit).
 *
 * <p>Alasan tidak memakai libvips: butuh library native di image container dan JNI/FFM binding,
 * sementara volume upload MVP kecil (≤ 30/hari/user) dan gambar keluaran cuma 1080 px. Kalau CPU worker
 * jadi bottleneck, ganti implementasi kelas ini saja (build guide §2).
 */
@Component
class ImagePipeline {

    /** Gambar ditolak; {@code reason} disimpan di kolom moderation, bukan ditampilkan ke user apa adanya. */
    static final class RejectedImage extends Exception {
        RejectedImage(String reason) {
            super(reason);
        }
    }

    /**
     * @param originalHashHex dHash gambar <b>asli</b> (setelah orientasi + resize, sebelum blur) untuk hash matching
     * @param skinRatio       proporsi piksel warna kulit (sinyal klasifikasi gambar heuristik)
     */
    record Processed(byte[] jpeg, int width, int height, String blurhash, byte[] perceptualHash,
                     String originalHashHex, double skinRatio) {
    }

    /** Sinyal moderasi dari gambar asli (FS-08). */
    record Signals(String hashHex, double skinRatio) {
    }

    private final MediaProperties props;

    ImagePipeline(MediaProperties props) {
        this.props = props;
    }

    /** Untuk gambar upload user: blur + overlay gelap. */
    Processed processUpload(byte[] input) throws RejectedImage {
        String format = detectFormat(input);
        BufferedImage decoded = decode(input, format);
        if ("jpeg".equals(format)) {
            decoded = applyOrientation(decoded, ExifOrientation.read(input));
        }
        BufferedImage img = resize(toRgb(decoded), props.maxDimension());
        Signals signals = signals(img);
        img = blur(img, props.blurRadius());
        overlay(img, props.overlayAlpha());
        Processed p = finish(img);
        return new Processed(p.jpeg(), p.width(), p.height(), p.blurhash(), p.perceptualHash(), signals.hashHex(),
                signals.skinRatio());
    }

    /** Hanya sinyal moderasi (gambar DM tidak diproses ulang). */
    Signals inspect(byte[] input) throws RejectedImage {
        String format = detectFormat(input);
        BufferedImage decoded = decode(input, format);
        if ("jpeg".equals(format)) {
            decoded = applyOrientation(decoded, ExifOrientation.read(input));
        }
        return signals(resize(toRgb(decoded), props.maxDimension()));
    }

    static Signals signals(BufferedImage img) {
        return new Signals(java.util.HexFormat.of().formatHex(dHash(img)), skinRatio(img));
    }

    /** Proporsi piksel dalam rentang warna kulit YCbCr klasik (Cb 77–127, Cr 133–173), sampel grid ≤ 100×100. */
    static double skinRatio(BufferedImage img) {
        int stepX = Math.max(1, img.getWidth() / 100);
        int stepY = Math.max(1, img.getHeight() / 100);
        int total = 0;
        int skin = 0;
        for (int y = 0; y < img.getHeight(); y += stepY) {
            for (int x = 0; x < img.getWidth(); x += stepX) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                double cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b;
                double cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b;
                total++;
                if (cb >= 77 && cb <= 127 && cr >= 133 && cr <= 173) {
                    skin++;
                }
            }
        }
        return total == 0 ? 0 : (double) skin / total;
    }

    /** Untuk galeri bawaan: hanya resize + encode (gambar sudah dirancang terbaca). */
    Processed processBuiltin(BufferedImage img) {
        return finish(resize(toRgb(img), props.maxDimension()));
    }

    static String detectFormat(byte[] b) throws RejectedImage {
        if (b.length > 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) {
            return "jpeg";
        }
        if (b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "png";
        }
        throw new RejectedImage("format tidak didukung");
    }

    private BufferedImage decode(byte[] input, String format) throws RejectedImage {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            var readers = ImageIO.getImageReadersByFormatName(format);
            if (!readers.hasNext()) {
                throw new RejectedImage("tidak ada decoder " + format);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > props.maxPixels()) {
                    throw new RejectedImage("dimensi tidak wajar");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw new RejectedImage("gagal decode");
        }
    }

    static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        var out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.BLACK); // area transparan PNG → hitam (latar teks putih)
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Orientasi EXIF 1..8 → gambar tegak. */
    static BufferedImage applyOrientation(BufferedImage img, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return img;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        boolean swap = orientation >= 5;
        var t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.translate(0, h); t.scale(1, -1); }
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1, 1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { }
        }
        var out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, t, null);
        g.dispose();
        return out;
    }

    /** Perkecil bertahap (setengah per langkah) supaya hasilnya halus; tidak pernah memperbesar. */
    static BufferedImage resize(BufferedImage img, int maxDimension) {
        int w = img.getWidth();
        int h = img.getHeight();
        double scale = Math.min(1.0, (double) maxDimension / Math.max(w, h));
        int targetW = Math.max(1, (int) Math.round(w * scale));
        int targetH = Math.max(1, (int) Math.round(h * scale));
        BufferedImage cur = img;
        while (cur.getWidth() != targetW || cur.getHeight() != targetH) {
            int nw = Math.max(targetW, cur.getWidth() / 2);
            int nh = Math.max(targetH, cur.getHeight() / 2);
            var next = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(cur, 0, 0, nw, nh, null);
            g.dispose();
            cur = next;
        }
        return cur;
    }

    /** Box blur 3 lintasan (≈ Gaussian) horizontal + vertikal, tepi di-clamp. */
    static BufferedImage blur(BufferedImage img, int radius) {
        if (radius <= 0) {
            return img;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int[] a = img.getRGB(0, 0, w, h, null, 0, w);
        int[] b = new int[a.length];
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(a, b, w, h, radius, true);
            boxBlur(b, a, w, h, radius, false);
        }
        var out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, a, 0, w);
        return out;
    }

    private static void boxBlur(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h;
        int lines = horizontal ? h : w;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int sr = 0;
            int sg = 0;
            int sb = 0;
            for (int k = -r; k <= r; k++) {
                int p = src[index(line, Math.clamp(k, 0, len - 1), w, horizontal)];
                sr += (p >> 16) & 0xff;
                sg += (p >> 8) & 0xff;
                sb += p & 0xff;
            }
            for (int i = 0; i < len; i++) {
                dst[index(line, i, w, horizontal)] = ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int out = src[index(line, Math.clamp(i - r, 0, len - 1), w, horizontal)];
                int in = src[index(line, Math.clamp(i + r + 1, 0, len - 1), w, horizontal)];
                sr += ((in >> 16) & 0xff) - ((out >> 16) & 0xff);
                sg += ((in >> 8) & 0xff) - ((out >> 8) & 0xff);
                sb += (in & 0xff) - (out & 0xff);
            }
        }
    }

    private static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    static void overlay(BufferedImage img, double alpha) {
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0, 0, 0, (int) Math.round(Math.clamp(alpha, 0, 1) * 255)));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.dispose();
    }

    private static Processed finish(BufferedImage img) {
        return new Processed(encodeJpeg(img, 0.85f), img.getWidth(), img.getHeight(),
                BlurHash.encode(resize(img, 32), 4, 3), dHash(img), null, 0);
    }

    static byte[] encodeJpeg(BufferedImage img, float quality) {
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var bytes = new ByteArrayOutputStream();
        try (var out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            writer.write(null, new IIOImage(img, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("gagal encode JPEG", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** dHash 64-bit: gambar abu-abu 9×8, bit = piksel kiri lebih terang dari kanannya. */
    static byte[] dHash(BufferedImage img) {
        var small = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(resize(img, 64), 0, 0, 9, 8, null);
        g.dispose();
        var raster = small.getRaster();
        long bits = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                bits = (bits << 1) | (raster.getSample(x, y, 0) > raster.getSample(x + 1, y, 0) ? 1 : 0);
            }
        }
        byte[] out = new byte[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) bits;
            bits >>>= 8;
        }
        return out;
    }
}
