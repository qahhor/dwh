package com.smartup24.cms.instance.config.module;

import com.smartup24.cms.instance.common.module.ModuleManifest;
import com.smartup24.cms.instance.common.module.ModuleManifests;
import com.smartup24.cms.platform.api.PlatformVersion;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Checks the module manifests while the configuration is parsed, before any bean exists (ADR-0033, 6.3; plan 10/10,
 * item 6.4), and imports the configuration of each module outside the monorepo. A module that needs a newer platform
 * API, another major version of it or a module that is missing stops the start: {@link ModuleManifestFailureAnalyzer}
 * prints the refusal.
 */
public class ModuleManifestSelector implements ImportSelector, BeanClassLoaderAware {

    private static final Logger log = LoggerFactory.getLogger(ModuleManifestSelector.class);

    private @Nullable ClassLoader loader;

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.loader = classLoader;
    }

    @Override
    public String[] selectImports(AnnotationMetadata metadata) {
        PlatformVersion platform = PlatformVersion.current();
        ClassLoader classes = loader != null ? loader : ModuleManifestSelector.class.getClassLoader();
        String[] imports = ModuleManifests.checked(classes, platform).stream()
                .peek(manifest -> log.info(
                        "module_manifest code={} version={} minPlatform={} platform={}",
                        manifest.code(),
                        manifest.version(),
                        manifest.minPlatform(),
                        platform))
                .map(ModuleManifest::configuration)
                .filter(Objects::nonNull)
                .toArray(String[]::new);
        return imports;
    }
}
