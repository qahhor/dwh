package com.smartup24.cms.instance.config.module;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The modules of the installation (ADR-0033, 6): their manifests are checked and the configuration of every module
 * outside the monorepo is imported, before any bean is created.
 */
@Configuration(proxyBeanMethods = false)
@Import(ModuleManifestSelector.class)
public class ModuleConfiguration {}
