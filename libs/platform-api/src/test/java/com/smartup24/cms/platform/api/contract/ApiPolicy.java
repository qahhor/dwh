package com.smartup24.cms.platform.api.contract;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.PlatformVersion;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The rules every artifact of the platform's API keeps (ADR-0033, 3–5), shared by {@code platform-api} and
 * {@code provider-spi} through the test jar of {@code platform-api}:
 *
 * <ul>
 *   <li>every public type of its packages carries {@link PlatformApi}, with a {@code since} no later than the current
 *       version;
 *   <li>its types depend on nothing but the JDK, the nullness annotations and the platform's API itself;
 *   <li>a public type or member of the last released version (the baseline jar) that is gone was deprecated there, or
 *       was experimental (ADR-0033, 5).
 * </ul>
 */
public final class ApiPolicy {

    private static final Pattern SINCE = Pattern.compile("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$");
    private static final String API = "com.smartup24.cms.platform.api";

    private final String rootPackage;

    public ApiPolicy(String rootPackage) {
        this.rootPackage = rootPackage;
    }

    /** The production classes of the artifact's packages. */
    public JavaClasses importedClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages(rootPackage);
    }

    /** Every public type, nested ones included, is marked as part of the contract. */
    public void everyPublicTypeIsMarked() {
        classes()
                .that()
                .arePublic()
                .and()
                .resideInAPackage(rootPackage + "..")
                .should()
                .beAnnotatedWith(PlatformApi.class)
                .because("every public type of the platform's API states its version and stability (ADR-0033, 3.1)")
                .check(importedClasses());
    }

    /** The types of the API depend only on the JDK, the nullness annotations and the API. */
    public void dependsOnNothingElse() {
        DescribedPredicate<JavaClass> outside = DescribedPredicate.describe(
                "outside the JDK, org.jspecify and the platform's API",
                type -> !type.isPrimitive()
                        && !type.isArray()
                        && !type.getPackageName().startsWith("java.")
                        && !type.getPackageName().startsWith("org.jspecify")
                        && !type.getPackageName().startsWith(API)
                        && !type.getPackageName().startsWith(rootPackage));
        noClasses()
                .that()
                .resideInAPackage(rootPackage + "..")
                .should()
                .dependOnClassesThat(outside)
                .because("a module outside the monorepo builds against the API alone (ADR-0033, 3.1)")
                .check(importedClasses());
    }

    /** The {@code since} of every marked type is {@code MAJOR.MINOR}, no later than the current version. */
    public List<String> sinceProblems() {
        PlatformVersion current = PlatformVersion.current();
        List<String> problems = new ArrayList<>();
        for (JavaClass type : importedClasses()) {
            if (!type.isAnnotatedWith(PlatformApi.class)) continue;
            String since = type.getAnnotationOfType(PlatformApi.class).since();
            if (!SINCE.matcher(since).matches()) {
                problems.add(type.getName() + ": since " + since + " is not MAJOR.MINOR");
                continue;
            }
            PlatformVersion added = PlatformVersion.parse(since + ".0");
            if (added.compareTo(current) > 0) {
                problems.add(type.getName() + ": since " + since + " is later than the API " + current);
            }
        }
        return problems;
    }

    /**
     * The public types and members of the baseline jar that the current classes no longer have and that were neither
     * deprecated there nor experimental (ADR-0033, 5).
     *
     * @param alongside the baseline jars of the API artifacts it depends on, so their marks read
     */
    public List<String> removedWithoutDeprecation(Path baselineJar, Path... alongside) {
        List<URL> urls = new ArrayList<>();
        for (Path jar : prepend(baselineJar, alongside)) {
            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException("No baseline jar of the API at " + jar);
            }
            urls.add(url(jar));
        }
        List<String> problems = new ArrayList<>();
        try (URLClassLoader baseline =
                        new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
                JarFile jar = new JarFile(baselineJar.toFile())) {
            List<String> names = jar.stream()
                    .map(entry -> entry.getName())
                    .filter(name -> name.endsWith(".class") && !name.endsWith("package-info.class"))
                    .map(name ->
                            name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                    .filter(name -> name.startsWith(rootPackage + "."))
                    .sorted()
                    .toList();
            for (String name : names) {
                Class<?> old = Class.forName(name, false, baseline);
                if (!isPublicApi(old)) continue;
                problems.addAll(compare(old));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("The baseline jar lists a class it does not hold", e);
        }
        return problems;
    }

    private static List<Path> prepend(Path first, Path... rest) {
        List<Path> all = new ArrayList<>();
        all.add(first);
        all.addAll(Arrays.asList(rest));
        return all;
    }

    private static URL url(Path jar) {
        try {
            return jar.toUri().toURL();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> compare(Class<?> old) {
        boolean free = experimental(old) || deprecated(old.getAnnotations());
        Class<?> now;
        try {
            now = Class.forName(old.getName(), false, ApiPolicy.class.getClassLoader());
        } catch (ClassNotFoundException gone) {
            return free ? List.of() : List.of(old.getName() + " is gone without a deprecation first");
        }
        if (free) return List.of();
        Map<String, AnnotatedElement> current = members(now);
        List<String> problems = new ArrayList<>();
        members(old).forEach((signature, member) -> {
            if (!current.containsKey(signature) && !deprecated(member.getAnnotations())) {
                problems.add(old.getName() + "#" + signature + " is gone without a deprecation first");
            }
        });
        return problems;
    }

    /** The public and protected constructors, methods and fields declared by the type, by signature. */
    private static Map<String, AnnotatedElement> members(Class<?> type) {
        Map<String, AnnotatedElement> members = new TreeMap<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (visible(constructor.getModifiers())) {
                members.put("<init>" + parameters(constructor.getParameterTypes()), constructor);
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (visible(method.getModifiers()) && !method.isSynthetic() && !method.isBridge()) {
                members.put(
                        method.getName() + parameters(method.getParameterTypes()) + ":"
                                + method.getReturnType().getName(),
                        method);
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (visible(field.getModifiers()) && !field.isSynthetic()) {
                members.put(field.getName() + ":" + field.getType().getName(), field);
            }
        }
        return Collections.unmodifiableMap(members);
    }

    private static String parameters(Class<?>[] types) {
        return Arrays.stream(types).map(Class::getName).collect(Collectors.joining(",", "(", ")"));
    }

    private static boolean visible(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static boolean isPublicApi(Class<?> type) {
        return Modifier.isPublic(type.getModifiers()) && marking(type) != null;
    }

    private static boolean experimental(Class<?> type) {
        Annotation mark = marking(type);
        if (mark == null) return false;
        try {
            Object stability = mark.annotationType().getMethod("stability").invoke(mark);
            return "EXPERIMENTAL".equals(String.valueOf(stability));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The baseline's @PlatformApi has no stability", e);
        }
    }

    private static Annotation marking(Class<?> type) {
        return Arrays.stream(type.getAnnotations())
                .filter(annotation -> annotation.annotationType().getName().equals(PlatformApi.class.getName()))
                .findFirst()
                .orElse(null);
    }

    private static boolean deprecated(Annotation[] annotations) {
        Set<String> names = Arrays.stream(annotations)
                .map(annotation -> annotation.annotationType().getName())
                .collect(Collectors.toSet());
        return names.contains(Deprecated.class.getName());
    }
}
