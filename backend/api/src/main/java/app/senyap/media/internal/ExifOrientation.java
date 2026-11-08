package app.senyap.media.internal;

/**
 * Membaca tag Orientation (0x0112) dari segmen APP1 Exif JPEG. Hanya itu yang dibaca; EXIF lain
 * (termasuk GPS) dibuang saat gambar di-encode ulang. Mengembalikan 1 (tegak) bila tidak ada atau rusak.
 */
final class ExifOrientation {

    private ExifOrientation() {
    }

    static int read(byte[] jpeg) {
        try {
            int i = 2;
            while (i + 4 <= jpeg.length) {
                if ((jpeg[i] & 0xff) != 0xFF) {
                    return 1;
                }
                int marker = jpeg[i + 1] & 0xff;
                if (marker == 0xDA || marker == 0xD9) { // start of scan / end of image
                    return 1;
                }
                int len = u16(jpeg, i + 2, false);
                if (marker == 0xE1 && len >= 8 && i + 4 + 6 <= jpeg.length
                        && jpeg[i + 4] == 'E' && jpeg[i + 5] == 'x' && jpeg[i + 6] == 'i' && jpeg[i + 7] == 'f') {
                    return fromTiff(jpeg, i + 10, Math.min(jpeg.length, i + 2 + len));
                }
                i += 2 + len;
            }
        } catch (RuntimeException e) {
            return 1;
        }
        return 1;
    }

    private static int fromTiff(byte[] b, int tiff, int end) {
        boolean little = b[tiff] == 'I' && b[tiff + 1] == 'I';
        int ifd = tiff + (int) u32(b, tiff + 4, little);
        int entries = u16(b, ifd, little);
        for (int e = 0; e < entries; e++) {
            int entry = ifd + 2 + e * 12;
            if (entry + 12 > end) {
                return 1;
            }
            if (u16(b, entry, little) == 0x0112) {
                int v = u16(b, entry + 8, little);
                return v >= 1 && v <= 8 ? v : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] b, int i, boolean little) {
        int a = b[i] & 0xff;
        int c = b[i + 1] & 0xff;
        return little ? (c << 8) | a : (a << 8) | c;
    }

    private static long u32(byte[] b, int i, boolean little) {
        long hi = u16(b, little ? i + 2 : i, little);
        long lo = u16(b, little ? i : i + 2, little);
        return (hi << 16) | lo;
    }
}
