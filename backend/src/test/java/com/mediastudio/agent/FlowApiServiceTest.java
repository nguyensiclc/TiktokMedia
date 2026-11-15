package com.mediastudio.agent;

import com.mediastudio.settings.AppSettings;
import com.mediastudio.settings.SettingsService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The client side of the local image service: request shape, per-image errors, base64 and image_url results. */
class FlowApiServiceTest {
    private HttpServer server;
    private FlowApiService svc;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> health = new AtomicReference<>("{\"status\":\"ok\",\"service_ready\":true,\"is_enabled\":true}");
    private final byte[] png = new byte[2000];

    @BeforeEach
    void start() throws Exception {
        png[0] = (byte) 0x89; png[1] = 'P'; png[2] = 'N'; png[3] = 'G';
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String b64 = Base64.getEncoder().encodeToString(png);
        server.createContext("/api/v1/health", ex -> {
            byte[] out = health.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length); ex.getResponseBody().write(out); ex.close();
        });
        server.createContext("/outputs/b.png", ex -> { ex.sendResponseHeaders(200, png.length); ex.getResponseBody().write(png); ex.close(); });
        server.createContext("/api/v1/generate", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            // out of order on purpose: results are matched by prompt text
            String body = """
                    {"images":[
                      {"prompt":"p3","file_path":null,"image_url":null,"base64":null,"error":"Flow từ chối prompt"},
                      {"prompt":"p1","file_path":"outputs\\\\a.png","image_url":"http://x/a.png","base64":"%s","error":null},
                      {"prompt":"p2","file_path":"outputs\\\\b.png","image_url":"http://127.0.0.1:%d/outputs/b.png","base64":"","error":null}
                    ],"total":3,"failed":["p3"]}""".formatted(b64, port);
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        AppSettings cfg = new AppSettings();
        cfg.flowApiUrl = "http://127.0.0.1:" + port + "/";
        SettingsService settings = mock(SettingsService.class);
        when(settings.get()).thenReturn(cfg);
        svc = new FlowApiService(null, null, settings);
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void healthOk() {
        assertNull(svc.health());
        health.set("ok"); // plain text is accepted too
        assertNull(svc.health());
    }

    @Test
    void healthReportsDisabledOrNotReady() {
        health.set("{\"status\":\"ok\",\"service_ready\":true,\"is_enabled\":false}");
        assertTrue(svc.health().contains("đang tắt"));
        health.set("{\"status\":\"ok\",\"service_ready\":false,\"is_enabled\":true}");
        assertTrue(svc.health().contains("chưa sẵn sàng"));
    }

    @Test
    void generateMatchesByPromptAndKeepsPerImageErrors() throws Exception {
        List<FlowApiService.Result> r = svc.generate(List.of("p1", "p2", "p3"));
        assertTrue(lastBody.get().contains("\"prompts\":[\"p1\",\"p2\",\"p3\"]"), lastBody.get());
        assertEquals(3, r.size());
        assertArrayEquals(png, r.get(0).image(), "p1 from base64");
        assertNull(r.get(0).error());
        assertArrayEquals(png, r.get(1).image(), "p2 fetched from image_url");
        assertNull(r.get(2).image());
        assertEquals("Flow từ chối prompt", r.get(2).error());
    }

    @Test
    void sendsAspectRatio() throws Exception {
        svc.generate(List.of("p1"), "9:16");
        assertTrue(lastBody.get().contains("\"aspect_ratio\":\"9:16\""), lastBody.get());
        svc.generate(List.of("p1"), "21:9"); // unsupported: left out, the service keeps its setting
        assertFalse(lastBody.get().contains("aspect_ratio"), lastBody.get());
    }

    @Test
    void unreachableServiceIsReported() {
        server.stop(0);
        String h = svc.health();
        assertNotNull(h);
        assertTrue(h.contains("không kết nối"), h);
    }

    @Test
    void timeoutFollowsServiceRecommendation() {
        // n × 60 + 120 s margin, never below the minimum from Settings
        assertEquals(180, svc.timeout(1).toSeconds());
        assertEquals(240, svc.timeout(2).toSeconds());
        assertEquals(360, svc.timeout(4).toSeconds());
        assertEquals(720, svc.timeout(10).toSeconds());
    }

    @Test
    void extensionDetection() {
        assertEquals(".png", FlowApiService.ext(png));
        assertEquals(".jpg", FlowApiService.ext(new byte[] { (byte) 0xFF, (byte) 0xD8, 0, 0 }));
    }
}
