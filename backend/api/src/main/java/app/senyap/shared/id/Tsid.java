package app.senyap.shared.id;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generator TSID 64-bit yang berurut waktu: 42 bit milidetik sejak {@link #EPOCH}, 10 bit node,
 * 12 bit counter. Cukup untuk ±139 tahun, 1.024 node, 4.096 ID per milidetik per node.
 *
 * <p>ID disimpan sebagai {@code BIGINT} dan dikirim ke client sebagai string desimal
 * ({@link #toString(long)}), karena angka 64-bit tidak aman di JavaScript/Dart web.
 */
public final class Tsid {

    /** 2026-01-01T00:00:00Z. Jangan pernah diubah setelah ada data. */
    public static final long EPOCH = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    private static final int NODE_BITS = 10;
    private static final int COUNTER_BITS = 12;
    private static final long MAX_NODE = (1L << NODE_BITS) - 1;
    private static final long COUNTER_MASK = (1L << COUNTER_BITS) - 1;

    private final long node;
    /** time << 12 | counter, dimajukan secara atomik. */
    private final AtomicLong state = new AtomicLong();

    public Tsid(long node) {
        if (node < 0 || node > MAX_NODE) {
            throw new IllegalArgumentException("node harus 0.." + MAX_NODE);
        }
        this.node = node;
    }

    /** Node acak; cukup untuk lokal/test. Di produksi set {@code senyap.id.node} per instance. */
    public static Tsid randomNode() {
        return new Tsid(new SecureRandom().nextInt((int) MAX_NODE + 1));
    }

    public long next() {
        long now = System.currentTimeMillis() - EPOCH;
        long s = state.updateAndGet(prev -> {
            long prevTime = prev >>> COUNTER_BITS;
            if (now > prevTime) {
                return now << COUNTER_BITS;
            }
            // Jam mundur atau masih di milidetik yang sama: lanjutkan counter (meminjam milidetik berikutnya
            // bila counter habis), sehingga ID tetap monoton.
            return prev + 1;
        });
        long time = s >>> COUNTER_BITS;
        long counter = s & COUNTER_MASK;
        return (time << (NODE_BITS + COUNTER_BITS)) | (node << COUNTER_BITS) | counter;
    }

    public static Instant timestampOf(long tsid) {
        return Instant.ofEpochMilli((tsid >>> (NODE_BITS + COUNTER_BITS)) + EPOCH);
    }

    public static String toString(long tsid) {
        return Long.toString(tsid);
    }

    /** @throws IllegalArgumentException jika bukan ID yang valid */
    public static long parse(String value) {
        try {
            long v = Long.parseLong(value);
            if (v <= 0) {
                throw new IllegalArgumentException("ID tidak valid");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("ID tidak valid", e);
        }
    }
}
