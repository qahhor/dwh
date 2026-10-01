package com.smartup24.cms.instance.warehouse;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.jobs.fixtures.JobsDependsOnWarehouseViolator;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.support.fixtures.DwhQualifierViolator;
import com.smartup24.cms.instance.upl.fixtures.UplModuleFixture;
import com.smartup24.cms.instance.warehouse.fixtures.WarehouseDependsOnUplViolator;
import com.smartup24.cms.instance.warehouse.fixtures.WarehouseScheduledViolator;
import com.smartup24.cms.spi.storage.StorageProvider;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

/**
 * Architecture rules of the modules split out of the former foundation (plan 10/10, item 4.2, ADR-0030): the pg-dwh
 * qualifier stays inside the warehouse, the job queue knows no warehouse type, the data modules depend on no
 * application module, keep no file storage of their own and have no controllers or schedulers. Runs without Spring
 * or a database.
 */
class WarehouseArchitectureTest {

    private static final String ROOT = "com.smartup24.cms.instance";
    private static final String WAREHOUSE = ROOT + ".warehouse..";
    private static final String JOBS = ROOT + ".jobs..";
    private static final String UNITS = ROOT + ".units..";
    private static JavaClasses main;

    @BeforeAll
    static void importClasses() {
        main = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
        if (main.isEmpty()) {
            throw new IllegalStateException("Классы приложения не импортированы — проверьте версию ArchUnit");
        }
    }

    /** The {@code "dwh"} qualifier may appear only on fields and parameters of classes in the warehouse package. */
    static ArchRule warehouseQualifierOnlyInWarehouse() {
        return classes().should(new ArchCondition<>("use @Qualifier(\"dwh\") only inside " + WAREHOUSE) {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                boolean inWarehouse = type.getPackageName().startsWith(ROOT + ".warehouse");
                for (JavaField field : type.getFields()) {
                    if (isWarehouse(
                            field.tryGetAnnotationOfType(Qualifier.class).orElse(null))) {
                        events.add(new SimpleConditionEvent(
                                type,
                                inWarehouse,
                                field.getFullName() + " объявляет @Qualifier(\"dwh\") вне warehouse"));
                    }
                }
                for (JavaCodeUnit unit : type.getCodeUnits()) {
                    for (JavaParameter parameter : unit.getParameters()) {
                        if (isWarehouse(parameter
                                .tryGetAnnotationOfType(Qualifier.class)
                                .orElse(null))) {
                            events.add(new SimpleConditionEvent(
                                    type,
                                    inWarehouse,
                                    unit.getFullName() + " принимает @Qualifier(\"dwh\") вне warehouse"));
                        }
                    }
                }
            }

            private boolean isWarehouse(Qualifier qualifier) {
                return qualifier != null && WarehousePref.QUALIFIER.equals(qualifier.value());
            }
        });
    }

    @Test
    @DisplayName("AC-5: бины pg-dwh (@Qualifier(\"dwh\")) используются только в ..instance.warehouse..")
    void warehouseQualifierStaysInWarehouse() {
        warehouseQualifierOnlyInWarehouse().check(main);
    }

    @Test
    @DisplayName("AC-5: фикстура-нарушитель вне warehouse делает правило красным")
    void warehouseQualifierViolatorIsRed() {
        JavaClasses withViolator = new ClassFileImporter().importClasses(DwhQualifierViolator.class);
        EvaluationResult result = warehouseQualifierOnlyInWarehouse().evaluate(withViolator);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains("DwhQualifierViolator");
    }

    /** Plan 10/10, item 4.2: the shared job queue knows no warehouse type; the warehouse uses the queue, not back. */
    static ArchRule jobsDependOnNoWarehouseRule() {
        return noClasses()
                .that()
                .resideInAPackage(JOBS)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(WAREHOUSE)
                .as("the job queue does not depend on the warehouse");
    }

    @Test
    @DisplayName("4.2: очередь заданий не зависит от хранилища")
    void jobsDependOnNoWarehouse() {
        jobsDependOnNoWarehouseRule().check(main);
    }

    @Test
    @DisplayName("4.2: фикстура-нарушитель в jobs, знающая тип warehouse, делает правило красным")
    void jobsDependingOnWarehouseIsRed() {
        JavaClasses withViolator = new ClassFileImporter().importClasses(JobsDependsOnWarehouseViolator.class);
        EvaluationResult result = jobsDependOnNoWarehouseRule().evaluate(withViolator);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains("JobsDependsOnWarehouseViolator");
    }

    /**
     * The data modules know no application module; the framework's platform modules (common, config, kauth, md, mf,
     * audit) are allowed.
     */
    static ArchRule dataModulesDependOnNoApplicationModuleRule() {
        return noClasses()
                .that()
                .resideInAnyPackage(WAREHOUSE, JOBS, UNITS)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(ROOT + ".upl..", ROOT + ".ref..", ROOT + ".reg..", ROOT + ".vit..");
    }

    @Test
    @DisplayName("AC-37: warehouse, jobs и units не зависят от прикладных модулей upl/ref/reg/vit")
    void dataModulesDependOnNoApplicationModule() {
        dataModulesDependOnNoApplicationModuleRule().check(main);
    }

    @Test
    @DisplayName("AC-37: фикстура-нарушитель в warehouse, импортирующая upl, делает правило красным")
    void warehouseDependingOnUplIsRed() {
        JavaClasses withViolator =
                new ClassFileImporter().importClasses(WarehouseDependsOnUplViolator.class, UplModuleFixture.class);
        EvaluationResult result = dataModulesDependOnNoApplicationModuleRule().evaluate(withViolator);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString())
                .contains("WarehouseDependsOnUplViolator")
                .contains("UplModuleFixture");
    }

    /** The framework's mf module keeps the files: the data modules have no storage of their own and never call the SPI. */
    @Test
    @DisplayName("AC-8: в warehouse, jobs и units нет своего FileStorage и зависимости от StorageProvider")
    void dataModulesHaveNoOwnFileStorage() {
        noClasses()
                .that()
                .resideInAnyPackage(WAREHOUSE, JOBS, UNITS)
                .should()
                .haveSimpleNameContaining("FileStorage")
                .orShould()
                .dependOnClassesThat()
                .areAssignableTo(StorageProvider.class)
                .check(main);
    }

    /** The warehouse never calls file deletion: the source file of a load is immutable. */
    @Test
    @DisplayName("AC-8: warehouse не вызывает удаление файла в модуле mf")
    void warehouseNeverDeletesFiles() {
        noClasses()
                .that()
                .resideInAPackage(WAREHOUSE)
                .should()
                .callMethodWhere(JavaCall.Predicates.target(owner(assignableTo(MfFileService.class)))
                        .and(JavaCall.Predicates.target(name("deleteFile"))))
                .check(main);
    }

    @Test
    @DisplayName("AC-42: в warehouse и jobs нет контроллеров и эндпоинтов")
    void warehouseAndJobsHaveNoControllers() {
        noClasses()
                .that()
                .resideInAnyPackage(WAREHOUSE, JOBS)
                .should()
                .beAnnotatedWith(RestController.class)
                .orShould()
                .beAnnotatedWith(Controller.class)
                .check(main);
    }

    /**
     * The data modules have no scheduler: the instance chooses when jobs run, and {@code config.jobs.JobQueueWorker}
     * drives the queue. The rule leaves the framework's workers alone.
     */
    static ArchRule noScheduledInDataModulesRule() {
        return noMethods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAnyPackage(WAREHOUSE, JOBS, UNITS)
                .should()
                .beAnnotatedWith(Scheduled.class);
    }

    @Test
    @DisplayName(
            "AC-7: @Scheduled отсутствует в warehouse, jobs и units (в модулях каркаса — есть, правило их не касается)")
    void dataModulesHaveNoScheduled() {
        noScheduledInDataModulesRule().check(main);
        // Check that the rule really is narrow: the framework does use @Scheduled, and the import sees it
        boolean frameworkHasScheduled = main.stream()
                .filter(type -> !type.getPackageName().startsWith(ROOT + ".warehouse"))
                .flatMap(type -> type.getMethods().stream())
                .anyMatch(method -> method.isAnnotatedWith(Scheduled.class));
        assertThat(frameworkHasScheduled)
                .as("в модулях каркаса есть @Scheduled")
                .isTrue();
    }

    @Test
    @DisplayName("AC-7: фикстура-нарушитель с @Scheduled в warehouse делает правило красным")
    void warehouseScheduledViolatorIsRed() {
        JavaClasses withViolator = new ClassFileImporter().importClasses(WarehouseScheduledViolator.class);
        EvaluationResult result = noScheduledInDataModulesRule().evaluate(withViolator);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains("WarehouseScheduledViolator");
    }
}
