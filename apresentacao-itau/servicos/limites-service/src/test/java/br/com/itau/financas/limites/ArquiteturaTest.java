package br.com.itau.financas.limites;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(packages = "br.com.itau.financas.limites", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaTest {

    @ArchTest
    static final ArchRule camadas = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("..limites.domain..")
            .layer("Application").definedBy("..limites.application..")
            .layer("Adapters").definedBy("..limites.adapters..")
            .layer("Config").definedBy("..limites.config..")
            .whereLayer("Adapters").mayOnlyBeAccessedByLayers("Config")
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapters", "Config")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapters", "Config");

    @ArchTest
    static final ArchRule nucleoSemFrameworks = noClasses()
            .that().resideInAnyPackage("..limites.domain..", "..limites.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "software.amazon..", "io.awspring..", "jakarta..",
                    "br.com.itau.financas.eventos..");
}
