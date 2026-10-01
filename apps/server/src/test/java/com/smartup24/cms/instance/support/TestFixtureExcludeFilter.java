package com.smartup24.cms.instance.support;

import java.io.IOException;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * Removes the framework's test configurations from the component scan of {@code @SpringBootTest}.
 *
 * <p>The framework tests are slices ({@code @WebMvcTest}) with fixtures such as
 * {@code SearchSettingsIntegrationTestSupport.Fixture}: {@code @Configuration} classes in the test
 * sources that start Testcontainers and replace {@code dataSource}/{@code jdbcClient}.
 * The standard {@code TestTypeExcludeFilter} does not exclude them (they have no {@code @Test} methods),
 * and the full context fails without Docker. Everything in {@code target/test-classes} is excluded;
 * an explicit {@code @Import} in a test is not affected by this filter.
 */
public class TestFixtureExcludeFilter extends TypeExcludeFilter {

    @Override
    public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
            throws IOException {
        String location = metadataReader.getResource().getURI().toString().replace('\\', '/');
        return location.contains("/test-classes/");
    }

    @Override
    public boolean equals(Object other) {
        return other != null && getClass() == other.getClass();
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
