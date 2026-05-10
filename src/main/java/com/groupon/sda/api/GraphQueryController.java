package com.groupon.sda.api;

import com.groupon.sda.config.HealthProperties;
import com.groupon.sda.domain.graph.Edge;
import com.groupon.sda.graph.ServiceGraph;
import com.groupon.sda.graph.algorithms.Criticality;
import com.groupon.sda.graph.algorithms.Cycles;
import com.groupon.sda.graph.algorithms.Reachability;
import com.groupon.sda.graph.algorithms.ShortestPath;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;

/**
 * Six analytical endpoints over the in-memory {@link ServiceGraph}. Each delegates to a
 * stateless algorithm class in {@code graph.algorithms.*} (or to {@code computeHealth}
 * directly) and returns an algorithm-result record that Jackson serializes as JSON.
 *
 * <p>Error contract:
 * <ul>
 *   <li>Unknown service → 404 {@code service_not_found} (via {@link UnknownServiceException}).</li>
 *   <li>Bad arg (k≤0, window≤0, window>retention, missing id) → 400 {@code invalid_request}
 *       (via {@code IllegalArgumentException} → {@link GlobalExceptionHandler}).</li>
 * </ul>
 */
@Tag(name = "Graph Queries", description = "Analytical queries over the service dependency graph")
@RestController
@RequestMapping("/api/v1/graph")
public class GraphQueryController {

    private final ServiceGraph graph;
    private final Clock clock;
    private final int defaultWindowSeconds;

    public GraphQueryController(ServiceGraph graph, Clock clock, HealthProperties health) {
        this.graph = graph;
        this.clock = clock;
        this.defaultWindowSeconds = health.windowSeconds();
    }

    @Operation(summary = "Reachable downstream services (blast radius)")
    @GetMapping("/reachable/{service}")
    public Reachability.Result reachable(@PathVariable String service) {
        Reachability.Result r = Reachability.reachable(graph, service);
        if (r == null) throw new UnknownServiceException(service);
        return r;
    }

    @Operation(summary = "Services that transitively depend on the given service")
    @GetMapping("/dependents/{service}")
    public Reachability.Result dependents(@PathVariable String service) {
        Reachability.Result r = Reachability.dependents(graph, service);
        if (r == null) throw new UnknownServiceException(service);
        return r;
    }

    @Operation(summary = "Lowest-latency path between two services")
    @GetMapping("/shortest-path")
    public ShortestPath.Result shortestPath(@RequestParam String source,
                                            @RequestParam String target) {
        ShortestPath.Result r = ShortestPath.shortestPath(graph, source, target);
        if (r == null) {
            // Disambiguate which one is missing for a clearer 404.
            if (!graph.hasNode(source)) throw new UnknownServiceException(source);
            throw new UnknownServiceException(target);
        }
        return r;
    }

    @Operation(summary = "Top-k critical services by chosen criticality metric")
    @GetMapping("/critical-services")
    public Criticality.Result criticalServices(@RequestParam(defaultValue = "10") int k) {
        return Criticality.criticalServices(graph, k);   // throws IAE for k<=0
    }

    @Operation(summary = "All cycles currently present in the graph")
    @GetMapping("/cycles")
    public Cycles.Result cycles() {
        return Cycles.cycles(graph);
    }

    @Operation(summary = "Error rate and p95 latency over a trailing window")
    @GetMapping("/health/{service}")
    public HealthResponse health(@PathVariable String service,
                                 @RequestParam(required = false) Integer windowSeconds) {
        if (!graph.hasNode(service)) throw new UnknownServiceException(service);
        int window = windowSeconds == null ? defaultWindowSeconds : windowSeconds;
        Edge.HealthSnapshot snap = graph.computeHealth(service, clock.instant(),
                Duration.ofSeconds(window));
        if (snap == null) {
            // Service exists but has no in-window samples — return zeros.
            return new HealthResponse(service, window, 0, 0.0, 0);
        }
        return new HealthResponse(service, window,
                snap.sampleCount(), snap.errorRate(), snap.p95LatencyMs());
    }

    public record HealthResponse(String service, int windowSeconds,
                                 int sampleCount, double errorRate, int p95LatencyMs) {
    }
}
