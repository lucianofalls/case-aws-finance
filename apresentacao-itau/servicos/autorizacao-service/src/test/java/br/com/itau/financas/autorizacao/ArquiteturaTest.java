package br.com.itau.financas.autorizacao;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/** Hexagonal garantida no build: domínio e aplicação não conhecem Spring, AWS SDK nem adapters (DIP). */
@AnalyzeClasses(packages = "br.com.itau.financas.autorizacao", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaTest {

    @ArchTest
    static final ArchRule camadas = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("..autorizacao.domain..")
            .layer("Application").definedBy("..autorizacao.application..")
            .layer("Adapters").definedBy("..autorizacao.adapters..")
            .layer("Config").definedBy("..autorizacao.config..")
            .whereLayer("Adapters").mayOnlyBeAccessedByLayers("Config")
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapters", "Config")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapters", "Config");

    @ArchTest
    static final ArchRule nucleoSemFrameworks = noClasses()
            .that().resideInAnyPackage("..autorizacao.domain..", "..autorizacao.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "software.amazon..", "io.github.resilience4j..", "jakarta..");
}
