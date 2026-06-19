package io.attune;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileSensorTokenProviderTest {

    @Test
    void readsAndReReadsRotatedStateFromFile() throws Exception {
        Path dir = Path.of("target", "token-provider-tests");
        Files.createDirectories(dir);
        Path stateFile = dir.resolve("token-state-" + System.nanoTime() + ".json");

        Files.writeString(
                stateFile,
                "{\"token\":\"token-1\",\"expires_at\":\"2099-01-01T00:00:00Z\"}",
                StandardCharsets.UTF_8
        );

        FileSensorTokenProvider provider = new FileSensorTokenProvider(stateFile);
        assertEquals("token-1", provider.currentTokenState().token());

        Files.writeString(
                stateFile,
                "{\"token\":\"token-2\",\"expires_at\":\"2099-01-01T00:30:00Z\"}",
                StandardCharsets.UTF_8
        );
        assertEquals("token-2", provider.currentTokenState().token());
        assertEquals("2099-01-01T00:30:00Z", provider.currentTokenState().expiresAt());
    }

    @Test
    void throwsClearErrorWhenStateSourceUnavailableWithoutFallback() {
        Path missing = Path.of("target", "token-provider-tests", "missing-" + System.nanoTime() + ".json");
        FileSensorTokenProvider provider = new FileSensorTokenProvider(missing);
        IllegalStateException error = assertThrows(IllegalStateException.class, provider::currentTokenState);
        assertTrue(error.getMessage().contains("Unable to read sensor token state"));
        assertTrue(error.getMessage().contains(missing.toString()));
    }
}
