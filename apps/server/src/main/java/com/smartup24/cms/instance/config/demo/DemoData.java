package com.smartup24.cms.instance.config.demo;

import com.smartup24.cms.instance.common.entity.runtime.EntityRecordView;
import com.smartup24.cms.instance.common.entity.runtime.EntityRuntime;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.config.bootstrap.InstanceBootstrapProperties;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Demo data of a local stand (plan 10/10, item 6.5): with the {@code demo} profile the first start after the instance
 * bootstrap adds a few users, projects, tasks, notes and orders, so a new developer sees filled screens at once. Off by
 * default: no other profile creates the bean.
 *
 * <p>Every record goes through the general entity runtime as the bootstrap administrator, with the same checks, hooks,
 * audit and invitations as a create from the screen (ADR-0032, 6). Idempotent: a record is looked up by its demo key
 * (a login, a name, a title, a customer) and created only when missing, so a restart adds nothing. The orders need the
 * module {@code example}, which ships switched off; the demo profile switches it on.
 */
@Component
@Profile("demo")
@Order(20)
public class DemoData implements ApplicationRunner {

    static final String ORDERS_MODULE = "example";

    private static final Logger log = LoggerFactory.getLogger(DemoData.class);

    private final EntityRuntime runtime;
    private final MdUserService users;
    private final MdPermissionService permissions;
    private final ModuleRegistryService modules;
    private final InstanceBootstrapProperties instance;
    private final ObjectMapper mapper;

    public DemoData(
            EntityRuntime runtime,
            MdUserService users,
            MdPermissionService permissions,
            ModuleRegistryService modules,
            InstanceBootstrapProperties instance,
            ObjectMapper mapper) {
        this.runtime = runtime;
        this.users = users;
        this.permissions = permissions;
        this.modules = modules;
        this.instance = instance;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Demo data: {} records created", seed());
    }

    /** Adds the missing demo records; answers how many it created. */
    int seed() {
        long adminId = users.findAuthUserByLogin(instance.adminLogin())
                .orElseThrow(() -> new IllegalStateException(
                        "Demo data needs the bootstrap administrator " + instance.adminLogin()))
                .id();
        modules.toggleModuleStatus(ORDERS_MODULE, true);
        SecurityContext.KauthPrincipal previous = SecurityContext.getPrincipal();
        SecurityContext.setPrincipal(principal(adminId));
        try {
            return seedAll();
        } finally {
            if (previous == null) SecurityContext.clear();
            else SecurityContext.setPrincipal(previous);
        }
    }

    private int seedAll() {
        int created = 0;
        List<Long> people = new ArrayList<>();
        for (DemoDataset.Person person : DemoDataset.PEOPLE) {
            Optional<Long> existing = users.findAuthUserByLogin(person.login()).map(MdUserService.AuthUser::id);
            if (existing.isPresent()) {
                people.add(existing.get());
                continue;
            }
            people.add(create("md.users", person.values()).id());
            created++;
        }
        for (DemoDataset.Project project : DemoDataset.PROJECTS) {
            Lookup found = findOrCreate("ms.projects", "name", project.name(), project.values());
            created += found.created() ? 1 : 0;
            created += seedTasks(found.id(), project.tasks(), people);
        }
        for (Map<String, Object> note : DemoDataset.NOTES) {
            created += findOrCreate("ms.notes", "title", (String) note.get("title"), note)
                            .created()
                    ? 1
                    : 0;
        }
        for (DemoDataset.Order order : DemoDataset.ORDERS) {
            created += seedOrder(order);
        }
        return created;
    }

    private int seedTasks(long projectId, List<DemoDataset.Task> tasks, List<Long> people) {
        int created = 0;
        for (DemoDataset.Task task : tasks) {
            Map<String, Object> values = new LinkedHashMap<>(task.values());
            values.put("projectId", projectId);
            values.put("responsibleId", people.get(task.responsible() % people.size()));
            created += findOrCreate("ms.tasks", "title", task.title(), values).created() ? 1 : 0;
        }
        return created;
    }

    private int seedOrder(DemoDataset.Order order) {
        Lookup found = findOrCreate("example.orders", "customer", order.customer(), order.values());
        if (found.created() && order.posted()) {
            EntityRecordView record = runtime.get("example.orders", found.id());
            runtime.action("example.orders", found.id(), "post", "\"" + record.revision() + "\"", null);
        }
        return found.created() ? 1 : 0;
    }

    private Lookup findOrCreate(String code, String field, String value, Map<String, Object> values) {
        String filter = mapper.writeValueAsString(List.of(Map.of("field", field, "op", "eq", "value", value)));
        List<EntityRecordView> page =
                runtime.list(code, 1, null, filter, null, null).items();
        if (!page.isEmpty()) {
            return new Lookup(page.getFirst().id(), false);
        }
        return new Lookup(create(code, values).id(), true);
    }

    private EntityRecordView create(String code, Map<String, Object> values) {
        return runtime.create(code, mapper.valueToTree(values));
    }

    private SecurityContext.KauthPrincipal principal(long adminId) {
        var admin = users.getUserIdentity(adminId);
        return new SecurityContext.KauthPrincipal(
                admin.id(),
                admin.login(),
                admin.email(),
                null,
                false,
                permissions.getEffectivePermissions(adminId),
                permissions.getPermissionVersion(adminId),
                false,
                admin.authenticationVersion(),
                null);
    }

    private record Lookup(long id, boolean created) {}
}
