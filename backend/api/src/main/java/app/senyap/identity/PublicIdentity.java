package app.senyap.identity;

import java.util.UUID;

/**
 * Yang boleh dilihat user lain: handle opaque + alias + gender + avatar. Contoh: "Pangeran Senja" #4821
 * #E07A3F, {@code male}, avatar "🦊".
 *
 * @param avatar emoji avatar pilihan dari daftar tetap, atau {@code null} bila belum memilih
 */
public record PublicIdentity(UUID publicId, String name, String tag, String color, Gender gender, String avatar) {
}
