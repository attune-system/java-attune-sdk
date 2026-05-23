package io.attune;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActionRunnerTest {

    private InputStream originalStdin;

    @BeforeEach
    void saveStdin() {
        originalStdin = System.in;
    }

    @AfterEach
    void restoreStdin() {
        System.setIn(originalStdin);
    }

    private void setStdin(String content) {
        System.setIn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------
    // Map-based readParams
    // ------------------------------------------------------------------

    @Test
    void readParamsReturnsMapFromJson() {
        setStdin("{\"name\":\"alice\",\"count\":3}");
        Map<String, Object> params = ActionRunner.readParams();
        assertEquals("alice", params.get("name"));
        assertEquals(3, params.get("count"));
    }

    @Test
    void readParamsReturnsEmptyMapForEmptyInput() {
        setStdin("");
        Map<String, Object> params = ActionRunner.readParams();
        assertTrue(params.isEmpty());
    }

    @Test
    void readParamsReturnsEmptyMapForInvalidJson() {
        setStdin("not json at all");
        Map<String, Object> params = ActionRunner.readParams();
        assertTrue(params.isEmpty());
    }

    // ------------------------------------------------------------------
    // Typed readParams
    // ------------------------------------------------------------------

    public static class GreetParams {
        public String name;
        public int count;

        public GreetParams() {}
        public GreetParams(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    @Test
    void readTypedParamsDeserializesIntoPojo() {
        setStdin("{\"name\":\"bob\",\"count\":5}");
        GreetParams params = ActionRunner.readParams(GreetParams.class);
        assertEquals("bob", params.name);
        assertEquals(5, params.count);
    }

    @Test
    void readTypedParamsHandlesEmptyInput() {
        setStdin("");
        GreetParams params = ActionRunner.readParams(GreetParams.class);
        assertNotNull(params);
        assertNull(params.name);
        assertEquals(0, params.count);
    }

    @Test
    void readTypedParamsIgnoresExtraFields() {
        setStdin("{\"name\":\"carol\",\"count\":2,\"extra\":\"ignored\"}");
        GreetParams params = ActionRunner.readParams(GreetParams.class);
        assertEquals("carol", params.name);
        assertEquals(2, params.count);
    }

    // ------------------------------------------------------------------
    // emitResult with typed objects
    // ------------------------------------------------------------------

    public static class GreetResult {
        public String greeting;
        public int length;

        public GreetResult() {}
        public GreetResult(String greeting, int length) {
            this.greeting = greeting;
            this.length = length;
        }
    }

    @Test
    void emitResultSerializesPojoToJson() throws Exception {
        // Capture stdout
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalOut = System.out;
        System.setOut(new java.io.PrintStream(out));

        try {
            ActionRunner.emitResult(new GreetResult("Hello!", 6));
        } finally {
            System.setOut(originalOut);
        }

        ObjectMapper mapper = new ObjectMapper();
        Map<?, ?> result = mapper.readValue(out.toString().trim(), Map.class);
        assertEquals("Hello!", result.get("greeting"));
        assertEquals(6, result.get("length"));
    }

    @Test
    void emitResultSerializesMapToJson() throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalOut = System.out;
        System.setOut(new java.io.PrintStream(out));

        try {
            ActionRunner.emitResult(Map.of("key", "value"));
        } finally {
            System.setOut(originalOut);
        }

        ObjectMapper mapper = new ObjectMapper();
        Map<?, ?> result = mapper.readValue(out.toString().trim(), Map.class);
        assertEquals("value", result.get("key"));
    }
}
