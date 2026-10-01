set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: every grant, personal right, effective right and menu binding of a renamed form is copied to its new code
--         first; only then are the old catalog rows, and with them the copied grants, deleted. No user gains or loses
--         an effective right; the permission version of every user is raised so clients re-read the set.
-- approved_by: product owner (plan 10/10, item 4.4)
-- Plan 10/10, item 4.4 (ADR-0028): a form code is <area>.<entity-or-screen>, and the area names the owning module.
-- The forms below carried the area of a screen group (iam, rbac, platform) instead of the module that guards them.
-- Old codes are not kept in the catalog: requests that grant rights or bind a menu item still accept them until
-- 2026-12-31 (PermissionAreas.LEGACY_FORMS), the stored rows are converted here.
do $$
declare
    pair text[];
    old_code text;
    new_code text;
begin
    foreach pair slice 1 in array array[
        ['iam.users', 'md.users', 'md'],
        ['iam.profile', 'md.profile', 'md'],
        ['iam.org_units', 'md.org_units', 'md'],
        ['rbac.roles', 'md.roles', 'md'],
        ['rbac.assignments', 'md.assignments', 'md'],
        ['platform.settings', 'md.settings', 'md'],
        ['platform.navigation', 'md.navigation', 'md'],
        ['platform.modules', 'md.modules', 'md'],
        ['platform.announcements', 'notify.announcements', 'ms.notify'],
        ['platform.files', 'mf.files', 'mf'],
        ['platform.search', 'search', 'search'],
        ['platform.webhooks', 'webhook.subscriptions', 'kwh']
    ] loop
        old_code := pair[1];
        new_code := pair[2];

        -- The catalog: the new form takes the old one's names and state; the next start refreshes the names.
        insert into md_forms (code, module, name, is_deprecated)
        select new_code, pair[3], f.name, f.is_deprecated
        from md_forms f
        where f.code = old_code
        on conflict (code) do nothing;

        insert into md_form_actions (form_code, action, name, is_deprecated)
        select new_code, fa.action, fa.name, fa.is_deprecated
        from md_form_actions fa
        where fa.form_code = old_code
        on conflict (form_code, action) do nothing;

        -- Grants of roles and of single users, and their materialized union.
        insert into md_role_permissions (role_id, form_code, action)
        select rp.role_id, new_code, rp.action
        from md_role_permissions rp
        where rp.form_code = old_code
        on conflict do nothing;

        insert into md_user_permissions (user_id, form_code, action)
        select up.user_id, new_code, up.action
        from md_user_permissions up
        where up.form_code = old_code
        on conflict do nothing;

        insert into md_effective_permissions (user_id, form_code, action, source_role_id)
        select ep.user_id, new_code, ep.action, ep.source_role_id
        from md_effective_permissions ep
        where ep.form_code = old_code
        on conflict do nothing;

        -- A menu item bound to <form>.<action> of a renamed form; its revision moves, so a stale edit gets 409.
        update md_navigation_items
        set required_permission = new_code || substr(required_permission, length(old_code) + 1),
            revision = revision + 1
        where starts_with(required_permission, old_code || '.')
          and strpos(substr(required_permission, length(old_code) + 2), '.') = 0;

        -- The old rows go: the form cascades to its actions and to the role grants already copied above.
        delete from md_effective_permissions where form_code = old_code;
        delete from md_user_permissions where form_code = old_code;
        delete from md_forms where code = old_code;
    end loop;

    update md_user_permission_versions set permissions_version = permissions_version + 1;
end $$;
