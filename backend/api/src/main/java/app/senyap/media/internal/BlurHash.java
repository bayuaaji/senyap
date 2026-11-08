package app.senyap.media.internal;

import java.awt.image.BufferedImage;

/**
 * Encoder BlurHash (https://blurha.sh, algoritma publik, lisensi MIT) untuk placeholder gambar latar
 * di client. Gambar sebaiknya sudah diperkecil (mis. 32 px) karena biayanya O(lebar × tinggi × komponen).
 */
final class BlurHash {

    private static final String CHARS =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~";

    private BlurHash() {
    }

    static String encode(BufferedImage img, int componentsX, int componentsY) {
        if (componentsX < 1 || componentsX > 9 || componentsY < 1 || componentsY > 9) {
            throw new IllegalArgumentException("komponen harus 1..9");
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
        double[][] factors = new double[componentsX * componentsY][3];
        for (int j = 0; j < componentsY; j++) {
            for (int i = 0; i < componentsX; i++) {
                double normalisation = (i == 0 && j == 0) ? 1 : 2;
                double r = 0;
                double g = 0;
                double b = 0;
                for (int y = 0; y < h; y++) {
                    double cosY = Math.cos(Math.PI * j * y / h);
                    for (int x = 0; x < w; x++) {
                        double basis = normalisation * Math.cos(Math.PI * i * x / w) * cosY;
                        int p = pixels[y * w + x];
                        r += basis * srgbToLinear((p >> 16) & 0xff);
                        g += basis * srgbToLinear((p >> 8) & 0xff);
                        b += basis * srgbToLinear(p & 0xff);
                    }
                }
                double scale = 1.0 / (w * h);
                factors[j * componentsX + i] = new double[] {r * scale, g * scale, b * scale};
            }
        }

        var hash = new StringBuilder();
        encode83((componentsX - 1) + (componentsY - 1) * 9, 1, hash);

        double maximumValue;
        if (factors.length > 1) {
            double actualMax = 0;
            for (int k = 1; k < factors.length; k++) {
                for (double v : factors[k]) {
                    actualMax = Math.max(actualMax, Math.abs(v));
                }
            }
            int quantisedMax = (int) Math.max(0, Math.min(82, Math.floor(actualMax * 166 - 0.5)));
            maximumValue = (quantisedMax + 1) / 166.0;
            encode83(quantisedMax, 1, hash);
        } else {
            maximumValue = 1;
            encode83(0, 1, hash);
        }

        double[] dc = factors[0];
        encode83((linearToSrgb(dc[0]) << 16) + (linearToSrgb(dc[1]) << 8) + linearToSrgb(dc[2]), 4, hash);
        for (int k = 1; k < factors.length; k++) {
            double[] ac = factors[k];
            int quantR = quantiseAc(ac[0], maximumValue);
            int quantG = quantiseAc(ac[1], maximumValue);
            int quantB = quantiseAc(ac[2], maximumValue);
            encode83(quantR * 19 * 19 + quantG * 19 + quantB, 2, hash);
        }
        return hash.toString();
    }

    private static int quantiseAc(double value, double maximumValue) {
        return (int) Math.max(0, Math.min(18, Math.floor(signPow(value / maximumValue, 0.5) * 9 + 9.5)));
    }

    private static double signPow(double v, double exp) {
        return Math.copySign(Math.pow(Math.abs(v), exp), v);
    }

    private static double srgbToLinear(int value) {
        double v = value / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static int linearToSrgb(double value) {
        double v = Math.max(0, Math.min(1, value));
        return v <= 0.0031308
                ? (int) (v * 12.92 * 255 + 0.5)
                : (int) ((1.055 * Math.pow(v, 1 / 2.4) - 0.055) * 255 + 0.5);
    }

    private static void encode83(int value, int length, StringBuilder out) {
        for (int i = 1; i <= length; i++) {
            int digit = (value / pow83(length - i)) % 83;
            out.append(CHARS.charAt(digit));
        }
    }

    private static int pow83(int exp) {
        int r = 1;
        for (int i = 0; i < exp; i++) {
            r *= 83;
        }
        return r;
    }
}
