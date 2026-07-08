package io.forgetdm.mainframe;

import io.forgetdm.core.copybook.Copybook;
import io.forgetdm.core.copybook.CopybookParser;
import io.forgetdm.core.copybook.Field;
import io.forgetdm.core.copybook.Picture;
import io.forgetdm.core.copybook.Usage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared copybook helpers used by both the controller (UI) and the masking service. */
public final class CopybookSupport {

    private CopybookSupport() {}

    /** A structural (de-subscripted) elementary field, used to build the field -> mask map. */
    public record FieldInfo(String path, String type, int offset, int length, boolean numeric) {}

    public static Copybook parse(String source) { return CopybookParser.parse(source); }

    public static String stripSubscripts(String path) {
        return path == null ? null : path.replaceAll("\\(\\d+\\)", "");
    }

    public static String typeLabel(Field f) {
        String pic = f.rawPicture() == null ? "" : " " + f.rawPicture();
        return f.effectiveUsage() + pic;
    }

    public static boolean numeric(Field f) {
        Usage u = f.effectiveUsage();
        if (u == Usage.COMP || u == Usage.COMP3 || u == Usage.COMP5) return true;
        return f.picture() != null && f.picture().category() == Picture.Category.NUMERIC;
    }

    /** Unique structural fields (OCCURS collapsed to one entry) in declaration order. */
    public static List<FieldInfo> structuralFields(Copybook cb, Field record) {
        Map<String, FieldInfo> m = new LinkedHashMap<>();
        for (Copybook.FlatField ff : cb.flatten(record)) {
            String sp = stripSubscripts(ff.path());
            m.putIfAbsent(sp, new FieldInfo(sp, typeLabel(ff.field()), ff.offset(), ff.length(), numeric(ff.field())));
        }
        return new ArrayList<>(m.values());
    }
}
