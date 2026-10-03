package com.smartup24.cms.platform.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The deprecation gate of ADR-0033, 5 fails for what it should: a baseline jar is written with class files of a
 * released API that the current classes no longer have, and the policy reports exactly the stable ones that were not
 * deprecated before they went.
 */
class ApiPolicySelfTest {

    private static final String ROOT = "com.smartup24.cms.platform.api";
    private static final ClassDesc MARK = ClassDesc.of(ROOT + ".PlatformApi");
    private static final ClassDesc STABILITY = ClassDesc.of(ROOT + ".Stability");
    private static final ClassDesc DEPRECATED = ClassDesc.of("java.lang.Deprecated");

    @TempDir
    Path folder;

    @Test
    void aStableTypeGoneWithoutADeprecationIsReported() throws IOException {
        Path jar = jar(
                type(ROOT + ".fixture.GoneStable", "STABLE", false, List.of()),
                type(ROOT + ".fixture.GoneDeprecated", "STABLE", true, List.of()),
                type(ROOT + ".fixture.GoneExperimental", "EXPERIMENTAL", false, List.of()));

        assertThat(new ApiPolicy(ROOT).removedWithoutDeprecation(jar, PlatformApiContractTest.baseline()))
                .containsExactly(ROOT + ".fixture.GoneStable is gone without a deprecation first");
    }

    @Test
    void aStableMemberGoneWithoutADeprecationIsReported() throws IOException {
        // The released PlatformVersion had two more methods: one deprecated first, one not.
        Path jar = jar(type(
                ROOT + ".PlatformVersion",
                "STABLE",
                false,
                List.of(new Method("legacy", false), new Method("old", true))));

        assertThat(new ApiPolicy(ROOT).removedWithoutDeprecation(jar, PlatformApiContractTest.baseline()))
                .containsExactly(ROOT + ".PlatformVersion#legacy():void is gone without a deprecation first");
    }

    private record Method(String name, boolean deprecated) {}

    private record Type(String name, byte[] bytes) {}

    private static Type type(String name, String stability, boolean deprecated, List<Method> methods) {
        byte[] bytes = ClassFile.of().build(ClassDesc.of(name), type -> {
            type.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            type.withSuperclass(ConstantDescs.CD_Object);
            List<Annotation> annotations = new ArrayList<>();
            annotations.add(Annotation.of(
                    MARK,
                    AnnotationElement.ofString("since", "1.0"),
                    AnnotationElement.of("stability", AnnotationValue.ofEnum(STABILITY, stability))));
            if (deprecated) annotations.add(Annotation.of(DEPRECATED));
            type.with(RuntimeVisibleAnnotationsAttribute.of(annotations));
            for (Method method : methods) {
                type.withMethod(method.name(), MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC, body -> {
                    if (method.deprecated()) {
                        body.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(DEPRECATED)));
                    }
                    body.withCode(code -> code.return_());
                });
            }
        });
        return new Type(name, bytes);
    }

    private Path jar(Type... types) throws IOException {
        Path jar = folder.resolve("baseline.jar");
        try (OutputStream file = Files.newOutputStream(jar);
                JarOutputStream out = new JarOutputStream(file)) {
            for (Type type : types) {
                out.putNextEntry(new JarEntry(type.name().replace('.', '/') + ".class"));
                out.write(type.bytes());
                out.closeEntry();
            }
        }
        return jar;
    }
}
