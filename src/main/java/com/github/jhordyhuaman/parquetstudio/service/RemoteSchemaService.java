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
  private static final int MAX_REDIRECTS = 5;

  public enum TokenStyle {
    BEARER,
    JFROG
  }

  /**
   * Fetches the schema body from url. token may be null/blank for public URLs.
   */
  public String fetchSchema(String url, String token, TokenStyle style) throws Exception {
    URI originalUri = URI.create(url);
    String scheme = originalUri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      // Private/loopback IP ranges are intentionally allowed: internal Artifactory is the primary use case.
      throw new IllegalArgumentException("Only http/https URLs are supported");
    }

    // Redirects are followed manually (not via HttpClient.Redirect.NORMAL) so the credential
    // header is only re-attached when the redirect target shares the original request's origin.
    HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(TIMEOUT)
        .build();

    URI currentUri = originalUri;
    String originalAuthority = originalUri.getAuthority();
    String originalScheme = originalUri.getScheme();

    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      boolean sameOrigin = currentUri.getAuthority() != null
          && currentUri.getAuthority().equals(originalAuthority)
          && currentUri.getScheme() != null
          && currentUri.getScheme().equalsIgnoreCase(originalScheme);

      HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
          .uri(currentUri)
          .timeout(TIMEOUT)
          .GET();

      if (sameOrigin && token != null && !token.isBlank()) {
        switch (style) {
          case BEARER -> requestBuilder.header("Authorization", "Bearer " + token);
          case JFROG -> requestBuilder.header("X-JFrog-Art-Api", token);
        }
      }

      HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();

      if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
        if (hop == MAX_REDIRECTS) {
          throw new IOException("Schema fetch failed: too many redirects for " + urlWithoutQuery(url));
        }
        String location = response.headers().firstValue("Location")
            .orElseThrow(() -> new IOException("Schema fetch failed: redirect with no Location for " + urlWithoutQuery(url)));
        currentUri = currentUri.resolve(location);
        continue;
      }

      if (status < 200 || status >= 300) {
        throw new IOException("Schema fetch failed: HTTP " + status + " for " + urlWithoutQuery(url));
      }

      return response.body();
    }

    throw new IOException("Schema fetch failed: too many redirects for " + urlWithoutQuery(url));
  }

  private static String urlWithoutQuery(String url) {
    int idx = url.indexOf('?');
    return idx >= 0 ? url.substring(0, idx) : url;
  }
}
