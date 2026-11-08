package app.senyap.chat.internal;

import app.senyap.chat.OriginDirectory;
import app.senyap.shared.db.ModuleDatabase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * SEMENTARA (Fase 2): asal Bisik dari tabel seed {@code chat.dev_origins}, karena post/balasan baru ada di
 * Fase 3. Setelah Fase 3 digabung: ganti dengan adapter ke {@code PostsApi.findOrigins} (beri {@code @Primary}),
 * lalu hapus class ini, {@link DevSeedController}, dan tabel {@code dev_origins}.
 */
@Component
class SeedOriginDirectory implements OriginDirectory {

    private final ModuleDatabase db;

    SeedOriginDirectory(@Qualifier("chatDatabase") ModuleDatabase db) {
        this.db = db;
    }

    @Override
    public Map<OriginKey, OriginInfo> findOrigins(List<OriginKey> keys) {
        var out = new HashMap<OriginKey, OriginInfo>();
        for (OriginKey k : keys) { // jumlahnya kecil (satu layar); tabel ini sementara
            db.jdbc().sql("SELECT owner_id, excerpt FROM dev_origins WHERE origin_type = :t AND origin_id = :i")
                    .param("t", k.type()).param("i", k.id())
                    .query((rs, n) -> new OriginInfo(k, rs.getLong(1), rs.getString(2)))
                    .optional().ifPresent(o -> out.put(k, o));
        }
        return out;
    }

    long create(String type, long id, long ownerUserId, String excerpt) {
        db.jdbc().sql("INSERT INTO dev_origins (origin_type, origin_id, owner_id, excerpt) VALUES (:t, :i, :o, :e)")
                .param("t", type).param("i", id).param("o", ownerUserId).param("e", excerpt)
                .update();
        return id;
    }
}
