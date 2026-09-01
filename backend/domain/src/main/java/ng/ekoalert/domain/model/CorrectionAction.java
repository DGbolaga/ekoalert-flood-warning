package ng.ekoalert.domain.model;

import java.util.Locale;

/** What a resident did to an edge from the map, in one tap. */
public enum CorrectionAction {

    CONFIRM,
    REJECT,
    PROPOSE;

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CorrectionAction fromLabel(String label) {
        return valueOf(label.toUpperCase(Locale.ROOT));
    }
}
