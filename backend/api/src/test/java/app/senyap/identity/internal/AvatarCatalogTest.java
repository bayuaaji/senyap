package app.senyap.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.senyap.identity.internal.AvatarCatalog.Avatar;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class AvatarCatalogTest {

    @Test
    void daftarTetapDenganIdUnikDanEmoji() {
        var ids = AvatarCatalog.AVATARS.stream().map(Avatar::id).toList();
        var emojis = AvatarCatalog.AVATARS.stream().map(Avatar::emoji).toList();
        assertThat(ids).hasSizeBetween(20, 32);
        assertThat(new HashSet<>(ids)).hasSameSizeAs(ids);
        assertThat(new HashSet<>(emojis)).hasSameSizeAs(emojis);
        // ID muat di users.avatar VARCHAR(32), aman di URL/JSON
        assertThat(ids).allSatisfy(id -> assertThat(id).matches("[a-z]+(-[a-z]+)*").hasSizeLessThanOrEqualTo(32));
        assertThat(emojis).allSatisfy(e -> assertThat(e).isNotBlank());
    }

    @Test
    void cariBerdasarkanId() {
        assertThat(AvatarCatalog.find("rubah")).map(Avatar::emoji).contains("🦊");
        assertThat(AvatarCatalog.find("tidak-ada")).isEmpty();
        assertThat(AvatarCatalog.find(null)).isEmpty();
        assertThat(AvatarCatalog.emoji(null)).isNull();
        assertThat(AvatarCatalog.emoji("dihapus-dari-daftar")).isNull();
    }
}
