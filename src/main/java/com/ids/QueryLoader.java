package com.ids;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public class QueryLoader {
    private static Map<String, String> queries;

    static {
        try (InputStream is = QueryLoader.class.getResourceAsStream("/queries.json")) {
            if (is == null)
                throw new IOException("queries.json not found in resources");
            ObjectMapper mapper = new ObjectMapper();
            queries = mapper.readValue(is, new TypeReference<Map<String, String>>() {
            });
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to load queries.json", e);
        }
    }

    public static String getQuery(String key) {
        if (queries.containsKey(key)) {
            return queries.get(key);
        }
        throw new IllegalArgumentException("Query key not found: " + key);
    }
}
