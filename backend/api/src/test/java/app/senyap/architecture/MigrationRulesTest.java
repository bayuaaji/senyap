package app.senyap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * M1 + M2 di level migrasi: tiap schema punya folder migrasi sendiri, tidak menyebut schema lain
 * (tidak ada FK/JOIN lintas schema), dan punya tabel {@code outbox} (M7).
 */
class MigrationRulesTest {

    static final Path ROOT = Path.of("src/main/resources/db/migration");
    static final List<String> SCHEMAS = List.of("auth", "identity", "media", "posts", "chat", "notifications", "safety");

    static Stream<String> schemas() {
        return SCHEMAS.stream();
    }

    @ParameterizedTest
    @MethodSource("schemas")
    void migrasiHanyaMenyentuhSchemaSendiri(String schema) throws IOException {
        String sql = allSql(schema);
        for (String other : SCHEMAS) {
            if (other.equals(schema)) {
                continue;
            }
            // Abaikan komentar ("→ identity.users (logis)"); yang dicek hanya SQL yang dieksekusi.
            var qualified = Pattern.compile("(?i)\\b" + other + "\\s*\\.\\s*[a-z_]+");
            assertThat(qualified.matcher(stripComments(sql)).find())
                    .as("%s menyebut schema %s (aturan M2)", schema, other).isFalse();
        }
        assertThat(stripComments(sql)).as("%s tidak boleh SET search_path / schema lain", schema)
                .doesNotContainIgnoringCase("search_path");
    }

    @ParameterizedTest
    @MethodSource("schemas")
    void tiapSchemaPunyaOutbox(String schema) throws IOException {
        assertThat(allSql(schema)).containsPattern("(?i)CREATE TABLE outbox\\s*\\(");
    }

    private static String allSql(String schema) throws IOException {
        Path dir = ROOT.resolve(schema);
        assertThat(dir).as("folder migrasi %s", schema).isDirectory();
        try (Stream<Path> files = Files.list(dir)) {
            var sb = new StringBuilder();
            for (Path f : files.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                sb.append(Files.readString(f)).append('\n');
            }
            assertThat(sb).as("migrasi %s", schema).isNotEmpty();
            return sb.toString();
        }
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("--[^\\n]*", "");
    }
}
