#!/usr/bin/env node
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
  type Tool,
} from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";

const BASE_URL = (process.env.SDA_BASE_URL ?? "http://localhost:8080").replace(/\/$/, "");
const REQUEST_TIMEOUT_MS = Number(process.env.SDA_TIMEOUT_MS ?? 10_000);

type Json = unknown;

class SdaError extends Error {
  constructor(public status: number, public body: Json, message: string) {
    super(message);
  }
}

async function get(path: string, query?: Record<string, string | number | undefined>): Promise<Json> {
  const url = new URL(BASE_URL + path);
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null) url.searchParams.set(k, String(v));
    }
  }
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), REQUEST_TIMEOUT_MS);
  try {
    const res = await fetch(url, { signal: ctl.signal, headers: { accept: "application/json" } });
    const text = await res.text();
    let body: Json;
    try { body = text ? JSON.parse(text) : null; } catch { body = text; }
    if (!res.ok) {
      const msg =
        body && typeof body === "object" && body !== null && "message" in body
          ? String((body as Record<string, unknown>).message)
          : `HTTP ${res.status}`;
      throw new SdaError(res.status, body, msg);
    }
    return body;
  } finally {
    clearTimeout(timer);
  }
}

const ServiceArg = z.object({ service: z.string().min(1) });
const ShortestPathArgs = z.object({ source: z.string().min(1), target: z.string().min(1) });
const CriticalArgs = z.object({ k: z.number().int().positive().max(1000).default(10) });
const HealthArgs = z.object({
  service: z.string().min(1),
  windowSeconds: z.number().int().positive().optional(),
});

const TOOLS: Tool[] = [
  {
    name: "reachable",
    description:
      "Blast radius — every service reachable downstream from the given service, with the path used to reach each. Returns { service, reachable: [{ id, path: [...] }] }.",
    inputSchema: {
      type: "object",
      properties: { service: { type: "string", description: "Service id to start from." } },
      required: ["service"],
      additionalProperties: false,
    },
  },
  {
    name: "dependents",
    description:
      "Reverse reachability — every service that transitively depends on the given service. Returns { service, reachable: [{ id, path: [...] }] }.",
    inputSchema: {
      type: "object",
      properties: { service: { type: "string", description: "Service id whose upstream dependents to enumerate." } },
      required: ["service"],
      additionalProperties: false,
    },
  },
  {
    name: "shortest_path",
    description:
      "Lowest-latency path from source to target, using current rolling-average edge latencies as weights. Returns { source, target, found, path, totalLatencyMs }.",
    inputSchema: {
      type: "object",
      properties: {
        source: { type: "string", description: "Origin service id." },
        target: { type: "string", description: "Destination service id." },
      },
      required: ["source", "target"],
      additionalProperties: false,
    },
  },
  {
    name: "critical_services",
    description:
      "Top-k critical services by criticality score (in-degree × out-degree). Ties broken lex by id. Returns { k, services: [{ id, score, inDegree, outDegree }] }.",
    inputSchema: {
      type: "object",
      properties: {
        k: { type: "integer", minimum: 1, maximum: 1000, default: 10, description: "How many to return." },
      },
      additionalProperties: false,
    },
  },
  {
    name: "cycles",
    description:
      "All elementary dependency cycles currently present in the graph. Returns { cycles: [[id, id, ...], ...] } where each inner list closes back on its first element.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
  },
  {
    name: "health",
    description:
      "Error rate and p95 latency for the given service over a trailing window (defaults to the server's configured window). Returns { service, windowSeconds, sampleCount, errorRate, p95LatencyMs }.",
    inputSchema: {
      type: "object",
      properties: {
        service: { type: "string", description: "Service id to score." },
        windowSeconds: {
          type: "integer",
          minimum: 1,
          description: "Trailing window in seconds. Must be ≤ server retention.",
        },
      },
      required: ["service"],
      additionalProperties: false,
    },
  },
];

async function dispatch(name: string, raw: unknown): Promise<Json> {
  switch (name) {
    case "reachable": {
      const { service } = ServiceArg.parse(raw);
      return get(`/api/v1/graph/reachable/${encodeURIComponent(service)}`);
    }
    case "dependents": {
      const { service } = ServiceArg.parse(raw);
      return get(`/api/v1/graph/dependents/${encodeURIComponent(service)}`);
    }
    case "shortest_path": {
      const { source, target } = ShortestPathArgs.parse(raw);
      return get(`/api/v1/graph/shortest-path`, { source, target });
    }
    case "critical_services": {
      const { k } = CriticalArgs.parse(raw ?? {});
      return get(`/api/v1/graph/critical-services`, { k });
    }
    case "cycles":
      return get(`/api/v1/graph/cycles`);
    case "health": {
      const { service, windowSeconds } = HealthArgs.parse(raw);
      return get(`/api/v1/graph/health/${encodeURIComponent(service)}`, { windowSeconds });
    }
    default:
      throw new Error(`unknown tool: ${name}`);
  }
}

const server = new Server(
  { name: "sda-mcp", version: "0.1.0" },
  { capabilities: { tools: {} } },
);

server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools: TOOLS }));

server.setRequestHandler(CallToolRequestSchema, async (req) => {
  const { name, arguments: args } = req.params;
  try {
    const result = await dispatch(name, args ?? {});
    return {
      content: [{ type: "text", text: JSON.stringify(result, null, 2) }],
    };
  } catch (err) {
    if (err instanceof SdaError) {
      return {
        isError: true,
        content: [
          {
            type: "text",
            text: JSON.stringify(
              { httpStatus: err.status, baseUrl: BASE_URL, response: err.body },
              null,
              2,
            ),
          },
        ],
      };
    }
    if (err instanceof z.ZodError) {
      return {
        isError: true,
        content: [{ type: "text", text: `invalid arguments: ${err.message}` }],
      };
    }
    const msg = err instanceof Error ? err.message : String(err);
    const hint =
      msg.includes("ECONNREFUSED") || msg.includes("fetch failed")
        ? ` (is the SDA service running at ${BASE_URL}?)`
        : "";
    return { isError: true, content: [{ type: "text", text: msg + hint }] };
  }
});

await server.connect(new StdioServerTransport());
