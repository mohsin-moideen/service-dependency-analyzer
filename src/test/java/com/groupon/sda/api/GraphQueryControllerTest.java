package com.groupon.sda.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.groupon.sda.config.HealthProperties;
import com.groupon.sda.domain.event.DependencyObservedEvent.Status;
import com.groupon.sda.graph.ServiceGraph;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the controller wires algorithms to the JSON response shape the fixture
 * runner expects. Specifically asserts that {@code shortest-path} returns a JSON
 * object with {@code path} and {@code totalLatencyMs} fields — not a string, not an
 * error envelope — so the test harness's {@code jq -c .path} works.
 */
class GraphQueryControllerTest {

    private static final Instant T0 = Instant.parse("2026-05-10T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private MockMvc mvc(ServiceGraph graph) {
        HealthProperties health = new HealthProperties(86400, 1024);
        GraphQueryController controller = new GraphQueryController(graph, CLOCK, health);
        GlobalExceptionHandler advice = new GlobalExceptionHandler();
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice)
                .build();
    }

    private ServiceGraph diamond() {
        ServiceGraph g = new ServiceGraph(1024, Duration.ofDays(1), CLOCK);
        // topology/02-diamond.json
        g.applyDependencyObserved("a", "b", T0, 10, Status.ok);
        g.applyDependencyObserved("a", "c", T0, 50, Status.ok);
        g.applyDependencyObserved("b", "d", T0, 10, Status.ok);
        g.applyDependencyObserved("c", "d", T0, 5, Status.ok);
        return g;
    }

    @Test
    void diamondShortestPathReturnsJsonObjectWithPathAndWeight() throws Exception {
        MockMvc mvc = mvc(diamond());

        MvcResult res = mvc.perform(get("/api/v1/graph/shortest-path")
                        .param("source", "a")
                        .param("target", "d")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.path").isArray())
                .andExpect(jsonPath("$.path[0]").value("a"))
                .andExpect(jsonPath("$.path[1]").value("b"))
                .andExpect(jsonPath("$.path[2]").value("d"))
                .andExpect(jsonPath("$.totalLatencyMs").value(20.0))
                .andReturn();

        // Locks in the exact body shape the fixture harness's jq queries against.
        String body = res.getResponse().getContentAsString();
        assertThat(body).doesNotContain("\n");   // single-line JSON, no embedded newlines
        ObjectMapper m = new ObjectMapper();
        assertThat(m.readTree(body).isObject()).isTrue();
        assertThat(m.readTree(body).get("path").isArray()).isTrue();
        assertThat(m.readTree(body).get("totalLatencyMs").asDouble()).isEqualTo(20.0);
    }

    @Test
    void shortestPathUnknownTargetReturns404WithApiErrorJson() throws Exception {
        MockMvc mvc = mvc(diamond());

        mvc.perform(get("/api/v1/graph/shortest-path")
                        .param("source", "a")
                        .param("target", "ghost")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("service_not_found"))
                .andExpect(jsonPath("$.service").value("ghost"));
    }

    @Test
    void reachableDiamondReturnsExpectedNodes() throws Exception {
        MockMvc mvc = mvc(diamond());

        mvc.perform(get("/api/v1/graph/reachable/a")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("a"))
                .andExpect(jsonPath("$.reachable[*].id",
                        org.hamcrest.Matchers.containsInAnyOrder("b", "c", "d")));
    }
}
