package dev.kyrylo.ticket_triage.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Level 2: an HTTP server that speaks the OpenAI chat completions API. The real Spring AI client and the real
 * OpenAI SDK talk to it, so the tests see real serialization, real SDK exceptions, retries and timeouts.
 * The JDK's built-in HttpServer is enough; no extra dependency.
 */
public class FakeOpenAiServer implements AutoCloseable {

    public record Reply(int status, String body, Duration delay) {

        /** A successful completion; like the real API it reports the model that answered. */
        public static Reply ok(String model, String content) {
            return new Reply(200, completion(model, content), Duration.ZERO);
        }

        public static Reply error(int status, String code) {
            return new Reply(status, """
                    {"error": {"message": "fake %s", "type": "%s", "param": null, "code": "%s"}}
                    """.formatted(code, code, code), Duration.ZERO);
        }

        public Reply after(Duration delay) {
            return new Reply(status, body, delay);
        }
    }

    private static final Pattern MODEL = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpServer server;
    private final List<String> requestedModels = new CopyOnWriteArrayList<>();
    private volatile BiFunction<String, String, Reply> responder = (model, body) -> Reply.ok(model, "{}");

    public FakeOpenAiServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        // A thread per request: a slow reply (timeout test) must not block the fallback request behind it
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/v1";
    }

    /** Decides the reply from the requested model and the raw request body. */
    public void respond(BiFunction<String, String, Reply> responder) {
        this.responder = responder;
        requestedModels.clear();
    }

    public List<String> requestedModels() {
        return List.copyOf(requestedModels);
    }

    public static boolean isTriage(String requestBody) {
        return requestBody.contains("triage assistant");
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher matcher = MODEL.matcher(body);
        String model = matcher.find() ? matcher.group(1) : "unknown";
        requestedModels.add(model);

        Reply reply = responder.apply(model, body);
        if (!reply.delay().isZero()) {
            try {
                Thread.sleep(reply.delay());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } catch (IOException ignored) {
            // the client gave up (timeout test): nobody reads the answer
        }
    }

    private static String completion(String model, String content) {
        return """
                {"id": "chatcmpl-fake", "object": "chat.completion", "created": 1761800000, "model": "%s",
                 "choices": [{"index": 0, "finish_reason": "stop",
                              "message": {"role": "assistant", "content": "%s", "refusal": null}}],
                 "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120}}
                """.formatted(model, escape(content));
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
