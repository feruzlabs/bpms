package com.bpms.persistence.jpa;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.bpms.persistence.jpa.JpaPersistenceAdapter.decode;
import static com.bpms.persistence.jpa.JpaPersistenceAdapter.typeOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariableTypingTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    @Test
    void typeOf_mapsCanonicalKinds() {
        assertEquals("long", typeOf(150));
        assertEquals("long", typeOf(150L));
        assertEquals("long", typeOf((short) 1));
        assertEquals("long", typeOf(BigInteger.TEN));
        assertEquals("double", typeOf(12.5));
        assertEquals("double", typeOf(12.5f));
        assertEquals("double", typeOf(new BigDecimal("150.50")));
        assertEquals("boolean", typeOf(true));
        assertEquals("date", typeOf(LocalDate.of(2024, 1, 2)));
        assertEquals("date", typeOf(LocalDateTime.of(2024, 1, 2, 3, 4)));
        assertEquals("string", typeOf("hello"));
        assertEquals("string", typeOf(null));
        assertEquals("json", typeOf(Map.of("a", 1)));
        assertEquals("json", typeOf(List.of(1, 2)));
    }

    @Test
    void decimalIsNotStoredAsLong_regression() {
        assertEquals("double", typeOf(new BigDecimal("150.50")));
        assertThrows(NumberFormatException.class, () -> Long.valueOf("150.50"));
    }

    @Test
    void decode_roundTripsAllCanonicalTypes() throws Exception {
        assertEquals(150L, decode("long", "150", null, mapper));
        assertEquals(new BigDecimal("150.50"), decode("double", "150.50", null, mapper));
        assertEquals(new BigDecimal("12.5"), decode("double", "12.5", null, mapper));
        assertEquals(Boolean.TRUE, decode("boolean", "true", null, mapper));
        assertEquals("2024-01-02", decode("date", "2024-01-02", null, mapper));
        assertEquals("hello", decode("string", "hello", null, mapper));
        assertNull(decode("string", null, null, mapper));

        String json = mapper.writeValueAsString(Map.of("k", "v"));
        Object decodedJson = decode("json", null, json, mapper);
        assertInstanceOf(Map.class, decodedJson);
        assertEquals("v", ((Map<?, ?>) decodedJson).get("k"));

        String listJson = mapper.writeValueAsString(List.of(1, 2));
        assertInstanceOf(List.class, decode("json", null, listJson, mapper));
    }

    @Test
    void decode_doubleDoesNotThrowNumberFormatException() throws Exception {
        Object value = decode("double", "150.50", null, mapper);
        assertEquals(0, new BigDecimal("150.50").compareTo((BigDecimal) value));
    }

    @Test
    void jacksonDeserializesFloatsAsBigDecimal() throws Exception {
        Map<?, ?> parsed = mapper.readValue("{\"amount\":150.50,\"rate\":12.5}", Map.class);
        assertInstanceOf(BigDecimal.class, parsed.get("amount"));
        assertInstanceOf(BigDecimal.class, parsed.get("rate"));
        assertFalse(parsed.get("amount") instanceof Double);
        assertEquals(0, new BigDecimal("150.50").compareTo((BigDecimal) parsed.get("amount")));
    }

    @Test
    void simulatePutGetRoundTripInMemory() throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("whole", 150);
        input.put("money", new BigDecimal("150.50"));
        input.put("flag", true);
        input.put("day", LocalDate.of(2024, 6, 1));
        input.put("label", "ok");
        input.put("payload", Map.of("n", 1));
        input.put("empty", null);

        Map<String, Stored> store = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : input.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                store.put(e.getKey(), new Stored("string", null, null));
                continue;
            }
            String type = typeOf(v);
            store.put(e.getKey(), new Stored(
                    type,
                    "json".equals(type) ? null : String.valueOf(v),
                    "json".equals(type) ? mapper.writeValueAsString(v) : null));
        }

        Map<String, Object> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, Stored> e : store.entrySet()) {
            loaded.put(e.getKey(), decode(e.getValue().type(), e.getValue().text(), e.getValue().json(), mapper));
        }

        assertEquals(150L, loaded.get("whole"));
        assertEquals(new BigDecimal("150.50"), loaded.get("money"));
        assertEquals(Boolean.TRUE, loaded.get("flag"));
        assertEquals("2024-06-01", loaded.get("day"));
        assertEquals("ok", loaded.get("label"));
        assertTrue(((Map<?, ?>) loaded.get("payload")).containsKey("n"));
        assertNull(loaded.get("empty"));
    }

    private record Stored(String type, String text, String json) {
    }
}
