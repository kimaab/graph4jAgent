package com.graph.graphtemp.tools;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Fetches a URL and returns its body as text, truncated so one page cannot flood the context. */
@Component
public class HttpGetTool extends BuiltinTool {

    private static final String SCHEMA = """
            {"type":"object",\
            "properties":{"url":{"type":"string","description":"Absolute http(s) URL to fetch"}},\
            "required":["url"]}""";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_CHARS = 8_000;

    private final ObjectMapper mapper;
    private final HttpClient client;

    public HttpGetTool(ObjectMapper mapper) {
        super("http_get", "Fetch the text content at an http(s) URL.", SCHEMA);
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String call(String toolInput) {
        String url;
        try {
            JsonNode node = mapper.readTree(toolInput);
            JsonNode field = node.get("url");
            url = field == null ? null : field.asString();
        } catch (RuntimeException e) {
            return "error: tool input was not valid JSON";
        }
        if (url == null || url.isBlank()) {
            return "error: 'url' is required";
        }

        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return "error: malformed url";
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "error: only http and https urls are allowed";
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("User-Agent", "agent-studio/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            if (body.length() > MAX_CHARS) {
                body = body.substring(0, MAX_CHARS) + "\n...[truncated]";
            }
            return "HTTP " + response.statusCode() + "\n" + body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "error: request interrupted";
        } catch (Exception e) {
            return "error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
