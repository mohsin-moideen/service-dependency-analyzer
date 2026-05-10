package com.groupon.sda.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Graph Queries", description = "Analytical queries over the service dependency graph")
@RestController
@RequestMapping("/api/v1/graph")
public class GraphQueryController {

    @Operation(summary = "Reachable downstream services (blast radius)")
    @GetMapping("/reachable/{service}")
    public Object reachable(@PathVariable String service) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Operation(summary = "Services that transitively depend on the given service")
    @GetMapping("/dependents/{service}")
    public Object dependents(@PathVariable String service) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Operation(summary = "Lowest-latency path between two services")
    @GetMapping("/shortest-path")
    public Object shortestPath(@RequestParam String source, @RequestParam String target) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Operation(summary = "Top-k critical services by chosen criticality metric")
    @GetMapping("/critical-services")
    public Object criticalServices(@RequestParam(defaultValue = "10") int k) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Operation(summary = "All cycles currently present in the graph")
    @GetMapping("/cycles")
    public Object cycles() {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Operation(summary = "Error rate and p95 latency over a trailing window")
    @GetMapping("/health/{service}")
    public Object health(@PathVariable String service,
                         @RequestParam(defaultValue = "300") int windowSeconds) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
