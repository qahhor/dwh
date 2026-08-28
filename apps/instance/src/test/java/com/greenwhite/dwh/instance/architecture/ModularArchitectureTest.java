package com.greenwhite.dwh.instance.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ModularArchitectureTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void setup() {
        importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.greenwhite.dwh.instance");
    }

    @Test
    @DisplayName("Модули ядра (md, kauth, ms, mf, audit, kwh) не должны иметь циклических зависимостей")
    void modulesShouldBeFreeOfCycles() {
        slices().matching("com.greenwhite.dwh.instance.(*)..")
                .should().beFreeOfCycles()
                .check(importedClasses);
    }

    @Test
    @DisplayName("Репозитории должны быть доступны только внутри своего доменного пакета")
    void repositoriesShouldOnlyBeAccessedBySamePackage() {
        classes()
                .that().haveSimpleNameEndingWith("Repository")
                .should().onlyBeAccessed().byAnyPackage(
                        "com.greenwhite.dwh.instance..repository..",
                        "com.greenwhite.dwh.instance..service..",
                        "com.greenwhite.dwh.instance..worker..",
                        "com.greenwhite.dwh.instance..config.."
                )
                .check(importedClasses);
    }

    @Test
    @DisplayName("Контроллеры не должны напрямую обращаться к чужим репозиториям")
    void controllersShouldNotAccessForeignRepositoriesDirectly() {
        noClasses()
                .that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .check(importedClasses);
    }
}
