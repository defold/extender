package com.defold.extender.remote.operator;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Scriptable fake of the operator's touch API, on loopback. */
final class StubOperator implements AutoCloseable {

    record Reply(int status, String body, Map<String, String> headers) {
    }

    record Seen(String method, String path, String authorization) {
    }

    static Reply ready() {
        return new Reply(200, "{\"phase\":\"Ready\"}", Map.of());
    }

    static Reply waking() {
        return new Reply(202, "{\"phase\":\"Waking\"}", Map.of());
    }

    static Reply state(String phase, boolean alwaysOn) {
        return new Reply(200, "{\"phase\":\"" + phase + "\",\"alwaysOn\":" + alwaysOn + "}", Map.of());
    }

    static Reply error(int status, String code) {
        return new Reply(status, "{\"error\":\"" + code + "\"}", Map.of());
    }

    static Reply error(int status, String code, Map<String, String> headers) {
        return new Reply(status, "{\"error\":\"" + code + "\"}", headers);
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Deque<Reply> touchReplies = new ConcurrentLinkedDeque<>();
    private final Deque<Reply> stateReplies = new ConcurrentLinkedDeque<>();
    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    private volatile Reply defaultTouch = error(500, "no_script");
    private volatile Reply defaultState = error(500, "no_script");

    StubOperator() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** Replies for successive touches; the last one repeats. */
    StubOperator onTouch(Reply... replies) {
        script(touchReplies, replies, true);
        return this;
    }

    /** Replies for successive state calls; the last one repeats. */
    StubOperator onState(Reply... replies) {
        script(stateReplies, replies, false);
        return this;
    }

    private void script(Deque<Reply> queue, Reply[] replies, boolean touch) {
        queue.clear();
        for (int i = 0; i < replies.length - 1; i++) {
            queue.add(replies[i]);
        }
        if (touch) {
            defaultTouch = replies[replies.length - 1];
        } else {
            defaultState = replies[replies.length - 1];
        }
    }

    List<Seen> seen() {
        synchronized (seen) {
            return List.copyOf(seen);
        }
    }

    long count(String method) {
        return seen().stream().filter(s -> s.method().equals(method)).count();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        seen.add(new Seen(exchange.getRequestMethod(), path, exchange.getRequestHeaders().getFirst("Authorization")));
        Reply reply;
        if (path.endsWith("/touch")) {
            Reply next = touchReplies.poll();
            reply = next != null ? next : defaultTouch;
        } else {
            Reply next = stateReplies.poll();
            reply = next != null ? next : defaultState;
        }
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
