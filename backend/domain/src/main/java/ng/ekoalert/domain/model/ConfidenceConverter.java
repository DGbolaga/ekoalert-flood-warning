package ng.ekoalert.domain.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import ng.ekoalert.engine.Confidence;

import java.util.Locale;

/** See {@link SeverityConverter} for why this is a converter and not an @Enumerated. */
@Converter(autoApply = true)
public class ConfidenceConverter implements AttributeConverter<Confidence, String> {

    @Override
    public String convertToDatabaseColumn(Confidence attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public Confidence convertToEntityAttribute(String dbData) {
        return dbData == null ? null : Confidence.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
