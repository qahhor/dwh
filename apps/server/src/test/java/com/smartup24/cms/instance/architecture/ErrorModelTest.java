package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.config.error.GlobalExceptionHandler;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/**
 * Plan 10/10, item 3.1: one error model. Every exception the application defines is an {@link ApiException} — a
 * code, a catalog key and parameters — and {@link GlobalExceptionHandler} answers each of them as problem details.
 * Only exceptions that never end a request are internal, each with the reason written here.
 */
class ErrorModelTest {

    /** Internal exceptions: never the outcome of a request, so they are not part of the API's error model. */
    static final Map<String, String> INTERNAL = Map.of(
            "com.smartup24.cms.instance.warehouse.migration.SchemaVersionMismatchException",
            "stops the application at startup when the database schema does not match the build",
            "com.smartup24.cms.instance.report.service.ReportService$ClientAbortException",
            "the client closed the connection of a streamed export: there is no response left to write",
            "com.smartup24.cms.instance.search.repository.SearchProjectionReader$DocumentTooLargeException",
            "caught by the indexer, which skips the document and records it",
            "com.smartup24.cms.instance.search.typesense.TypesenseException",
            "a failure of the search engine, caught by its callers (search falls back to PostgreSQL)",
            "com.smartup24.cms.instance.warehouse.raw.JdbcRawWriter$CopyFailed",
            "carries a server refusal out of the COPY callback and is unwrapped by the writer itself",
            "com.smartup24.cms.instance.jobs.api.JobNotRetryableException",
            "ends a queued job, never a request: the job runner records it and marks the job failed at once",
            "com.smartup24.cms.instance.report.imports.ImportFailure",
            "ends an import job, never a request: the job records its code in the import's journal row",
            "com.smartup24.cms.instance.report.imports.ImportFile$Unreadable",
            "a file the import job cannot read, caught by the job and recorded as IMPORT_UNREADABLE",
            "com.smartup24.cms.instance.common.xlsx.XlsxGuard$Rejected",
            "an xlsx out of its limits (plan 10/10, item 7.6), caught by the import and upload readers as unreadable",
            "com.smartup24.cms.instance.common.module.ModuleManifestException",
            "stops the application at startup when a module cannot run on this platform (ADR-0033, 6.3)",
            "com.smartup24.cms.platform.api.entity.hook.EntityRefusal",
            "the refusal of a module's hook in the platform API; the runtime turns it into an ApiException (ADR-0033)");

    /**
     * Exceptions of modules not yet moved to the model. Empty since the fnd hierarchy joined it; kept so a temporary
     * exemption stays visible, and the check below fails when a listed class is already on the model.
     */
    static final Set<String> PENDING = Set.of();

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.smartup24.cms");
    }

    static List<JavaClass> applicationExceptions() {
        return classes.stream()
                .filter(javaClass -> javaClass.isAssignableTo(RuntimeException.class))
                .filter(javaClass -> !javaClass.isEquivalentTo(ApiException.class))
                .toList();
    }

    @Test
    @DisplayName("3.1: every exception of the application extends ApiException, or is internal with a reason")
    void everyExceptionIsAnApiException() {
        List<String> outside = applicationExceptions().stream()
                .filter(javaClass -> !javaClass.isAssignableTo(ApiException.class))
                .map(JavaClass::getName)
                .filter(name -> !INTERNAL.containsKey(name) && !PENDING.contains(name))
                .toList();
        assertThat(outside).as("exceptions outside the error model").isEmpty();

        List<String> done = PENDING.stream()
                .filter(name -> classes.contain(name))
                .filter(name -> classes.get(name).isAssignableTo(ApiException.class))
                .toList();
        assertThat(done).as("moved to the model: remove from PENDING").isEmpty();
    }

    @Test
    @DisplayName("3.1: the handler answers every subclass of ApiException with its problem details")
    void handlerCoversEverySubclass() throws Exception {
        var resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);
        Method expected = GlobalExceptionHandler.class.getMethod(
                "handleApiException", ApiException.class, jakarta.servlet.http.HttpServletRequest.class);
        for (JavaClass javaClass : applicationExceptions()) {
            if (!javaClass.isAssignableTo(ApiException.class)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Class<? extends Throwable> type = (Class<? extends Throwable>) javaClass.reflect();
            assertThat(resolver.resolveMethodByExceptionType(type))
                    .as(javaClass.getName())
                    .isEqualTo(expected);
        }
        assertThat(resolver.resolveMethodByExceptionType(ApiException.class)).isEqualTo(expected);
    }
}
