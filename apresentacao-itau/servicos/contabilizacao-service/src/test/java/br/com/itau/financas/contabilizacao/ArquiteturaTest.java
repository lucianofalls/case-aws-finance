package br.com.itau.financas.contabilizacao;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "br.com.itau.financas.contabilizacao", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaTest {

    @ArchTest
    static final ArchRule nucleoSemFrameworks = noClasses()
            .that().resideInAnyPackage("..contabilizacao.domain..", "..contabilizacao.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "software.amazon..", "java.sql..", "br.com.itau.financas.eventos..");

    @ArchTest
    static final ArchRule nucleoNaoConheceAdapters = noClasses()
            .that().resideInAnyPackage("..contabilizacao.domain..", "..contabilizacao.application..")
            .should().dependOnClassesThat().resideInAnyPackage("..contabilizacao.adapters..");
}
