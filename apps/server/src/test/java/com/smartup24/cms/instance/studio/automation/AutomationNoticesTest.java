package com.smartup24.cms.instance.studio.automation;

import static com.smartup24.cms.instance.studio.automation.AutomationFixtures.FORM;
import static com.smartup24.cms.instance.studio.automation.AutomationFixtures.run;
import static com.smartup24.cms.instance.studio.automation.AutomationFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.runtime.EntityReads;
import com.smartup24.cms.instance.common.entity.runtime.EntityRuntime;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.formula.Formula;
import com.smartup24.cms.instance.common.formula.FormulaType;
import com.smartup24.cms.instance.kauth.service.KauthDeliveryAddresses;
import com.smartup24.cms.instance.md.service.MdI18nService;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdUserTexts;
import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Right;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Texts;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.ActionResult;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.Recipient;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-0046: the recipients of a notification, who may see the record, each on the channels they keep. */
class AutomationNoticesTest {
    private final MdPermissionService permissions = mock(MdPermissionService.class);
    private final StudioAutomationRepository automations = mock(StudioAutomationRepository.class);
    private final EntityRegistry registry = mock(EntityRegistry.class);
    private final EntityReads reads = mock(EntityReads.class);
    private final MsNotificationService notifications = mock(MsNotificationService.class);
    private final KauthDeliveryAddresses addresses = mock(KauthDeliveryAddresses.class);
    private final MdI18nService i18n = mock(MdI18nService.class);
    private final MdUserTexts users = mock(MdUserTexts.class);
    private final AutomationTexts texts = new AutomationTexts(i18n);
    private final EntityDefinition entity = mock(EntityDefinition.class);

    AutomationNoticesTest() {
        when(registry.find(FORM)).thenReturn(Optional.of(entity));
        when(users.language(anyLong())).thenReturn("ru");
        when(users.text(anyLong(), anyString(), anyMap())).thenReturn("Открыть");
        when(i18n.effectiveDictionary(anyString())).thenReturn(Map.of("common.no", "Нет"));
    }

    private AutomationNotices notices(String publicUrl) {
        return new AutomationNotices(
                permissions, automations, registry, reads, notifications, addresses, texts, users, publicUrl);
    }

    private static AutomationSpec.Notify step(
            boolean author, List<Long> people, List<Right> rights, String... channels) {
        return new AutomationSpec.Notify(
                List.of(channels),
                List.of("manager", "note"),
                author,
                people,
                rights,
                new Texts("Заказ {note}", null, null),
                new Texts("Проверка _{review}_", null, null));
    }

    private static Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("manager", "7");
        values.put("note", "N-1");
        values.put("review", false);
        return values;
    }

    @Test
    @DisplayName("ADR-0046: each recipient who sees the record is told on the channels with a confirmed address")
    void recipientsAreToldOnTheirChannels() {
        when(automations.recordAuthor(FORM, 9)).thenReturn(Optional.of(8L));
        when(permissions.holders(FORM, java.util.Set.of("update"), AutomationNotices.MAX_RECIPIENTS))
                .thenReturn(List.of(9L));
        when(automations.activeUsers(anyCollection()))
                .thenReturn(new LinkedHashMap<>(Map.of(7L, "Менеджер", 8L, "Автор", 9L, "Старший", 10L, "Чужой")));
        when(permissions.activeHolder(anyLong(), eq(FORM), eq("view"))).thenReturn(true);
        when(permissions.activeHolder(10L, FORM, "view")).thenReturn(false);
        when(reads.inScopeOf(entity, 9, 7L)).thenReturn(true);
        when(reads.inScopeOf(entity, 9, 8L)).thenReturn(true);
        when(reads.inScopeOf(entity, 9, 9L)).thenReturn(false);
        when(notifications.isNotificationEnabled(anyLong(), eq(AutomationNotices.EVENT_TYPE), anyString()))
                .thenReturn(true);
        when(notifications.isNotificationEnabled(8L, AutomationNotices.EVENT_TYPE, "in_app"))
                .thenReturn(false);
        when(addresses.deliverable("email")).thenReturn(true);
        when(addresses.deliverable("telegram")).thenReturn(true);
        when(addresses.deliverable("sms")).thenReturn(false);
        when(addresses.confirmedAddress(7L, "email")).thenReturn(Optional.of("m@example.test"));
        when(addresses.confirmedAddress(7L, "telegram")).thenReturn(Optional.of("42"));
        when(addresses.confirmedAddress(8L, "email")).thenReturn(Optional.empty());
        when(addresses.confirmedAddress(8L, "telegram")).thenReturn(Optional.empty());

        ActionResult result = notices("https://cms.example.test/")
                .send(
                        2,
                        step(
                                true,
                                List.of(10L),
                                List.of(new Right(FORM, "update")),
                                "in_app",
                                "email",
                                "telegram",
                                "sms"),
                        run(false, values()),
                        false);
        assertThat(result.status()).isEqualTo("done");
        assertThat(result.recipients())
                .extracting(Recipient::id, Recipient::channels, Recipient::skipped)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(7L, List.of("in_app", "email", "telegram"), null),
                        org.assertj.core.groups.Tuple.tuple(8L, List.of(), "no_channel"),
                        org.assertj.core.groups.Tuple.tuple(9L, List.of(), "no_access"),
                        org.assertj.core.groups.Tuple.tuple(10L, List.of(), "no_access"));
        assertThat(result.message().title()).isEqualTo("Заказ N-1");
        verify(notifications)
                .sendInAppNotification(
                        eq(7L), eq("info"), eq("Заказ N-1"), anyString(), eq("/e/studio.orders/9"), anyString());
        verify(notifications)
                .enqueueExternalNotification(
                        eq("email"),
                        eq("m@example.test"),
                        eq(AutomationNotices.EVENT_TYPE),
                        any(),
                        eq(AutomationNotices.key("run-1", 2, 7L, "email")),
                        any());
        verify(notifications)
                .enqueueExternalNotification(
                        eq("telegram"), eq("42"), eq(AutomationNotices.EVENT_TYPE), any(), any(), any());
    }

    @Test
    @DisplayName(
            "ADR-0046: a dry run tells nobody; a deleted record needs the right alone; nobody gets the author's words")
    void dryRunsAndDeletedRecords() {
        List<Long> many = new ArrayList<>();
        for (long id = 100; id < 100 + AutomationNotices.MAX_RECIPIENTS + 5; id++) many.add(id);
        when(automations.activeUsers(anyCollection())).thenAnswer(call -> {
            java.util.Collection<Long> asked = call.getArgument(0);
            assertThat(asked).hasSizeLessThanOrEqualTo(AutomationNotices.MAX_RECIPIENTS);
            return Map.of(100L, "Первый");
        });
        when(permissions.activeHolder(100L, FORM, "view")).thenReturn(true);
        when(notifications.isNotificationEnabled(anyLong(), anyString(), anyString()))
                .thenReturn(true);
        when(addresses.deliverable("email")).thenReturn(true);
        when(addresses.confirmedAddress(100L, "email")).thenReturn(Optional.of("p@example.test"));
        ActionResult dry =
                notices("").send(0, step(true, many, List.of(), "in_app", "email"), run(true, values()), true);
        assertThat(dry.status()).isEqualTo("would_apply");
        assertThat(dry.recipients()).extracting(Recipient::channels).containsExactly(List.of("in_app", "email"));
        verify(automations, never()).recordAuthor(anyString(), anyLong());
        verify(notifications, never()).sendInAppNotification(anyLong(), any(), any(), any(), any(), any());

        when(automations.activeUsers(anyCollection())).thenReturn(Map.of());
        when(registry.find(FORM)).thenReturn(Optional.empty());
        ActionResult nobody = notices("https://cms.example.test")
                .send(1, step(false, List.of(), List.of(), "email"), run(false, Map.of("note", "x")), false);
        assertThat(nobody.reason()).isEqualTo("no_recipients");
        assertThat(nobody.message().body()).isEqualTo("Проверка __");
    }

    @Test
    @DisplayName("ADR-0046: the actions write values, create a record, move the status and notify, as their author")
    void actionsApplyThroughTheRuntime() {
        EntityRuntime runtime = mock(EntityRuntime.class);
        AutomationNotices notices = mock(AutomationNotices.class);
        AutomationActions actions = new AutomationActions(runtime, notices, texts, AutomationFixtures.JSON);
        Map<String, FormulaType> types = Map.of("note", FormulaType.TEXT, "manager", FormulaType.REF);
        AutomationRun run = run(false, values());
        when(runtime.update(eq(FORM), eq(9L), anyString(), any())).thenReturn(view(9, Map.of("note", "y")));
        when(runtime.create(eq("studio.tasks"), any())).thenReturn(view(40, Map.of()));
        when(runtime.action(eq(FORM), eq(9L), eq("submit"), anyString(), any())).thenReturn(view(9, Map.of()));

        ActionResult set = actions.apply(
                0,
                new AutomationSpec.SetFields(List.of(
                        new AutomationSpec.Assign("note", "text", Formula.compile("note & \"!\"", types)),
                        new AutomationSpec.Assign("manager", "user", Formula.compile("null", types)))),
                run,
                false);
        assertThat(set.values()).containsEntry("note", "N-1!").containsEntry("manager", "");
        assertThat(run.record()).containsEntry("note", "y");
        ActionResult created = actions.apply(
                1,
                new AutomationSpec.CreateRecord(
                        "studio.tasks",
                        List.of(new AutomationSpec.Assign("note", "text", Formula.compile("note", types)))),
                run,
                false);
        assertThat(created.recordId()).isEqualTo(40L);
        assertThat(actions.apply(2, new AutomationSpec.RunTransition("submit", "{note}"), run, false)
                        .values())
                .containsEntry("comment", "y");
        assertThat(actions.apply(3, new AutomationSpec.RunTransition("submit", null), run, false)
                        .values())
                .isEmpty();
        AutomationSpec.Notify notify = step(false, List.of(7L), List.of(), "in_app");
        when(notices.send(anyInt(), eq(notify), eq(run), eq(true)))
                .thenReturn(new ActionResult(4, "notify", "done", null, null, null, null, null, null, null));
        assertThat(actions.apply(4, notify, run, true).status()).isEqualTo("done");
        assertThatThrownBy(() -> actions.apply(
                        5,
                        new AutomationSpec.SetFields(List.of(new AutomationSpec.Assign(
                                "note", "text", Formula.compile("note & (1 / (len(note) - 1))", types)))),
                        run(false, Map.of("note", "x")),
                        false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("error.studio.automation_formula_failed");
    }

    @Test
    @DisplayName(
            "ADR-0046: moments of every kind read in the automation's zone; a text that only looks like a date stays")
    void momentsAndTextsReadAsAPersonReadsThem() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("at", OffsetDateTime.of(2026, 10, 19, 8, 30, 0, 0, ZoneOffset.ofHours(5)));
        values.put("stamp", Timestamp.from(AutomationFixtures.NOW));
        values.put("odd", "2026-13-45");
        values.put("review", false);
        assertThat(texts.fill("{at} {stamp} {odd} {review}", "ru", run(false, values)))
                .isEqualTo("19.10.2026 03:30 19.10.2026 08:00 2026-13-45 Нет");
    }
}
