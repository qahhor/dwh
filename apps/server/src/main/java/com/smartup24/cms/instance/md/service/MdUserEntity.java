package com.smartup24.cms.instance.md.service;

import static com.smartup24.cms.instance.common.entity.field.EntityFields.bool;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.email;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.hidden;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.instant;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.listed;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.multiRef;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.phone;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.ref;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.searchable;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.select;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.sortable;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.text;

import com.smartup24.cms.instance.common.entity.Entity;
import com.smartup24.cms.instance.common.entity.EntityCapability;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityMenu;
import com.smartup24.cms.instance.common.entity.EntityScope;
import com.smartup24.cms.instance.common.entity.field.FieldDefault;
import com.smartup24.cms.instance.common.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.instance.common.query.QueryRef;
import com.smartup24.cms.instance.md.pref.MdPref;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The user entity (ADR-0032, 8; plan 10/10, item 5.6): the general runtime serves the accounts at
 * {@code /api/v1/entities/md.users} — list, read, create, change and the record actions — with the data scope of
 * ADR-0013 for users (a user whose home or additional unit lies in the viewer's scope, {@code SELF} — oneself), the
 * field rules, the audit and the events. {@link MdUserHooks} keeps what the accounts need beyond the declaration
 * (unique login, e-mail and phone, the unit in the author's scope, the default role, the invitation, the security
 * effects of the actions) and {@link MdUserActions} the actions block, unblock, reset of the second factor, forced
 * password change and anonymisation.
 *
 * <p>The password hash, the authentication generation and the avatar are no fields: the declaration never reads them,
 * so no record, list, export, history or webhook carries them. Roles and personal rights stay the assignment matrix
 * ({@code md.assignments}); the record shows the role ids to holders of its {@code view} right only.
 */
@Configuration
public class MdUserEntity {

    /** The entity's code, its list's and its right's (ADR-0028). */
    public static final String CODE = MdPref.FORM_USERS;

    /** The record actions besides create and update, each with the right it needs. */
    public static final String BLOCK = "block";

    public static final String UNBLOCK = "unblock";
    public static final String RESET_2FA = "reset_2fa";
    public static final String ENABLE_2FA = "enable_2fa";
    public static final String FORCE_PASSWORD_CHANGE = "force_password_change";
    public static final String ANONYMIZE = "anonymize";

    /** Field keys the hooks and the actions read. */
    static final String NAME = "name";

    static final String LOGIN = "login";
    static final String EMAIL = "email";
    static final String PHONE = "phone";
    static final String STATE = "state";
    static final String ORG_UNIT = "orgUnitId";
    static final String MANAGER = "managerId";
    static final String TIMEZONE = "timezone";
    static final String TWO_FACTOR = "is2faEnabled";
    /** Whether the user must change the password at the next sign-in; the key names no secret (ADR-0029). */
    static final String FORCE_CHANGE = "credentialChangeRequired";

    /** A login: letters, digits, dot, dash and underscore; kept in lower case. */
    private static final String LOGIN_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]*$";

    /** A language tag of the catalogs ({@code ru}, {@code uz}, {@code en}, {@code uz-Cyrl}). */
    private static final String LANGUAGE_PATTERN = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$";

    /** A time zone of the IANA database ({@code Asia/Tashkent}) or {@code UTC}; the hook checks it exists. */
    private static final String ZONE_PATTERN = "^(UTC|[A-Z][A-Za-z_]+(/[A-Za-z0-9_+-]+)+)$";

    private static final String ROLE_IDS =
            "array(select ur.role_id from md_user_roles ur where ur.user_id = u.id order by ur.role_id)";

    /** The user entity with the users' data scope of ADR-0013, built by the md module's scope service. */
    public static EntityDefinition definition(EntityScope.ScopeProvider users) {
        Entity entity = Entity.define(CODE, MdPref.FORM_USERS)
                .table("md_users", "u")
                .scope(EntityScope.custom("users", users))
                .rights(
                        MdPref.MODULE_CODE,
                        "iam.users.rights.form",
                        Map.of(
                                "view",
                                "iam.users.rights.view",
                                "create",
                                "iam.users.rights.create",
                                "update",
                                "iam.users.rights.update",
                                BLOCK,
                                "iam.users.rights.block",
                                UNBLOCK,
                                "iam.users.rights.unblock",
                                "delete",
                                "iam.users.rights.delete"))
                .menu(new EntityMenu("nav.users", "people", "iam", 10, null));
        profile(entity);
        work(entity);
        security(entity);
        return entity.section("profile", "iam.users.section.profile", NAME, LOGIN, EMAIL, PHONE)
                .section("work", "iam.users.section.work", ORG_UNIT, MANAGER, "language", TIMEZONE)
                .section("security", "iam.users.section.security", STATE, TWO_FACTOR, FORCE_CHANGE)
                .actions("create", "update", BLOCK, UNBLOCK)
                .action(RESET_2FA, "update")
                .action(ENABLE_2FA, "update")
                .action(FORCE_PASSWORD_CHANGE, "update")
                // Anonymisation takes the place of a delete and needs its right (ADR-0032, 8).
                .action(ANONYMIZE, "delete")
                .defaultSort(NAME, Entity.Sort.ASC)
                .customFields("USER")
                .capabilities(EntityCapability.SAVED_VIEWS, EntityCapability.EXPORT, EntityCapability.HISTORY)
                .build();
    }

    /** Who the user is: the name, the login, the e-mail, the phone and the state. */
    private static void profile(Entity entity) {
        entity.field(text(NAME, "iam.users.col.name")
                        .column("name")
                        .required()
                        .length(1, 255)
                        .list(sortable().searchable()))
                .field(text(LOGIN, "iam.users.col.login")
                        .column("login")
                        .required()
                        .length(3, 50)
                        .matching(LOGIN_PATTERN)
                        .readonlyOnUpdate()
                        .list(sortable().searchable().hidden()))
                .field(email(EMAIL, "iam.users.col.email")
                        .column("email")
                        .required()
                        .readonlyOnUpdate()
                        .list(sortable().searchable()))
                .field(phone(PHONE, "iam.users.col.phone")
                        .column("phone")
                        .list(searchable().hidden()))
                .field(select(
                                STATE,
                                "iam.users.col.state",
                                List.of(MdPref.STATE_ACTIVE, MdPref.STATE_PASSIVE),
                                "iam.users.state.")
                        .column("state")
                        .readonly());
    }

    /** Where the user works: the home unit, the manager, the language and the time zone. */
    private static void work(Entity entity) {
        entity.field(ref(ORG_UNIT, "iam.users.col.org_unit", QueryRef.whole("/iam/org-units", "name"))
                        .column("org_unit_id")
                        .defaultValue(FieldDefault.currentOrgUnit())
                        .readonlyUnless(MdPref.FORM_ORG_UNITS, "assign")
                        .list(hidden()))
                .field(ref(MANAGER, "iam.users.col.manager")
                        .column("manager_id")
                        .target(CODE, NAME)
                        .list(hidden()))
                .field(text("language", "iam.common.language")
                        .column("language")
                        .length(2, 16)
                        .matching(LANGUAGE_PATTERN)
                        .defaultValue(FieldDefault.fixed("ru"))
                        .list(hidden()))
                .field(text(TIMEZONE, "iam.common.time_zone")
                        .column("timezone")
                        .length(3, 64)
                        .matching(ZONE_PATTERN)
                        .defaultValue(FieldDefault.fixed("UTC"))
                        .list(hidden()));
    }

    /** The security flags the actions set, the roles and the system times. */
    private static void security(Entity entity) {
        entity.field(bool(TWO_FACTOR, "iam.users.col.two_factor")
                        .column("is_2fa_enabled")
                        .readonly())
                .field(bool(FORCE_CHANGE, "iam.common.force_password_change")
                        .column("force_password_change")
                        .readonly()
                        .list(hidden()))
                .field(multiRef("roleIds", "iam.users.col.roles", QueryRef.whole("/iam/roles", "name"))
                        .expression(ROLE_IDS)
                        .listOnly(listed())
                        .requires(MdPref.FORM_ASSIGNMENTS, "view"))
                .field(instant("createdAt", "iam.users.col.created_at")
                        .system(SystemColumn.CREATED_AT)
                        .list(sortable()))
                .field(instant("modifiedAt", "iam.users.col.modified_at")
                        .system(SystemColumn.MODIFIED_AT)
                        .list(sortable().hidden()));
    }

    @Bean
    public EntityDefinition mdUsersEntity(ObjectProvider<MdScopeService> scopes) {
        return definition((userId, alias) -> scopes.getObject().filterForUsers(userId, alias + ".id"));
    }
}
