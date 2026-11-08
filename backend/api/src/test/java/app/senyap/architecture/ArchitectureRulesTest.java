package app.senyap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit untuk aturan M3–M6. Tiap aturan dijalankan terhadap kode produksi (harus bersih) dan
 * terhadap fixture {@code fixture.arch} yang sengaja melanggar (harus tertangkap), supaya aturan yang
 * diam-diam tidak memeriksa apa pun ketahuan.
 */
class ArchitectureRulesTest {

    static final String BASE = "app.senyap";
    static final String FIXTURE = "fixture.arch";

    static final JavaClasses PROD = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .withImportOption(new ImportOption.DoNotIncludeJars())
            .importPackages(BASE);

    static final JavaClasses FIX = new ClassFileImporter().importPackages(FIXTURE);

    @Test
    void m3KodeProduksiHanyaMemakaiApiPublikModul() {
        assertThat(ModuleRules.modules(PROD, BASE)).contains("chat", "identity", "posts");
        ModuleRules.m3OnlyPublicApi(PROD, BASE).check(PROD);
    }

    @Test
    void m3FixtureMelanggarTertangkap() {
        var result = ModuleRules.m3OnlyPublicApi(FIX, FIXTURE).evaluate(FIX);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString())
                .contains("BetaService").contains("alpha.internal.AlphaEntity")
                .contains("alpha.AlphaHelper");
    }

    @Test
    void m4KodeProduksiApiSiapJaringan() {
        ModuleRules.m4NetworkReadyApis(BASE).check(PROD);
    }

    @Test
    void m4FixtureMelanggarTertangkap() {
        var report = ModuleRules.m4NetworkReadyApis(FIXTURE).evaluate(FIX).getFailureReport().toString();
        assertThat(report).contains("AlphaApi.find").contains("AlphaApi.stream");
        assertThat(report).doesNotContain("GammaApi");
    }

    @Test
    void m5TanpaEventInMemory() {
        ModuleRules.m5NoInMemoryEvents(BASE).check(PROD);
        assertThat(ModuleRules.m5NoInMemoryEvents(FIXTURE).evaluate(FIX).hasViolation()).isTrue();
    }

    @Test
    void m6TransaksiMenyebutModulnya() {
        ModuleRules.m6TransactionsNameTheirModule(BASE).check(PROD);
        var report = ModuleRules.m6TransactionsNameTheirModule(FIXTURE).evaluate(FIX).getFailureReport().toString();
        assertThat(report).contains("BetaService.tanpaNama").doesNotContain("BetaService.denganNama");
    }
}
