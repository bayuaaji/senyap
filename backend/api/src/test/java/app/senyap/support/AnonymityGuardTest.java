package app.senyap.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AnonymityGuardTest {

    @Test
    void responsBersihLolos() {
        String post = """
                {"id":"7251234567890123456","author":{"public_id":"3f9c","name":"Kelinci Jingga","tag":"4821"},
                 "heart_count":42,"is_mine":false}""";
        assertThat(AnonymityGuard.scan(post, Set.of("99"))).isEmpty();
    }

    @Test
    void fieldTerlarangSnakeDanCamelCaseTertangkap() {
        assertThat(AnonymityGuard.scan("{\"user_id\":1,\"a\":{\"authorId\":2},\"b\":[{\"SENDER_ID\":3}]}", Set.of()))
                .containsExactly("field terlarang $.user_id", "field terlarang $.a.authorId",
                        "field terlarang $.b[0].SENDER_ID");
    }

    @Test
    void nilaiUserIdTertangkapBaikStringMaupunAngka() {
        assertThat(AnonymityGuard.scan("{\"id\":\"123\",\"x\":[123]}", Set.of("123")))
                .containsExactly("nilai user ID di $.id", "nilai user ID di $.x[0]");
    }

    @Test
    void bukanJsonDiabaikan() {
        assertThat(AnonymityGuard.scan("<html>", Set.of("1"))).isEmpty();
    }
}
