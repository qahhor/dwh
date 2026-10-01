package com.smartup24.cms.instance.upl;

import com.smartup24.cms.instance.fnd.units.FndUnitService;
import com.smartup24.cms.instance.md.service.MdAuditActors;
import com.smartup24.cms.instance.support.fixtures.DepartmentFixture;
import com.smartup24.cms.instance.support.fixtures.DepartmentFixture.Format;
import com.smartup24.cms.instance.support.fixtures.DepartmentFixture.FormatColumn;
import com.smartup24.cms.instance.support.fixtures.DepartmentFixture.Unit;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Column;
import com.smartup24.cms.instance.upl.format.UplFormatModel.DataType;
import com.smartup24.cms.instance.upl.format.UplFormatModel.FileKind;
import com.smartup24.cms.instance.upl.format.UplFormatModel.MatchBy;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Periodicity;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Sheet;
import com.smartup24.cms.instance.upl.format.UplFormatModel.SourceData;
import com.smartup24.cms.instance.upl.format.UplSourceService;
import com.smartup24.cms.instance.upl.format.UplSourceService.DraftData;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** The file format of a fixture as a load source: data shared by the instance configuration tests. */
public final class UplFixtureSources {

    /** The number of the first file format version. */
    public static final int FIRST_VERSION = 1;

    private static final String SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int SUFFIX_LENGTH = 6;

    private UplFixtureSources() {}

    /** A source with the first version of the fixture's file format published; returns the source id. */
    public static long publish(UplSourceService service, Format format, long userId) {
        long id = service.createSource(sourceData(format), userId).source().id();
        service.createDraft(id, null, userId);
        int lock = service.getVersion(id, FIRST_VERSION).lockVersion();
        service.replaceDraft(id, FIRST_VERSION, lock, draftData(format), userId);
        service.publish(id, FIRST_VERSION, format.validFrom(), userId);
        return id;
    }

    /** The fixture's units of measure in the instance database: base units first, then the units derived from them. */
    public static void registerUnits(FndUnitService units, MdAuditActors actors, DepartmentFixture dept) {
        dept.units().stream().filter(u -> u.code().equals(u.base())).forEach(u -> ensureUnit(units, actors, u));
        dept.units().stream().filter(u -> !u.code().equals(u.base())).forEach(u -> ensureUnit(units, actors, u));
    }

    private static void ensureUnit(FndUnitService units, MdAuditActors actors, Unit u) {
        if (units.findUnit(u.code()).isEmpty()) {
            units.registerUnit(u.code(), Map.of("uz", u.nameUz()), u.base(), actors.system());
        }
    }

    public static SourceData sourceData(Format f) {
        return new SourceData(
                f.code() + "-" + randomSuffix(),
                f.name(),
                f.ownerOrg(),
                null,
                Periodicity.fromDb(f.periodicity()),
                f.slaDays(),
                null,
                null);
    }

    public static DraftData draftData(Format f) {
        List<Sheet> sheets = f.sheets().stream()
                .map(s -> new Sheet(
                        null,
                        0,
                        s.sheetName(),
                        s.headerRow(),
                        s.totalRowMarker(),
                        s.columns().stream().map(UplFixtureSources::column).toList()))
                .toList();
        return new DraftData(
                FileKind.fromDb(f.fileKind()), f.encoding(), f.delimiter(), MatchBy.fromDb(f.matchColumnsBy()), sheets);
    }

    private static Column column(FormatColumn c) {
        return new Column(
                null,
                0,
                c.filePosition(),
                c.name(),
                c.field(),
                DataType.fromDb(c.type()),
                c.required(),
                c.sourceUnit(),
                c.baseUnit(),
                c.keyMask(),
                c.keyPadLength(),
                c.keyPadMax(),
                c.refBook());
    }

    private static String randomSuffix() {
        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            suffix.append(SUFFIX_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(SUFFIX_ALPHABET.length())));
        }
        return suffix.toString();
    }
}
