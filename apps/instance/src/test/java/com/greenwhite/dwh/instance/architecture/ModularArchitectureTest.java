package com.greenwhite.dwh.instance.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

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
    @DisplayName("1. Модули ядра (md, kauth, ms, mf, audit, kwh, search) не должны иметь циклических зависимостей (DAG)")
    void modulesShouldBeFreeOfCycles() {
        slices().matching("com.greenwhite.dwh.instance.(*)..")
                .should().beFreeOfCycles()
                .check(importedClasses);
    }

    @Test
    @DisplayName("2. Контроллеры не должны напрямую обращаться к репозиториям (только через сервисный слой)")
    void controllersShouldNotAccessRepositoriesDirectly() {
        noClasses()
                .that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .check(importedClasses);
    }

    @Test
    @DisplayName("3. Репозитории не должны зависеть от сервисов (чистота слоя данных)")
    void repositoriesShouldNotDependOnServices() {
        noClasses()
                .that().haveSimpleNameEndingWith("Repository")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Service")
                .check(importedClasses);
    }

    @Test
    @DisplayName("4. Модуль мастер-данных (md) не должен зависеть от прикладного модуля задач (ms)")
    void masterDataModuleShouldNotDependOnTasksModule() {
        noClasses()
                .that().resideInAPackage("com.greenwhite.dwh.instance.md..")
                .should().dependOnClassesThat().resideInAPackage("com.greenwhite.dwh.instance.ms..")
                .check(importedClasses);
    }

    @Test
    @DisplayName("5. Модуль файлового хранилища (mf) не должен зависеть от прикладного модуля задач (ms)")
    void fileStorageModuleShouldNotDependOnTasksModule() {
        noClasses()
                .that().resideInAPackage("com.greenwhite.dwh.instance.mf..")
                .should().dependOnClassesThat().resideInAPackage("com.greenwhite.dwh.instance.ms..")
                .check(importedClasses);
    }

    @Test
    @DisplayName("6. Все REST контроллеры должны быть аннотированы @RestController")
    void controllersShouldBeAnnotatedWithRestController() {
        classes()
                .that().haveSimpleNameEndingWith("Controller")
                .should().beAnnotatedWith(RestController.class)
                .check(importedClasses);
    }

    @Test
    @DisplayName("7. Все сервисы должны быть аннотированы @Service")
    void servicesShouldBeAnnotatedWithService() {
        classes()
                .that().haveSimpleNameEndingWith("Service")
                .and().areNotInterfaces()
                .should().beAnnotatedWith(Service.class)
                .check(importedClasses);
    }

    @Test
    @DisplayName("8. Все репозитории должны быть аннотированы @Repository")
    void repositoriesShouldBeAnnotatedWithRepository() {
        classes()
                .that().haveSimpleNameEndingWith("Repository")
                .and().areNotInterfaces()
                .should().beAnnotatedWith(Repository.class)
                .check(importedClasses);
    }
}
