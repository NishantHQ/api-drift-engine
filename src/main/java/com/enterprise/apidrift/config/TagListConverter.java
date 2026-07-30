package com.enterprise.apidrift.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JPA AttributeConverter for transparent List&lt;String&gt; ↔ comma-separated TEXT storage.
 * Empty list → null (avoids storing empty strings).
 * Null → empty list (defensive coding for legacy rows).
 */
@Converter
public class TagListConverter implements AttributeConverter<List<String>, String> {

    private static final String DELIMITER = ",";

    @Override
    public String convertToDatabaseColumn(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        return tags.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(DELIMITER));
    }

    @Override
    public List<String> convertToEntityAttribute(String dbValue) {
        if (dbValue == null || dbValue.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(dbValue.split(DELIMITER))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
