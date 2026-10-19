package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.SolutionPackage;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.Template;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The templates the product ships (ADR-0047): solution packages under {@code studio/templates/} of the application,
 * read and verified like an upload every time they are asked for, so a template that does not verify is never offered
 * as working. A test imports each into an empty installation.
 */
@Component
@Profile("!migrate")
public class SolutionTemplates {
    static final String LOCATION = "classpath*:studio/templates/*.json";
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9-]{1,39}$");

    private final SolutionFormat format;

    /** A template: its verified package and the bytes it was read from. */
    public record Shipped(SolutionPackage pack, byte[] bytes) {}

    public SolutionTemplates(SolutionFormat format) {
        this.format = format;
    }

    public List<Template> list() {
        List<Template> templates = new ArrayList<>();
        for (Shipped shipped : all()) {
            SolutionPackage pack = shipped.pack();
            templates.add(new Template(
                    pack.code(),
                    pack.version(),
                    pack.title(),
                    Objects.requireNonNullElse(pack.description(), pack.title()),
                    pack.forms().size(),
                    pack.lists().size(),
                    pack.reports().size(),
                    pack.mailings().size(),
                    pack.automations().size()));
        }
        templates.sort(Comparator.comparing(Template::code));
        return List.copyOf(templates);
    }

    public Shipped get(String code) {
        if (!CODE.matcher(code).matches()) throw notFound();
        return all().stream()
                .filter(shipped -> shipped.pack().code().equals(code))
                .findFirst()
                .orElseThrow(SolutionTemplates::notFound);
    }

    /** Every template shipped, each verified. */
    public List<Shipped> all() {
        List<Shipped> shipped = new ArrayList<>();
        for (Resource resource : resources()) {
            byte[] bytes = read(resource);
            shipped.add(new Shipped(format.read(bytes), bytes));
        }
        return List.copyOf(shipped);
    }

    private static Resource[] resources() {
        try {
            return new PathMatchingResourcePatternResolver().getResources(LOCATION);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("The templates of solution packages are not readable", unreadable);
        }
    }

    private static byte[] read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            byte[] bytes = in.readNBytes(StudioPackageDtos.MAX_BYTES + 1);
            SolutionFormat.requireSize(bytes.length);
            return bytes;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("A template of solution packages is not readable", unreadable);
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.studio.template_not_found", Map.of());
    }
}
