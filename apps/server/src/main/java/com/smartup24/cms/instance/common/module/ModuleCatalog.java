package com.smartup24.cms.instance.common.module;

import com.smartup24.cms.platform.api.PlatformVersion;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * The modules this installation runs, from their manifests (ADR-0033, 6; plan 10/10, item 6.4), in dependency order.
 * The manifests were checked before the first bean ({@code config.module.ModuleManifestSelector}); the registry shows
 * their versions, the migrate step applies their migrations and the catalogs take their messages from here.
 */
@Component
public class ModuleCatalog {

    private final PlatformVersion platform;
    private final List<ModuleManifest> manifests;
    private final Map<String, ModuleManifest> byCode;

    @Autowired
    public ModuleCatalog(ResourceLoader resources) {
        this(PlatformVersion.current(), ModuleManifests.checked(loader(resources), PlatformVersion.current()));
    }

    /** The class loader of the application's resources: the one the module jars are on. */
    public static ClassLoader loader(ResourceLoader resources) {
        ClassLoader loader = resources.getClassLoader();
        return loader != null ? loader : ModuleCatalog.class.getClassLoader();
    }

    public ModuleCatalog(PlatformVersion platform, List<ModuleManifest> manifests) {
        this.platform = platform;
        this.manifests = List.copyOf(manifests);
        Map<String, ModuleManifest> codes = new LinkedHashMap<>();
        manifests.forEach(manifest -> codes.put(manifest.code(), manifest));
        this.byCode = Map.copyOf(codes);
    }

    /** The version of the platform's API this installation provides. */
    public PlatformVersion platform() {
        return platform;
    }

    /** Every module, each after the modules it needs. */
    public List<ModuleManifest> manifests() {
        return manifests;
    }

    public Optional<ModuleManifest> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }
}
