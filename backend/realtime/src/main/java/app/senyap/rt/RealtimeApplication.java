package app.senyap.rt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Gateway WebSocket Senyap (Spring WebFlux / Netty). Dideploy terpisah dari {@code api} supaya deploy
 * API tidak memutus koneksi chat (build guide §5 poin 5). Protokol frame: functional spec FS-05 §5.5.
 */
@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class RealtimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealtimeApplication.class, args);
    }
}
