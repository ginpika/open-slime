package cc.ginpika.minis.config;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import okhttp3.*;
import org.junit.jupiter.api.Test;

class DeepSeekBalanceServiceTest {
    private static final String BASE = "https://api.deepseek.com";
    private static final String VALID = """
            {"is_available":true,"balance_infos":[
              {"currency":"CNY","total_balance":"110.0012","granted_balance":"10.0012","topped_up_balance":"100.00"},
              {"currency":"USD","total_balance":"0.00","granted_balance":"0.00","topped_up_balance":"0.00"}]}
            """;
    private final ObjectMapper json = new ObjectMapper();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final TestClock clock = new TestClock();
    private int status = 200;
    private String body = VALID;
    private boolean networkFailure;
    private final DeepSeekBalanceService service = new DeepSeekBalanceService(json,
            new OkHttpClient.Builder().addInterceptor(chain -> {
                requests.add(chain.request());
                if (networkFailure) throw new IOException("fixture-secret private upstream failure");
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                        .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
            }).build(), clock);

    @Test void onlyExactOfficialHttpsRootAndV1AreAccepted() {
        for (String base : List.of(BASE, BASE + "/", BASE + "/v1/", "https://API.DEEPSEEK.COM:443/v1"))
            assertTrue(DeepSeekBalanceService.official(base), base);
        for (String base : List.of("", "not a url", "mailto:api.deepseek.com", "http://api.deepseek.com", BASE + ":8443",
                BASE + ".evil.test", BASE + "/proxy", BASE + "/anthropic", BASE + "?api_key=private", BASE + "#x", "https://user@api.deepseek.com")) {
            assertFalse(DeepSeekBalanceService.official(base), base);
            assertEquals("unsupported", service.balance(base, "fixture-secret", false).status());
        }
        assertFalse(DeepSeekBalanceService.official(null));
        assertEquals("unconfigured", service.balance(BASE, " ", false).status());
        assertEquals("error", service.balance(BASE, "bad\nkey", false).status());
        assertTrue(requests.isEmpty());
    }

    @Test void normalizesV1UsesBearerAndPreservesAllCurrenciesAndPrecision() throws Exception {
        var result = service.balance(BASE + "/v1/", "fixture-secret", false);
        assertEquals("ready", result.status()); assertTrue(result.available());
        assertEquals(2, result.balances().size());
        assertEquals("110.0012", result.balances().getFirst().totalBalance());
        assertEquals("USD", result.balances().getLast().currency());
        assertEquals(clock.millis(), result.checkedAt());
        assertEquals("https://api.deepseek.com/user/balance", requests.getFirst().url().toString());
        assertEquals("GET", requests.getFirst().method());
        assertEquals("Bearer fixture-secret", requests.getFirst().header("Authorization"));
        assertFalse(json.writeValueAsString(result).contains("fixture-secret"));
    }

    @Test void sharesAccountCacheButRotatedKeysNeverReceivePreviousBalance() {
        var first = service.balance(BASE, "first-key", false);
        assertSame(first, service.balance(BASE + "/v1", "first-key", false));
        assertSame(first, service.balance(BASE, "first-key", true));
        assertEquals(1, requests.size());
        clock.advance(3_001);
        service.balance(BASE, "first-key", true); assertEquals(2, requests.size());
        service.balance(BASE, "first-key", false); assertEquals(2, requests.size());
        clock.advance(25_001);
        service.balance(BASE, "first-key", false); assertEquals(3, requests.size());
        status = 401; body = "first-key second-key private body";
        var rotated = service.balance(BASE, "second-key", false);
        assertNull(rotated.checkedAt()); assertTrue(rotated.balances().isEmpty()); assertNull(rotated.available());
        assertTrue(rotated.error().contains("401")); assertFalse(rotated.error().contains("key"));
    }

    @Test void refreshFailureRetainsTimestampAndDataThenRecovers() {
        var first = service.balance(BASE, "fixture-secret", false);
        clock.advance(30_000); status = 429; body = "fixture-secret private error";
        var failed = service.balance(BASE, "fixture-secret", false);
        assertEquals("error", failed.status()); assertEquals(first.balances(), failed.balances());
        assertEquals(first.checkedAt(), failed.checkedAt()); assertEquals(clock.millis(), failed.attemptedAt());
        assertTrue(failed.error().contains("429")); assertFalse(failed.error().contains("fixture-secret"));
        assertSame(failed, service.balance(BASE, "fixture-secret", false)); assertEquals(2, requests.size());
        clock.advance(10_001); status = 200;
        body = VALID.replace("\"is_available\":true", "\"is_available\":false");
        var recovered = service.balance(BASE, "fixture-secret", false);
        assertEquals("ready", recovered.status()); assertFalse(recovered.available()); assertNull(recovered.error());
        assertTrue(recovered.checkedAt() > first.checkedAt());
    }

    @Test void malformedAndNetworkFailuresNeverBecomeZeroOrExposeUpstreamContent() {
        for (String bad : List.of("{}", "[]", "not JSON", "{\"is_available\":true,\"balance_infos\":[]}",
                VALID.replace("110.0012", "NaN"), VALID.replace("110.0012", "1e9999"), VALID.replace("CNY", "private"), "x".repeat(65_537))) {
            body = bad; clock.advance(30_000);
            var view = service.balance(BASE, "fixture-secret", false);
            assertEquals("error", view.status()); assertNull(view.checkedAt()); assertTrue(view.balances().isEmpty());
        }
        networkFailure = true; clock.advance(30_000);
        var view = service.balance(BASE, "fixture-secret", false);
        assertFalse(view.error().contains("private")); assertFalse(view.error().contains("fixture-secret"));
    }

    @Test void concurrentRowsCoalesceAndTheCredentialCacheIsBounded() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 12)
                    .mapToObj(i -> pool.submit(() -> service.balance(BASE, "shared-key", false))).toList();
            for (var task : tasks) assertEquals("ready", task.get().status());
        }
        assertEquals(1, requests.size());
        for (int i = 0; i < 128; i++) service.balance(BASE, "key-" + i, false);
        service.balance(BASE, "shared-key", false);
        assertEquals(130, requests.size());
    }

    private static final class TestClock extends Clock {
        private long now = 1_800_000_000_000L;
        void advance(long ms) { now += ms; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }
}
