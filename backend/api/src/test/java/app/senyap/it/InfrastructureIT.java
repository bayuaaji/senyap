package app.senyap.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.id.Tsid;
import app.senyap.support.IntegrationTest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/** Startup penuh: health, migrasi per schema, isolasi role (M1), tanpa FK lintas schema (M2), partisi, Redis, MinIO. */
class InfrastructureIT extends IntegrationTest {

    @Autowired
    @Qualifier("chatDatabase")
    ModuleDatabase chat;

    @Autowired
    Tsid tsid;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    S3Client s3;

    @Test
    void healthUpTermasukSemuaDataSourceRedisDanMinio() {
        var res = get("/actuator/health");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"status\":\"UP\"")
                .contains("authDataSource", "identityDataSource", "mediaDataSource", "postsDataSource",
                        "chatDataSource", "notificationsDataSource", "safetyDataSource")
                .contains("\"redis\":{").contains("\"mediaStorage\":{");
    }

    @Test
    void semuaTabelSpesifikasiAdaDiSchemaMasingMasing() throws SQLException {
        assertThat(tables("auth")).contains("devices", "refresh_tokens", "otp_attempts", "outbox");
        assertThat(tables("identity")).contains("users", "alias_history", "outbox");
        assertThat(tables("media")).contains("media", "outbox");
        assertThat(tables("posts")).contains("posts", "replies", "hearts", "outbox");
        assertThat(tables("chat")).contains("conversations", "conversation_members", "messages", "message_dedup", "outbox");
        assertThat(tables("notifications")).contains("notifications", "outbox");
        assertThat(tables("safety")).contains("blocks", "reports", "moderation_actions", "outbox");
    }

    @Test
    void tidakAdaForeignKeyLintasSchema() throws SQLException {
        assertThat(adminQuery("""
                SELECT conname FROM pg_constraint c
                JOIN pg_class a ON a.oid = c.conrelid JOIN pg_class b ON b.oid = c.confrelid
                WHERE c.contype = 'f' AND a.relnamespace <> b.relnamespace""")).isEmpty();
    }

    @Test
    void userModulTidakBisaMembacaSchemaLain() {
        assertThatThrownBy(() -> {
            try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "senyap_chat", "test_chat_pw");
                 var st = c.createStatement()) {
                st.executeQuery("SELECT count(*) FROM identity.users");
            }
        }).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
    }

    @Test
    void messagesDipartisiPerBulanDanBarisMasukPartisiYangBenar() throws SQLException {
        assertThat(adminQuery("""
                SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'chat.messages'::regclass""")).hasSizeGreaterThanOrEqualTo(4);

        long conv = tsid.next();
        chat.tx().executeWithoutResult(s -> {
            chat.jdbc().sql("""
                    INSERT INTO conversations (id, origin_type, origin_id, initiator_id, recipient_id)
                    VALUES (:id, 'POST', 1, 10, 20)""").param("id", conv).update();
            long seq = chat.jdbc().sql("UPDATE conversations SET last_seq = last_seq + 1 WHERE id = :id RETURNING last_seq")
                    .param("id", conv).query(Long.class).single();
            chat.jdbc().sql("""
                    INSERT INTO messages (conversation_id, seq, sender_id, client_msg_id, type, body, created_at)
                    VALUES (:c, :seq, 10, :cm, 'TEXT', 'halo', :at)""")
                    .param("c", conv).param("seq", seq).param("cm", UUID.randomUUID())
                    .param("at", java.sql.Timestamp.from(Instant.now())).update();
        });
        String partition = chat.jdbc().sql("SELECT tableoid::regclass::text FROM messages WHERE conversation_id = :c")
                .param("c", conv).query(String.class).single();
        assertThat(partition).matches("(chat\\.)?messages_\\d{4}_\\d{2}");
    }

    @Test
    void redisDanMinioBisaDipakai() {
        redis.opsForValue().set("it:ping", "pong");
        assertThat(redis.opsForValue().get("it:ping")).isEqualTo("pong");

        s3.putObject(b -> b.bucket("media").key("it/hello.txt"), RequestBody.fromString("halo"));
        assertThat(s3.getObjectAsBytes(b -> b.bucket("media").key("it/hello.txt")).asUtf8String()).isEqualTo("halo");
    }

    private static List<String> tables(String schema) throws SQLException {
        return adminQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = '" + schema + "'");
    }

    private static List<String> adminQuery(String sql) throws SQLException {
        try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "senyap_admin", "senyap_admin");
             var rs = c.createStatement().executeQuery(sql)) {
            var out = new ArrayList<String>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        }
    }
}
