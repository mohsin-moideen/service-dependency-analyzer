package com.groupon.sda.testsupport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.groupon.sda.domain.event.Event;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Loads JSON fixture files from {@code src/test/resources/fixtures/} into typed
 * {@link Event} lists, using the same Jackson configuration the production
 * controllers see for {@code @RequestBody} deserialization.
 *
 * <p>Why duplicate Jackson setup instead of injecting Spring's pre-built mapper? Most
 * fixture-driven tests don't bootstrap the full application context — they're plain
 * JUnit tests against {@code ServiceGraph} or {@code EventConsumer} directly. A
 * standalone helper keeps those tests fast.
 *
 * <p>Resource paths are relative to {@code fixtures/}: pass {@code "topology/02-diamond.json"}
 * not {@code "/fixtures/topology/02-diamond.json"}.
 */
public final class FixtureLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // Match Spring Boot's default lenient deserialization, except for the
            // strict-enum option that catches "OK" vs "ok" — fixtures should fail
            // loudly when they drift from the wire format.
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private FixtureLoader() {
    }

    /**
     * Load a fixture JSON file as a list of {@link Event}.
     *
     * @param relativePath path under {@code fixtures/} (e.g., {@code "topology/02-diamond.json"})
     * @throws IllegalArgumentException if the resource doesn't exist
     * @throws UncheckedIOException     if the file is malformed or doesn't match the Event schema
     */
    public static List<Event> load(String relativePath) {
        String resourcePath = "/fixtures/" + relativePath;
        try (InputStream in = FixtureLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("fixture not found on classpath: " + resourcePath);
            }
            return MAPPER.readValue(in, new TypeReference<List<Event>>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load fixture " + resourcePath, e);
        }
    }

    /** Raw JSON bytes for a fixture — useful for the negative-fixture tests that want to
     * post the file content as-is to MockMvc and exercise the Jackson error path. */
    public static byte[] loadRaw(String relativePath) {
        String resourcePath = "/fixtures/" + relativePath;
        try (InputStream in = FixtureLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("fixture not found on classpath: " + resourcePath);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read fixture " + resourcePath, e);
        }
    }
}
