package com.github.jhordyhuaman.parquetstudio.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches a schema document over HTTP/HTTPS, optionally authenticating with a
 * Bearer token or a JFrog Artifactory API key header. Never logs, persists, or
 * echoes the token anywhere.
 */
public class RemoteSchemaService {

  private static final Duration TIMEOUT = Duration.ofSeconds(15);

  public enum TokenStyle {
    BEARER,
    JFROG
  }

  /**
   * Fetches the schema body from url. token may be null/blank for public URLs.
   */
  public String fetchSchema(String url, String token, TokenStyle style) throws Exception {
    HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(TIMEOUT)
        .build();

    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(TIMEOUT)
        .GET();

    if (token != null && !token.isBlank()) {
      switch (style) {
        case BEARER -> requestBuilder.header("Authorization", "Bearer " + token);
        case JFROG -> requestBuilder.header("X-JFrog-Art-Api", token);
      }
    }

    HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("Schema fetch failed: HTTP " + response.statusCode() + " for " + urlWithoutQuery(url));
    }

    return response.body();
  }

  private static String urlWithoutQuery(String url) {
    int idx = url.indexOf('?');
    return idx >= 0 ? url.substring(0, idx) : url;
  }
}
