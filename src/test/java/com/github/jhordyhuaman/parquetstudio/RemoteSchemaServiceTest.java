package com.github.jhordyhuaman.parquetstudio;

import com.github.jhordyhuaman.parquetstudio.service.RemoteSchemaService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteSchemaServiceTest {

  private HttpServer server;
  private HttpServer server2;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
    if (server2 != null) {
      server2.stop(0);
    }
  }

  private int startServer(java.util.function.BiConsumer<HttpExchange, String> handler) throws Exception {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/schema", exchange -> {
      try {
        String body = "{\"columns\":[]}";
        handler.accept(exchange, body);
      } finally {
        exchange.close();
      }
    });
    server.start();
    return server.getAddress().getPort();
  }

  @Test
  void fetchesBodyOnOk() throws Exception {
    int port = startServer((exchange, body) -> {
      try {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(bytes);
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    RemoteSchemaService service = new RemoteSchemaService();
    String result = service.fetchSchema("http://localhost:" + port + "/schema", null, RemoteSchemaService.TokenStyle.BEARER);

    assertThat(result).isEqualTo("{\"columns\":[]}");
  }

  @Test
  void sendsBearerHeader() throws Exception {
    String token = "secret-bearer-token";
    java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
    int port = startServer((exchange, body) -> {
      try {
        captured.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(bytes);
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    RemoteSchemaService service = new RemoteSchemaService();
    service.fetchSchema("http://localhost:" + port + "/schema", token, RemoteSchemaService.TokenStyle.BEARER);

    assertThat(captured.get()).isEqualTo("Bearer " + token);
  }

  @Test
  void sendsJfrogHeader() throws Exception {
    String token = "secret-jfrog-token";
    java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
    int port = startServer((exchange, body) -> {
      try {
        captured.set(exchange.getRequestHeaders().getFirst("X-JFrog-Art-Api"));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(bytes);
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    RemoteSchemaService service = new RemoteSchemaService();
    service.fetchSchema("http://localhost:" + port + "/schema", token, RemoteSchemaService.TokenStyle.JFROG);

    assertThat(captured.get()).isEqualTo(token);
  }

  @Test
  void non2xxThrowsWithoutToken() throws Exception {
    String token = "top-secret-value";
    int port = startServer((exchange, body) -> {
      try {
        byte[] bytes = "unauthorized".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(401, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(bytes);
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    RemoteSchemaService service = new RemoteSchemaService();
    String url = "http://localhost:" + port + "/schema?tok=" + token;

    assertThatThrownBy(() -> service.fetchSchema(url, token, RemoteSchemaService.TokenStyle.BEARER))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("401")
        .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(token));
  }

  @Test
  void redirectSameHostKeepsToken() throws Exception {
    String token = "same-origin-token";
    java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();

    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    int port = server.getAddress().getPort();
    server.createContext("/start", exchange -> {
      try {
        exchange.getResponseHeaders().add("Location", "/target");
        exchange.sendResponseHeaders(302, -1);
      } finally {
        exchange.close();
      }
    });
    server.createContext("/target", exchange -> {
      try {
        captured.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] bytes = "{\"columns\":[]}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(bytes);
        }
      } finally {
        exchange.close();
      }
    });
    server.start();

    RemoteSchemaService service = new RemoteSchemaService();
    String result = service.fetchSchema("http://localhost:" + port + "/start", token, RemoteSchemaService.TokenStyle.BEARER);

    assertThat(captured.get()).isEqualTo("Bearer " + token);
    assertThat(result).isEqualTo("{\"columns\":[]}");
  }

  @Test
  void redirectCrossHostDropsToken() throws Exception {
    String token = "cross-origin-token";
    java.util.concurrent.atomic.AtomicReference<String> capturedAuth = new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicReference<String> capturedJfrog = new java.util.concurrent.atomic.AtomicReference<>();

    server2 = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    int port2 = server2.getAddress().getPort();
    server2.createContext("/target", exchange -> {
      try {
        capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        capturedJfrog.set(exchange.getRequestHeaders().getFirst("X-JFrog-Art-Api"));
        byte[] bytes = "{\"columns\":[]}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(bytes);
        }
      } finally {
        exchange.close();
      }
    });
    server2.start();

    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    int port = server.getAddress().getPort();
    server.createContext("/start", exchange -> {
      try {
        exchange.getResponseHeaders().add("Location", "http://localhost:" + port2 + "/target");
        exchange.sendResponseHeaders(302, -1);
      } finally {
        exchange.close();
      }
    });
    server.start();

    RemoteSchemaService service = new RemoteSchemaService();
    String result = service.fetchSchema("http://localhost:" + port + "/start", token, RemoteSchemaService.TokenStyle.BEARER);

    assertThat(capturedAuth.get()).isNull();
    assertThat(capturedJfrog.get()).isNull();
    assertThat(result).isEqualTo("{\"columns\":[]}");
  }

  @Test
  void rejectsNonHttpScheme() {
    RemoteSchemaService service = new RemoteSchemaService();

    assertThatThrownBy(() -> service.fetchSchema("file:///etc/passwd", null, RemoteSchemaService.TokenStyle.BEARER))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
