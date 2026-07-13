package org.praxisplatform.rules.jsonlogic.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;

public final class JsonLogicConformanceFixtureLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonLogicConformanceFixtureLoader() {
    }

    public static JsonNode loadSharedCorpus() throws IOException {
        try (InputStream input = JsonLogicConformanceFixtureLoader.class.getResourceAsStream(
                "/org/praxisplatform/rules/jsonlogic/conformance-fixtures.json")) {
            if (input == null) throw new IOException("Packaged conformance corpus is missing.");
            return MAPPER.readTree(input);
        }
    }
}
