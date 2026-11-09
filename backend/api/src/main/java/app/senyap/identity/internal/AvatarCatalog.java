package app.senyap.identity.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Daftar tetap avatar (keputusan 9 Nov 2026): emoji hewan/alam yang netral, bukan foto asli, demi anonimitas.
 * {@code users.avatar} menyimpan {@code id}; yang dikirim ke user lain hanya emoji-nya. Menambah item aman;
 * menghapus item membuat avatar user yang memakainya tampil kosong ({@code null}).
 */
final class AvatarCatalog {

    record Avatar(String id, String emoji) {
    }

    static final List<Avatar> AVATARS = List.of(
            new Avatar("rubah", "🦊"),
            new Avatar("kucing", "🐱"),
            new Avatar("kelinci", "🐰"),
            new Avatar("panda", "🐼"),
            new Avatar("koala", "🐨"),
            new Avatar("beruang", "🐻"),
            new Avatar("singa", "🦁"),
            new Avatar("harimau", "🐯"),
            new Avatar("burung-hantu", "🦉"),
            new Avatar("penguin", "🐧"),
            new Avatar("paus", "🐳"),
            new Avatar("lumba-lumba", "🐬"),
            new Avatar("penyu", "🐢"),
            new Avatar("kupu-kupu", "🦋"),
            new Avatar("lebah", "🐝"),
            new Avatar("landak", "🦔"),
            new Avatar("rusa", "🦌"),
            new Avatar("bunga-matahari", "🌻"),
            new Avatar("sakura", "🌸"),
            new Avatar("kaktus", "🌵"),
            new Avatar("semanggi", "🍀"),
            new Avatar("bulan", "🌙"),
            new Avatar("bintang", "⭐"),
            new Avatar("pelangi", "🌈"),
            new Avatar("ombak", "🌊"),
            new Avatar("gunung", "🏔️"));

    private static final Map<String, Avatar> BY_ID =
            AVATARS.stream().collect(Collectors.toUnmodifiableMap(Avatar::id, Function.identity()));

    private AvatarCatalog() {
    }

    static Optional<Avatar> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(BY_ID.get(id));
    }

    /** Emoji untuk ID tersimpan; {@code null} bila kosong atau sudah tidak ada di daftar. */
    static String emoji(String id) {
        return find(id).map(Avatar::emoji).orElse(null);
    }
}
