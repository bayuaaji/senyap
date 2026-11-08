package app.senyap.media.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImagePipelineTest {

    private final ImagePipeline pipeline = new ImagePipeline(new MediaProperties("http://x/media", 10_485_760,
            Duration.ofMinutes(15), 30, 5, 1080, 40_000_000, 3, 0.35, true, 300, Duration.ofMinutes(5)));

    @Test
    void blurhashContohResmi() {
        // Gambar satu warna: hanya komponen DC → hash 6 karakter, "00" + warna.
        var img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 8, 8);
        g.dispose();
        assertThat(BlurHash.encode(img, 1, 1)).isEqualTo("00TSUA"); // 0xFFFFFF dalam base83
        assertThat(BlurHash.encode(img, 4, 3)).hasSize(4 + 2 * 4 * 3);
    }

    @Test
    void uploadDiresizeDiblurDanDiencodeUlang() throws Exception {
        byte[] png = png(3000, 2000);
        var out = pipeline.processUpload(png);
        assertThat(out.width()).isEqualTo(1080);
        assertThat(out.height()).isEqualTo(720);
        assertThat(out.perceptualHash()).hasSize(8);
        assertThat(out.blurhash()).hasSize(28);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out.jpeg()));
        assertThat(decoded.getWidth()).isEqualTo(1080);
        // overlay gelap: putih murni tidak tersisa
        assertThat(decoded.getRGB(5, 5) & 0xff).isLessThan(200);
    }

    @Test
    void bukanGambarDitolak() {
        assertThatThrownBy(() -> pipeline.processUpload("<?php echo 1; ?>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ImagePipeline.RejectedImage.class);
        byte[] fakePng = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0};
        assertThatThrownBy(() -> pipeline.processUpload(fakePng)).isInstanceOf(ImagePipeline.RejectedImage.class);
    }

    @Test
    void orientasiExifDibacaDanDiterapkan() {
        // JPEG minimal: SOI + APP1 Exif (big endian) dengan Orientation = 6
        byte[] jpeg = {
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x00, 0x22,
                'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
                0x00, 0x01,
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x06, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                (byte) 0xFF, (byte) 0xD9};
        assertThat(ExifOrientation.read(jpeg)).isEqualTo(6);
        assertThat(ExifOrientation.read(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9})).isEqualTo(1);

        var img = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0xFF0000); // kiri atas merah
        var rotated = ImagePipeline.applyOrientation(img, 6); // putar 90° searah jarum jam
        assertThat(rotated.getWidth()).isEqualTo(2);
        assertThat(rotated.getHeight()).isEqualTo(4);
        assertThat(rotated.getRGB(1, 0) & 0xFFFFFF).as("kiri atas pindah ke kanan atas").isEqualTo(0xFF0000);
    }

    @Test
    void galeriPlaceholderDeterministik() {
        var a = pipeline.processBuiltin(GalleryArt.render("cinta", 7));
        var b = pipeline.processBuiltin(GalleryArt.render("cinta", 7));
        assertThat(a.blurhash()).isEqualTo(b.blurhash());
        assertThat(a.width()).isEqualTo(GalleryArt.WIDTH);
        assertThat(pipeline.processBuiltin(GalleryArt.render("kerja", 7)).blurhash()).isNotEqualTo(a.blurhash());
    }

    private static byte[] png(int w, int h) throws IOException {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLUE);
        g.fillOval(w / 3, h / 3, w / 3, h / 3);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
