package com.smartup24.cms.instance.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.smartup24.cms.instance.common.json.JsonColumns;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, item 3.11: a repository writes and reads its JSON columns through {@link JsonColumns}, which logs the
 * table of a failure and never turns a broken document into an empty one; it does not call the mapper itself.
 */
class JsonColumnsArchitectureTest {

    /**
     * The mapper calls that turn values into JSON text or back. {@code writeValueAsBytes} is not among them: bytes are
     * hashed (the search fingerprint), a column stores text.
     */
    private static final Set<String> SERIALIZING =
            Set.of("writeValueAsString", "readValue", "readTree", "treeToValue", "convertValue");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.smartup24.cms.instance");
    }

    @Test
    @DisplayName("3.11: repositories write and read JSON columns through JsonColumns, not the mapper")
    void repositoriesUseJsonColumns() {
        rule().check(classes);
    }

    static ArchRule rule() {
        return noClasses()
                .that()
                .resideInAPackage("..repository..")
                .should()
                .callMethodWhere(DescribedPredicate.describe(
                        "a serializing method of ObjectMapper",
                        (JavaMethodCall call) -> call.getTargetOwner().isAssignableTo(ObjectMapper.class)
                                && SERIALIZING.contains(call.getName())))
                .because("a JSON column goes through JsonColumns (plan 10/10, item 3.11)");
    }
}
