package com.smartup24.cms.instance.fnd;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.fnd.fixtures.FndDependsOnUplViolator;
import com.smartup24.cms.instance.fnd.fixtures.FndScheduledViolator;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.support.fixtures.DwhQualifierViolator;
import com.smartup24.cms.instance.upl.fixtures.UplModuleFixture;
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
 * Architecture rules of the foundation (fnd): the pg-dwh qualifier stays inside fnd, fnd depends on no application
 * module, keeps no file storage of its own and has no controllers or schedulers. Runs without Spring or a database.
 */
class FndArchitectureTest {

    private static final String ROOT = "com.smartup24.cms.instance";
    private static final String FND = ROOT + ".fnd..";
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

    /** The {@code "dwh"} qualifier may appear only on fields and parameters of classes in the fnd package. */
    static ArchRule dwhQualifierOnlyInFnd() {
        return classes().should(new ArchCondition<>("use @Qualifier(\"dwh\") only inside " + FND) {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                boolean inFnd = type.getPackageName().startsWith(ROOT + ".fnd");
                for (JavaField field : type.getFields()) {
                    if (isDwh(field.tryGetAnnotationOfType(Qualifier.class).orElse(null))) {
                        events.add(new SimpleConditionEvent(
                                type, inFnd, field.getFullName() + " объявляет @Qualifier(\"dwh\") вне пакета fnd"));
                    }
                }
                for (JavaCodeUnit unit : type.getCodeUnits()) {
                    for (JavaParameter parameter : unit.getParameters()) {
                        if (isDwh(parameter
                                .tryGetAnnotationOfType(Qualifier.class)
                                .orElse(null))) {
                            events.add(new SimpleConditionEvent(
                                    type, inFnd, unit.getFullName() + " принимает @Qualifier(\"dwh\") вне пакета fnd"));
                        }
                    }
                }
            }

            private boolean isDwh(Qualifier qualifier) {
                return qualifier != null && FndPref.DWH.equals(qualifier.value());
            }
        });
    }

    @Test
    @DisplayName("AC-5: бины pg-dwh (@Qualifier(\"dwh\")) используются только в ..instance.fnd..")
    void dwhQualifierStaysInFnd() {
        dwhQualifierOnlyInFnd().check(main);
    }

    @Test
    @DisplayName("AC-5: фикстура-нарушитель вне fnd делает правило красным")
    void dwhQualifierViolatorIsRed() {
        JavaClasses withViolator = new ClassFileImporter().importClasses(DwhQualifierViolator.class);
        EvaluationResult result = dwhQualifierOnlyInFnd().evaluate(withViolator);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains("DwhQualifierViolator");
    }

    /**
     * The foundation knows no application modules; the framework's platform modules (common, config, kauth, md, mf,
     * audit) are allowed.
     */
    static ArchRule fndDependsOnNoApplicationModuleRule() {
        return noClasses()
                .that()
                .resideInAPackage(FND)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(ROOT + ".upl..", ROOT + ".ref..", ROOT + ".reg..", ROOT + ".vit..");
    }

    @Test
    @DisplayName("AC-37: fnd не зависит от прикладных модулей upl/ref/reg/vit")
    void fndDependsOnNoApplicationModule() {
        fndDependsOnNoApplicationModuleRule().check(main);
    }

    @Test
    @DisplayName("AC-37: фикстура-нарушитель в fnd, импортирующая upl, делает правило красным")
    void fndDependingOnUplIsRed() {
        JavaClasses withViolator =
                new ClassFileImporter().importClasses(FndDependsOnUplViolator.class, UplModuleFixture.class);
        EvaluationResult result = fndDependsOnNoApplicationModuleRule().evaluate(withViolator);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString())
                .contains("FndDependsOnUplViolator")
                .contains("UplModuleFixture");
    }

    /** The framework's mf module keeps the files: the foundation has no storage of its own and never calls the SPI. */
    @Test
    @DisplayName("AC-8: в fnd нет своего FileStorage и зависимости от StorageProvider")
    void fndHasNoOwnFileStorage() {
        noClasses()
                .that()
                .resideInAPackage(FND)
                .should()
                .haveSimpleNameContaining("FileStorage")
                .orShould()
                .dependOnClassesThat()
                .areAssignableTo(StorageProvider.class)
                .check(main);
    }

    /** fnd never calls file deletion: the source file of a load is immutable. */
    @Test
    @DisplayName("AC-8: fnd не вызывает удаление файла в модуле mf")
    void fndNeverDeletesFiles() {
        noClasses()
                .that()
                .resideInAPackage(FND)
                .should()
                .callMethodWhere(JavaCall.Predicates.target(owner(assignableTo(MfFileService.class)))
                        .and(JavaCall.Predicates.target(name("deleteFile"))))
                .check(main);
    }

    @Test
    @DisplayName("AC-42: в fnd нет контроллеров и эндпоинтов")
    void fndHasNoControllers() {
        noClasses()
                .that()
                .resideInAPackage(FND)
                .should()
                .beAnnotatedWith(RestController.class)
                .orShould()
                .beAnnotatedWith(Controller.class)
                .check(main);
    }

    /**
     * The foundation has no scheduler: the instance chooses when jobs run. The rule leaves the framework's workers
     * alone.
     */
    static ArchRule noScheduledInFndRule() {
        return noMethods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAPackage(FND)
                .should()
                .beAnnotatedWith(Scheduled.class);
    }

    @Test
    @DisplayName("AC-7: @Scheduled отсутствует в ..instance.fnd.. (в модулях каркаса — есть, правило их не касается)")
    void fndHasNoScheduled() {
        noScheduledInFndRule().check(main);
        // Check that the rule really is narrow: the framework does use @Scheduled, and the import sees it
        boolean frameworkHasScheduled = main.stream()
                .filter(type -> !type.getPackageName().startsWith(ROOT + ".fnd"))
                .flatMap(type -> type.getMethods().stream())
                .anyMatch(method -> method.isAnnotatedWith(Scheduled.class));
        assertThat(frameworkHasScheduled)
                .as("в модулях каркаса есть @Scheduled")
                .isTrue();
    }

    @Test
    @DisplayName("AC-7: фикстура-нарушитель с @Scheduled в fnd делает правило красным")
    void fndScheduledViolatorIsRed() {
        JavaClasses withViolator = new ClassFileImporter().importClasses(FndScheduledViolator.class);
        EvaluationResult result = noScheduledInFndRule().evaluate(withViolator);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains("FndScheduledViolator");
    }
}
