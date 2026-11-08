package app.senyap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import app.senyap.SenyapApiApplication;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Aturan M3 + M10: modul hanya bicara lewat API publiknya; tidak ada siklus. */
class ModularityTest {

    static final ApplicationModules MODULES =
            ApplicationModules.of(SenyapApiApplication.class, new ImportOption.DoNotIncludeTests());

    @Test
    void batasModulTerverifikasi() {
        MODULES.verify();
    }

    @Test
    void semuaModulTerdeteksi() {
        assertThat(MODULES.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .contains("auth", "identity", "media", "posts", "feed", "chat", "notifications", "safety", "admin",
                        "shared");
    }

    @Test
    void tulisDokumentasiModul() {
        // Diagram PlantUML + canvas modul di target/spring-modulith-docs (tidak di-commit).
        new Documenter(MODULES).writeModulesAsPlantUml().writeModuleCanvases();
    }
}
