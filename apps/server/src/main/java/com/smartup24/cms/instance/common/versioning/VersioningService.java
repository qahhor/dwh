package com.smartup24.cms.instance.common.versioning;

import com.smartup24.cms.instance.common.actor.AuditActorContext;
import com.smartup24.cms.instance.common.error.ConstraintCode;
import com.smartup24.cms.instance.common.error.ConstraintCodes;
import com.smartup24.cms.instance.common.error.ConstraintViolationException;
import com.smartup24.cms.platform.api.actor.AuditActor;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one mechanism of versions with an effective date for every module (V102). A module declares its versions table
 * in a migration by calling {@code fnd_versioning_enable}, and writes the version data (for example {@code factor})
 * to its own columns through {@link #updateDraft}. A platform mechanism with no business table of its own, so it
 * lives in {@code common} (plan 10/10, item 4.2, ADR-0030); its SQL is in {@link VersionRepository}.
 *
 * <p>Lifecycle: {@link #createDraft} (number = max + 1, at most one draft per header) → edits to the draft →
 * {@link #publish} (closes the previous version) → {@link #supersede} if the version was published by mistake.
 * The {@code deny_update_published()} trigger protects a published row.
 */
@Service
public class VersioningService implements Versions {

    /** A table or column name in dynamic SQL: only names we create ourselves in migrations. */
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    /** Columns owned by the versioning standard: they change only through this facade's own methods. */
    private static final Set<String> RESERVED_COLUMNS =
            Set.of("id", "status", "version", "valid_from", "valid_to", "lock_version", "published_at", "published_by");

    private final VersionRepository versions;
    private final AuditActorContext actors;
    /** The standard's codes and the codes of the modules whose versions tables it writes. */
    private final List<ConstraintCode> codes;
    /** table -&gt; header column; the fnd_versioned_tables registry changes only through migrations. */
    private final Map<String, String> headerColumns = new ConcurrentHashMap<>();

    @Autowired
    public VersioningService(VersionRepository versions, AuditActorContext actors, List<ConstraintCodes> moduleCodes) {
        this.versions = versions;
        this.actors = actors;
        List<ConstraintCode> all = new ArrayList<>(VersionErrors.CODES);
        moduleCodes.forEach(module -> all.addAll(module.codes()));
        this.codes = List.copyOf(all);
    }

    /** A facade built by hand, for tests and tools: it translates only the standard's own codes. */
    public VersioningService(JdbcClient jdbc, AuditActorContext actors) {
        this(new VersionRepository(jdbc), actors, List.of());
    }

    /**
     * Creates a draft with the next number. The draft's {@code valid_from} is a placeholder (the current date).
     * Assumption: a placeholder is acceptable because the column is declared {@code not null} and the effective
     * dates are set on publishing.
     *
     * @throws ConstraintViolationException {@code fnd_version_draft_exists} when the header already has a draft
     * @throws ConstraintViolationException {@code fnd_version_conflict} when a concurrent createDraft for the same
     *     header won the race
     */
    @Transactional
    @Override
    public int createDraft(String versionsTable, long headerId, AuditActor actor) {
        String header = headerColumn(versionsTable);
        actors.apply(actor);
        if (versions.draftVersion(versionsTable, header, headerId).isPresent()) {
            throw new ConstraintViolationException(VersionError.FND_VERSION_DRAFT_EXISTS);
        }
        return VersionErrors.translatingVersions(
                versionsTable, codes, () -> versions.insertDraft(versionsTable, header, headerId));
    }

    /**
     * Publishes a draft: closes the open end of the previous version on the day before {@code validFrom} and moves
     * the draft to {@code published}.
     *
     * @throws ConstraintViolationException {@code fnd_version_unknown} when there is no draft with that number;
     *                                      {@code fnd_version_not_after_previous} when the date is not later than
     *                                      the previous version's
     */
    @Transactional
    @Override
    public void publish(
            String versionsTable,
            long headerId,
            int version,
            LocalDate validFrom,
            @Nullable LocalDate validTo,
            AuditActor actor) {
        String header = headerColumn(versionsTable);
        actors.apply(actor);
        // The draft row is locked: a concurrent publish of the same version waits for the commit, then sees
        // published and is rejected, rather than "succeeding" with zero rows updated
        String status =
                versions.lockStatus(versionsTable, header, headerId, version).orElse(null);
        if (!Version.DRAFT.equals(status)) {
            throw new ConstraintViolationException(VersionError.FND_VERSION_UNKNOWN);
        }
        // The service, not a database constraint, checks the version order: on rejection the previous version stays
        // untouched and the draft stays a draft, because nothing has been written before this point.
        Optional<Version> previous = versions.latestPublished(versionsTable, header, headerId);
        if (previous.isPresent() && !validFrom.isAfter(previous.get().validFrom())) {
            throw new ConstraintViolationException(VersionError.FND_VERSION_NOT_AFTER_PREVIOUS);
        }
        int published = VersionErrors.translating(codes, () -> {
            if (previous.isPresent() && previous.get().validTo() == null) {
                versions.closeVersion(
                        versionsTable, header, headerId, previous.get().version(), validFrom.minusDays(1));
            }
            return versions.publishDraft(versionsTable, header, headerId, version, validFrom, validTo, actor.name());
        });
        if (published != 1) {
            // The draft vanished between the check and the update: the transaction rolls back, together with the
            // closing of the previous version
            throw new ConstraintViolationException(VersionError.FND_VERSION_UNKNOWN);
        }
    }

    /** Withdraws a version published by mistake: its interval is freed and the row stays as history. */
    @Transactional
    @Override
    public void supersede(String versionsTable, long headerId, int version, AuditActor actor) {
        String header = headerColumn(versionsTable);
        actors.apply(actor);
        int updated =
                VersionErrors.translating(codes, () -> versions.supersede(versionsTable, header, headerId, version));
        if (updated == 0) {
            throw new ConstraintViolationException(VersionError.FND_VERSION_UNKNOWN);
        }
    }

    /**
     * Updates draft columns under optimistic locking: a trigger increments {@code lock_version}, so a client holding
     * an old value gets {@link StaleVersionException}.
     */
    @Transactional
    @Override
    public void updateDraft(
            String versionsTable,
            long headerId,
            int version,
            int expectedLockVersion,
            Map<String, Object> columns,
            AuditActor actor) {
        String header = headerColumn(versionsTable);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Нечего обновлять: список колонок пуст");
        }
        Map<String, Object> checked = new LinkedHashMap<>();
        columns.forEach((column, value) -> {
            if (!IDENTIFIER.matcher(column).matches()) {
                throw new IllegalArgumentException("Недопустимое имя колонки: " + column);
            }
            String normalized = column.toLowerCase(Locale.ROOT);
            if (RESERVED_COLUMNS.contains(normalized) || normalized.equals(header)) {
                throw new IllegalArgumentException(
                        "Колонка управляется версионностью, правка через updateDraft запрещена: " + column);
            }
            checked.put(column, value);
        });
        actors.apply(actor);
        int updated = VersionErrors.translating(
                codes,
                () -> versions.updateDraft(versionsTable, header, headerId, version, expectedLockVersion, checked));
        if (updated == 0) {
            if (find(versionsTable, headerId, version).isEmpty()) {
                throw new ConstraintViolationException(VersionError.FND_VERSION_UNKNOWN);
            }
            throw new StaleVersionException();
        }
    }

    /** The number of the version in effect on the date; drafts and withdrawn versions are ignored. */
    @Transactional(readOnly = true)
    @Override
    public Optional<Integer> versionAt(String versionsTable, long headerId, LocalDate date) {
        headerColumn(versionsTable);
        return versions.versionAt(versionsTable, headerId, date);
    }

    /** A version row as stored, for modules and checks. */
    @Transactional(readOnly = true)
    @Override
    public Optional<Version> find(String versionsTable, long headerId, int version) {
        String header = headerColumn(versionsTable);
        return versions.find(versionsTable, header, headerId, version);
    }

    /** The header column from the registry; also checks that the table was declared under the versioning standard. */
    private String headerColumn(String versionsTable) {
        if (!IDENTIFIER.matcher(versionsTable).matches()) {
            throw new IllegalArgumentException("Недопустимое имя таблицы версий: " + versionsTable);
        }
        return headerColumns.computeIfAbsent(
                versionsTable,
                table -> versions.headerColumn(table)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Таблица " + table + " не объявлена через fnd_versioning_enable")));
    }
}
