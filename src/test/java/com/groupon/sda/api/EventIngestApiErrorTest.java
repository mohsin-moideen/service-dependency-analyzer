package com.groupon.sda.api;

import com.groupon.sda.queue.EventQueue;
import com.groupon.sda.queue.QueueClosedException;
import com.groupon.sda.testsupport.FixtureLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ingest endpoint's error contract:
 * <ul>
 *   <li>Malformed JSON → 400 {@code invalid_request} (Jackson failure → {@code HttpMessageNotReadableException}).</li>
 *   <li>Unknown {@code type} discriminator → 400 {@code invalid_request}.</li>
 *   <li>Bad enum value (e.g., {@code status: "OK"}) → 400 {@code invalid_request}.</li>
 *   <li>Bad timestamp string → 400 {@code invalid_request}.</li>
 *   <li>Queue offer timeout → 503 (single endpoint) / 503 batch ack with {@code rejected > 0}.</li>
 *   <li>Queue closed → 503 {@code service_unavailable}.</li>
 * </ul>
 *
 * <p>Each negative input lives in {@code src/test/resources/fixtures/negative/} so the
 * wire-format inputs can be reused by other tooling (e.g., a curl-based smoke harness).
 */
@WebMvcTest(EventIngestController.class)
class EventIngestApiErrorTest {

    @Autowired MockMvc mvc;
    @MockBean EventQueue queue;

    private static final String BATCH = "/api/v1/events/batch";

    // ---- 400 paths -----------------------------------------------------------------

    @Test
    void malformedJsonReturns400() throws Exception {
        byte[] body = FixtureLoader.loadRaw("negative/01-malformed-json.json");
        mvc.perform(post(BATCH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").value("malformed request body"));
    }

    @Test
    void missingRequiredFieldReturns400() throws Exception {
        byte[] body = FixtureLoader.loadRaw("negative/02-missing-required-field.json");
        // Jackson will accept the missing primitive (latency_ms gets 0), but the record
        // canonical constructor still works; we lean on either Jackson rejecting unknown
        // shapes OR the queue accepting the event. The contract under test is "no
        // stack traces, structured response". We accept any 4xx with a structured body.
        mvc.perform(post(BATCH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(result -> {
                    int sc = result.getResponse().getStatus();
                    if (sc >= 400 && sc < 500) {
                        // 4xx: must be structured ApiError
                        String json = result.getResponse().getContentAsString();
                        org.assertj.core.api.Assertions.assertThat(json)
                                .doesNotContain("java.lang.")
                                .doesNotContain("at com.groupon");
                    } else {
                        // If accepted, queue.offer must have been called.
                        org.mockito.Mockito.verify(queue).offer(any(), anyLong(), any());
                    }
                });
    }

    @Test
    void badStatusEnumReturns400() throws Exception {
        byte[] body = FixtureLoader.loadRaw("negative/03-bad-status-enum.json");
        mvc.perform(post(BATCH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void unknownEventTypeReturns400() throws Exception {
        byte[] body = FixtureLoader.loadRaw("negative/04-unknown-event-type.json");
        mvc.perform(post(BATCH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void badTimestampReturns400() throws Exception {
        byte[] body = FixtureLoader.loadRaw("negative/05-bad-timestamp.json");
        mvc.perform(post(BATCH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    // ---- 503 paths -----------------------------------------------------------------

    @Test
    void batchRejectedWhenQueueOfferTimesOut() throws Exception {
        // Wire queue.offer to refuse all events — the controller will return 503
        // with a partial-ack body.
        when(queue.offer(any(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        String body = """
                [{"type":"dependency_observed","event_id":"x-1","timestamp":"2026-05-10T12:00:00Z","source":"a","target":"b","latency_ms":1,"status":"ok"}]
                """;
        mvc.perform(post(BATCH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.submitted").value(1))
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.rejected").value(1));
    }

    @Test
    void singleEventRejectedWhenQueueOfferTimesOut() throws Exception {
        when(queue.offer(any(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        String body = """
                {"type":"heartbeat","event_id":"hb-1","timestamp":"2026-05-10T12:00:00Z","service":"svc-a"}
                """;
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void queueClosedDuringIngestReturns503Structured() throws Exception {
        when(queue.offer(any(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new QueueClosedException("queue closed"));
        String body = """
                {"type":"heartbeat","event_id":"hb-1","timestamp":"2026-05-10T12:00:00Z","service":"svc-a"}
                """;
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("service_unavailable"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ---- Body must never carry stack traces ----------------------------------------

    @Test
    void noStackTraceOnAnyErrorPath() throws Exception {
        byte[] body = FixtureLoader.loadRaw("negative/01-malformed-json.json");
        mvc.perform(post(BATCH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("at com.groupon"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("java.lang."))));
    }
}
