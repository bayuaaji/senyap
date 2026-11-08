package app.senyap.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Aturan M3–M6 (build guide §3.1) sebagai ArchUnit rule, dibuat per base package supaya bisa diuji
 * terhadap kode asli dan terhadap fixture yang sengaja melanggar.
 */
final class ModuleRules {

    /** Bukan modul bisnis: infrastruktur bersama dan kontrak (artefak {@code contracts}). */
    static final java.util.Set<String> NOT_MODULES = java.util.Set.of("shared", "contracts");

    private ModuleRules() {
    }

    /** Package langsung di bawah {@code base} yang berisi class, selain {@link #NOT_MODULES}. */
    static Set<String> modules(JavaClasses classes, String base) {
        var result = new TreeSet<String>();
        for (JavaClass c : classes) {
            String pkg = c.getPackageName();
            if (pkg.startsWith(base + ".")) {
                String first = pkg.substring(base.length() + 1).split("\\.")[0];
                if (!NOT_MODULES.contains(first)) {
                    result.add(first);
                }
            }
        }
        return result;
    }

    /**
     * M3: class di luar modul X tidak boleh menyentuh {@code X.internal..}, dan dari package root X hanya
     * boleh memakai interface, record, enum, atau exception (API + DTO).
     */
    static ArchRule m3OnlyPublicApi(JavaClasses classes, String base) {
        List<ArchRule> rules = new ArrayList<>();
        for (String m : modules(classes, base)) {
            String root = base + "." + m;
            rules.add(noClasses().that().resideOutsideOfPackage(root + "..")
                    .should().dependOnClassesThat().resideInAPackage(root + ".internal..")
                    .as("M3: modul lain tidak boleh mengakses " + root + ".internal")
                    .allowEmptyShould(true));
            rules.add(noClasses().that().resideOutsideOfPackage(root + "..")
                    .should().dependOnClassesThat(DescribedPredicate.and(
                            JavaClass.Predicates.resideInAPackage(root),
                            DescribedPredicate.not(apiType())))
                    .as("M3: dari " + root + " modul lain hanya boleh memakai interface/record/enum/exception")
                    .allowEmptyShould(true));
        }
        if (rules.isEmpty()) {
            return noClasses().should().haveSimpleName("__tidak_ada__").allowEmptyShould(true);
        }
        return CompositeArchRule.of(rules);
    }

    private static DescribedPredicate<JavaClass> apiType() {
        return DescribedPredicate.describe("interface/record/enum/exception", c ->
                c.isInterface() || c.isRecord() || c.isEnum() || c.isAssignableTo(Throwable.class)
                        || c.getSimpleName().equals("package-info"));
    }

    /**
     * M4: method pada interface {@code *Api} di package root modul memakai tipe yang aman lewat jaringan:
     * primitif, String, UUID, java.time, koleksi (List/Set/Map/Collection/Optional), record, atau enum.
     * Tidak ada tipe dari {@code ..internal..}, entity JPA, Stream, atau {@code @Transactional}.
     */
    static ArchRule m4NetworkReadyApis(String base) {
        return methods().that().areDeclaredInClassesThat(apiInterface(base))
                .should(useOnlySerializableDtoTypes())
                .andShould().notBeAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                .as("M4: interface *Api dirancang seolah lewat jaringan")
                .allowEmptyShould(true);
    }

    private static DescribedPredicate<JavaClass> apiInterface(String base) {
        return DescribedPredicate.describe("interface *Api di root modul", c ->
                c.isInterface() && c.getSimpleName().endsWith("Api") && c.getPackageName().startsWith(base + ".")
                        && c.getPackageName().substring(base.length() + 1).split("\\.").length == 1);
    }

    private static final Set<String> ALLOWED_JDK = Set.of(
            "java.lang.String", "java.lang.Long", "java.lang.Integer", "java.lang.Boolean", "java.lang.Short",
            "java.lang.Double", "java.lang.Void", "java.util.UUID", "java.util.List", "java.util.Set",
            "java.util.Map", "java.util.Collection", "java.util.Optional");

    private static ArchCondition<JavaMethod> useOnlySerializableDtoTypes() {
        return new ArchCondition<>("hanya memakai tipe DTO yang bisa diserialisasi") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                List<JavaType> types = new ArrayList<>(method.getParameterTypes());
                types.add(method.getReturnType());
                for (JavaType t : types) {
                    for (JavaClass c : t.getAllInvolvedRawTypes()) {
                        if (!allowed(c)) {
                            events.add(SimpleConditionEvent.violated(method,
                                    method.getFullName() + " memakai tipe " + c.getName()));
                        }
                    }
                }
            }
        };
    }

    private static boolean allowed(JavaClass c) {
        if (c.isPrimitive() || ALLOWED_JDK.contains(c.getName()) || c.getPackageName().equals("java.time")) {
            return true;
        }
        if (c.getPackageName().contains(".internal") || c.getPackageName().startsWith("jakarta.persistence")) {
            return false;
        }
        return c.isRecord() || c.isEnum();
    }

    /** M5: event antarmodul lewat Kafka + outbox, bukan event in-memory Spring. */
    static ArchRule m5NoInMemoryEvents(String base) {
        return noClasses().that().resideInAPackage(base + "..")
                .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.context.ApplicationEventPublisher")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.context.event.EventListener")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.modulith.events.ApplicationModuleListener")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.transaction.event.TransactionalEventListener")
                .as("M5: tidak memakai event in-memory Spring; tulis ke outbox modul")
                .allowEmptyShould(true);
    }

    /**
     * M6: setiap {@code @Transactional} wajib menyebut transaction manager modulnya
     * ({@code @Transactional("chatTx")}), jadi satu transaksi hanya menyentuh satu schema.
     */
    static ArchRule m6TransactionsNameTheirModule(String base) {
        var annotation = "org.springframework.transaction.annotation.Transactional";
        return noMethods().that().areDeclaredInClassesThat().resideInAPackage(base + "..")
                .and().areAnnotatedWith(annotation)
                .should(new ArchCondition<>("@Transactional tanpa nama transaction manager") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        var a = method.getAnnotationOfType(annotation);
                        String value = String.valueOf(a.get("value").orElse(""));
                        String tm = String.valueOf(a.get("transactionManager").orElse(""));
                        if (value.isEmpty() && tm.isEmpty()) {
                            events.add(SimpleConditionEvent.satisfied(method, method.getFullName()));
                        }
                    }
                })
                .as("M6: @Transactional harus menyebut transaction manager modul (mis. \"chatTx\")")
                .allowEmptyShould(true);
    }
}
