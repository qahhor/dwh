package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.studio.api.StudioListDtos.CreateItem;
import com.smartup24.cms.instance.studio.api.StudioListDtos.CreateList;
import com.smartup24.cms.instance.studio.api.StudioListDtos.Item;
import com.smartup24.cms.instance.studio.api.StudioListDtos.ListDetail;
import com.smartup24.cms.instance.studio.api.StudioListDtos.UpdateItem;
import com.smartup24.cms.instance.studio.api.StudioListDtos.UpdateList;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.ListItem;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageList;
import com.smartup24.cms.instance.studio.repository.StudioFormVersionRepository;
import com.smartup24.cms.instance.studio.repository.StudioFormVersionRepository.Head;
import com.smartup24.cms.instance.studio.service.StudioActivationService;
import com.smartup24.cms.instance.studio.service.StudioListService;
import com.smartup24.cms.instance.studio.service.StudioVersionService;
import com.smartup24.cms.instance.studio.solution.SolutionItems.Done;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The reference lists and the live forms of a solution package (ADR-0047), through the paths an administrator uses.
 * A list that exists gets the items it lacks and the names, orders and states the package gives; items the package
 * does not name stay as they are. A form that exists gets a new version under the compatible-change rules of ADR-0041,
 * unless its active version is the package's.
 */
@Component
@Profile("!migrate")
public class SolutionCatalogSteps {
    private final StudioListService lists;
    private final StudioActivationService activation;
    private final StudioVersionService versions;
    private final StudioFormVersionRepository heads;

    public SolutionCatalogSteps(
            StudioListService lists,
            StudioActivationService activation,
            StudioVersionService versions,
            StudioFormVersionRepository heads) {
        this.lists = lists;
        this.activation = activation;
        this.versions = versions;
        this.heads = heads;
    }

    public Done list(PackageList list) {
        require("studio.lists", "manage");
        if (!lists.exists(list.code())) {
            lists.create(new CreateList(list.code(), list.name()));
            for (ListItem item : list.items()) add(list.code(), item);
            return new Done(SolutionItems.CREATE);
        }
        ListDetail current = lists.get(list.code());
        boolean changed = false;
        if (!same(current.list().name(), list.name())) {
            lists.rename(list.code(), Revisions.etag(current.list().revision()), new UpdateList(list.name()));
            changed = true;
        }
        Map<String, Item> held = current.items().stream().collect(Collectors.toMap(Item::code, Function.identity()));
        for (ListItem item : list.items()) {
            Item existing = held.get(item.code());
            if (existing == null) {
                add(list.code(), item);
                changed = true;
            } else if (!same(existing.name(), item.name())
                    || existing.sortOrder() != item.sortOrder()
                    || existing.active() != item.active()) {
                lists.updateItem(
                        list.code(),
                        item.code(),
                        Revisions.etag(existing.revision()),
                        new UpdateItem(item.name(), item.sortOrder(), item.active()));
                changed = true;
            }
        }
        return new Done(changed ? SolutionItems.UPGRADE : SolutionItems.UNCHANGED);
    }

    public Done form(DeliveryPackage form) {
        require("studio.forms", "publish");
        String code = "studio." + form.declaration().entity().name();
        Optional<Head> head = heads.head(code);
        if (head.isEmpty()) {
            activation.activate(form);
            return new Done(SolutionItems.CREATE);
        }
        if (head.get().checksum().equals(form.checksum())) {
            Done done = new Done(SolutionItems.UNCHANGED);
            return head.get().archived() ? done.with("studio.packages.note.archived") : done;
        }
        versions.newVersion(code, head.get().revision(), form);
        return new Done(SolutionItems.UPGRADE);
    }

    private void add(String code, ListItem item) {
        Item added = lists.addItem(code, new CreateItem(item.code(), item.name(), item.sortOrder()));
        if (!item.active()) {
            lists.updateItem(
                    code,
                    item.code(),
                    Revisions.etag(added.revision()),
                    new UpdateItem(item.name(), item.sortOrder(), false));
        }
    }

    static boolean same(Labels a, Labels b) {
        return a.ru().equals(b.ru()) && a.uz().equals(b.uz()) && a.en().equals(b.en());
    }

    static void require(String form, String action) {
        if (!SecurityContext.hasPermission(form, action)) throw ApiException.permissionDenied(form, action);
    }
}
