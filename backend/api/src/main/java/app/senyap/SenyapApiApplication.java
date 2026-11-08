package app.senyap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Modular monolith Senyap. Tiap package langsung di bawah {@code app.senyap} adalah satu modul
 * (Spring Modulith) yang harus siap dipecah jadi service sendiri (build guide §3.1, M1–M12).
 *
 * <p>Profil: {@code api} (HTTP publik) dan {@code worker} (relay outbox, job terjadwal, consumer).
 * Keduanya bisa aktif di satu proses saat pengembangan lokal.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SenyapApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SenyapApiApplication.class, args);
    }
}
