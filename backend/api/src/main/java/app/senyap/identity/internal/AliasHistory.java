package app.senyap.identity.internal;

import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.id.Tsid;
import java.sql.Timestamp;

/** {@code alias_history}: hanya untuk moderasi, tidak pernah ditampilkan ke user lain. */
final class AliasHistory {

    private AliasHistory() {
    }

    static void open(ModuleDatabase db, Tsid tsid, long userId, String name, String tag, Timestamp from) {
        db.jdbc().sql("""
                        INSERT INTO alias_history (id, user_id, alias_name, alias_tag, valid_from)
                        VALUES (:id, :user, :name, :tag, :from)""")
                .param("id", tsid.next()).param("user", userId).param("name", name).param("tag", tag)
                .param("from", from)
                .update();
    }

    static void closeCurrent(ModuleDatabase db, long userId, Timestamp to) {
        db.jdbc().sql("UPDATE alias_history SET valid_to = :to WHERE user_id = :user AND valid_to IS NULL")
                .param("to", to).param("user", userId)
                .update();
    }
}
