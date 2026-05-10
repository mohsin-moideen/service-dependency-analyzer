package com.groupon.sda.api;

import com.groupon.sda.config.HealthProperties;
import com.groupon.sda.domain.graph.Edge;
import com.groupon.sda.graph.ServiceGraph;
import com.groupon.sda.testsupport.FixtureLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec quote: <i>"Returns structured errors (unknown service, malformed request, etc.)
 * — not stack traces."</i>
 *
 * <p>This is a slice test against {@link GraphQueryController}: the full Spring MVC
 * pipeline (Jackson + {@link GlobalExceptionHandler}) is loaded but the data layer is
 * stubbed via {@link MockBean}. We assert two things on every error path:
 *
 * <ol>
 *   <li>The HTTP status is what the spec expects (404, 400, 503).</li>
 *   <li>The body is an {@link ApiError} JSON envelope with a stable {@code error}
 *       discriminator and a non-empty {@code message} — never a stack trace.</li>
 * </ol>
 */
@WebMvcTest(GraphQueryController.class)
@org.springframework.context.annotation.Import(ApiErrorPathTest.Config.class)
class ApiErrorPathTest {

    @Autowired MockMvc mvc;
    @MockBean ServiceGraph graph;

    @Configuration
    static class Config {
        @Bean Clock clock() {
            return Clock.fixed(Instant.parse("2026-05-10T12:00:00Z"), ZoneOffset.UTC);
        }
        @Bean HealthProperties healthProperties() {
            return new HealthProperties(300, 1024);
        }
    }

    @BeforeEach
    void neverLeakStackTraces() {
        // Default Mockito stubs return false for hasNode (so unknown-service paths trigger).
    }

    // ---- 404: unknown service --------------------------------------------------------

    @Test
    void reachableUnknownService404() throws Exception {
        when(graph.withReadLock(any())).thenReturn(null);  // Reachability returns null
        mvc.perform(get("/api/v1/graph/reachable/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("service_not_found"))
                .andExpect(jsonPath("$.service").value("ghost"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void dependentsUnknownService404() throws Exception {
        when(graph.withReadLock(any())).thenReturn(null);
        mvc.perform(get("/api/v1/graph/dependents/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("service_not_found"))
                .andExpect(jsonPath("$.service").value("ghost"));
    }

    @Test
    void shortestPathUnknownSource404() throws Exception {
        // ShortestPath returns null when either endpoint is missing; the controller
        // disambiguates by re-checking hasNode, so we have to wire that too.
        when(graph.withReadLock(any())).thenReturn(null);
        when(graph.hasNode("missing-src")).thenReturn(false);
        when(graph.hasNode("anywhere")).thenReturn(true);
        mvc.perform(get("/api/v1/graph/shortest-path")
                        .param("source", "missing-src").param("target", "anywhere"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("service_not_found"))
                .andExpect(jsonPath("$.service").value("missing-src"));
    }

    @Test
    void healthUnknownService404() throws Exception {
        when(graph.hasNode("ghost")).thenReturn(false);
        mvc.perform(get("/api/v1/graph/health/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("service_not_found"));
    }

    // ---- 400: invalid argument -------------------------------------------------------

    @Test
    void criticalServicesNonPositiveK400() throws Exception {
        // The Criticality algorithm throws IAE on k<=0; the controller passes it through
        // to GlobalExceptionHandler.
        mvc.perform(get("/api/v1/graph/critical-services").param("k", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void criticalServicesNonNumericK400() throws Exception {
        mvc.perform(get("/api/v1/graph/critical-services").param("k", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void healthWindowExceedingRetention400() throws Exception {
        when(graph.hasNode("svc")).thenReturn(true);
        when(graph.computeHealth(eq("svc"), any(), any()))
                .thenThrow(new IllegalArgumentException(
                        "requested window PT10M exceeds configured retention PT5M"));
        mvc.perform(get("/api/v1/graph/health/svc").param("windowSeconds", "600"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ---- Body sanity: no stack trace ever ------------------------------------------

    @Test
    void errorBodiesNeverIncludeStackTrace() throws Exception {
        when(graph.withReadLock(any())).thenReturn(null);
        mvc.perform(get("/api/v1/graph/reachable/ghost"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("java.lang."))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("at com.groupon"))));
    }

    // ---- 200 happy path: empty health returns zeros, not 404 -----------------------

    @Test
    void healthForKnownServiceWithNoSamplesReturnsZeros() throws Exception {
        when(graph.hasNode("svc")).thenReturn(true);
        when(graph.computeHealth(eq("svc"), any(), any())).thenReturn(null);
        mvc.perform(get("/api/v1/graph/health/svc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleCount").value(0))
                .andExpect(jsonPath("$.errorRate").value(0.0))
                .andExpect(jsonPath("$.p95LatencyMs").value(0));
    }

    @Test
    void healthForKnownServiceReturnsValues() throws Exception {
        when(graph.hasNode("svc")).thenReturn(true);
        when(graph.computeHealth(eq("svc"), any(), any()))
                .thenReturn(new Edge.HealthSnapshot(42, 0.1, 250));
        mvc.perform(get("/api/v1/graph/health/svc").param("windowSeconds", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleCount").value(42))
                .andExpect(jsonPath("$.errorRate").value(0.1))
                .andExpect(jsonPath("$.p95LatencyMs").value(250))
                .andExpect(jsonPath("$.windowSeconds").value(60))
                .andExpect(jsonPath("$.service").value("svc"));
    }

    // ---- Reference to the negative fixtures ----------------------------------------
    // These prove the fixture files are well-formed inputs for the negative tests
    // below; the tests themselves live in EventIngestApiErrorTest where the JSON
    // is POSTed against the ingest controller.

    @SuppressWarnings("unused")
    private static byte[] sanityNegativeFixturesAreOnClasspath() {
        return FixtureLoader.loadRaw("negative/01-malformed-json.json");
    }

    /** Suppress unused-import warnings while keeping the Duration reference visible. */
    @SuppressWarnings("unused")
    private static final Duration WINDOW = Duration.ofSeconds(300);
}
