package ng.ekoalert.domain.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import ng.ekoalert.engine.Severity;

import java.util.Locale;

/**
 * The database enum labels are lowercase; the Java constants are not. The
 * datasource sets stringtype=unspecified so PostgreSQL coerces the bound text
 * into the enum column.
 */
@Converter(autoApply = true)
public class SeverityConverter implements AttributeConverter<Severity, String> {

    @Override
    public String convertToDatabaseColumn(Severity attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public Severity convertToEntityAttribute(String dbData) {
        return dbData == null ? null : Severity.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
