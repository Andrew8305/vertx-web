package io.vertx.webclient;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.VertxException;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.dns.AddressResolverOptions;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import io.vertx.test.core.HttpTestBase;
import io.vertx.test.core.TestUtils;
import io.vertx.webclient.impl.HttpContext;
import io.vertx.webclient.impl.WebClientInternal;
import io.vertx.webclient.jackson.WineAndCheese;
import org.junit.Test;

import java.io.File;
import java.net.ConnectException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class WebClientTest extends HttpTestBase {

  private WebClient client;

  @Override
  protected VertxOptions getOptions() {
    return super.getOptions().setAddressResolverOptions(new AddressResolverOptions().
      setHostsValue(Buffer.buffer(
        "127.0.0.1 somehost\n" +
        "127.0.0.1 localhost")));
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    super.client = vertx.createHttpClient(new HttpClientOptions().setDefaultPort(8080).setDefaultHost("localhost"));
    client = WebClient.wrap(super.client);
    server = vertx.createHttpServer(new HttpServerOptions().setPort(DEFAULT_HTTP_PORT).setHost(DEFAULT_HTTP_HOST));
  }

  @Test
  public void testDefaultHostAndPort() throws Exception {
    testRequest(client -> client.get("somepath"), req -> {
      assertEquals("localhost:8080", req.host());
    });
  }

  @Test
  public void testDefaultPort() throws Exception {
    testRequest(client -> client.get("somehost", "somepath"), req -> {
      assertEquals("somehost:8080", req.host());
    });
  }

  @Test
  public void testDefaultHost() throws Exception {
    try {
      client.get("somepath").port(8080).send(ar -> {
        fail();
      });
      fail();
    } catch (IllegalStateException ignore) {
    }
  }

  @Test
  public void testGet() throws Exception {
    testRequest(HttpMethod.GET);
  }

  @Test
  public void testHead() throws Exception {
    testRequest(HttpMethod.HEAD);
  }

  @Test
  public void testDelete() throws Exception {
    testRequest(HttpMethod.DELETE);
  }

  private void testRequest(HttpMethod method) throws Exception {
    testRequest(client -> {
      switch (method) {
        case GET:
          return client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
        case HEAD:
          return client.head(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
        case DELETE:
          return client.delete(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
        default:
          fail("Invalid HTTP method");
          return null;
      }
    }, req -> assertEquals(method, req.method()));
  }

  private void testRequest(Function<WebClient, HttpRequest> reqFactory, Consumer<HttpServerRequest> reqChecker) throws Exception {
    waitFor(4);
    server.requestHandler(req -> {
      reqChecker.accept(req);
      complete();
      req.response().end();
    });
    startServer();
    HttpRequest builder = reqFactory.apply(client);
    builder.send(onSuccess(resp -> {
      complete();
    }));
    builder.send(onSuccess(resp -> {
      complete();
    }));
    await();
  }

  @Test
  public void testPost() throws Exception {
    testRequestWithBody(HttpMethod.POST, false);
  }

  @Test
  public void testPostChunked() throws Exception {
    testRequestWithBody(HttpMethod.POST, true);
  }

  @Test
  public void testPut() throws Exception {
    testRequestWithBody(HttpMethod.PUT, false);
  }

  @Test
  public void testPutChunked() throws Exception {
    testRequestWithBody(HttpMethod.PUT, true);
  }

  @Test
  public void testPatch() throws Exception {
    testRequestWithBody(HttpMethod.PATCH, false);
  }

  private void testRequestWithBody(HttpMethod method, boolean chunked) throws Exception {
    String expected = TestUtils.randomAlphaString(1024 * 1024);
    File f = File.createTempFile("vertx", ".data");
    f.deleteOnExit();
    Files.write(f.toPath(), expected.getBytes());
    waitFor(2);
    server.requestHandler(req -> req.bodyHandler(buff -> {
      assertEquals(method, req.method());
      assertEquals(Buffer.buffer(expected), buff);
      complete();
      req.response().end();
    }));
    startServer();
    vertx.runOnContext(v -> {
      AsyncFile asyncFile = vertx.fileSystem().openBlocking(f.getAbsolutePath(), new OpenOptions());

      HttpRequest builder = null;

      switch (method) {
        case POST:
          builder = client.post(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
          break;
        case PUT:
          builder = client.put(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
          break;
        case PATCH:
          builder = client.patch(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
          break;
        default:
          fail("Invalid HTTP method");
      }

      if (!chunked) {
        builder = builder.putHeader("Content-Length", "" + expected.length());
      }
      builder.sendStream(asyncFile, onSuccess(resp -> {
            assertEquals(200, resp.statusCode());
            complete();
          }));
    });
    await();
  }

  @Test
  public void testSendJsonObjectBody() throws Exception {
    JsonObject body = new JsonObject().put("wine", "Chateauneuf Du Pape").put("cheese", "roquefort");
    testSendBody(body, (contentType, buff) -> {
      assertEquals("application/json", contentType);
      assertEquals(body, buff.toJsonObject());
    });
  }

  @Test
  public void testSendJsonPojoBody() throws Exception {
    testSendBody(new WineAndCheese().setCheese("roquefort").setWine("Chateauneuf Du Pape"),
        (contentType, buff) -> {
          assertEquals("application/json", contentType);
          assertEquals(new JsonObject().put("wine", "Chateauneuf Du Pape").put("cheese", "roquefort"), buff.toJsonObject());
        });
  }

  @Test
  public void testSendJsonArrayBody() throws Exception {
    JsonArray body = new JsonArray().add(0).add(1).add(2);
    testSendBody(body, (contentType, buff) -> {
      assertEquals("application/json", contentType);
      assertEquals(body, buff.toJsonArray());
    });
  }

  @Test
  public void testSendBufferBody() throws Exception {
    Buffer body = TestUtils.randomBuffer(2048);
    testSendBody(body, (contentType, buff) -> assertEquals(body, buff));
  }

  private void testSendBody(Object body, BiConsumer<String, Buffer> checker) throws Exception {
    waitFor(2);
    server.requestHandler(req -> {
      req.bodyHandler(buff -> {
        checker.accept(req.getHeader("content-type"), buff);
        complete();
        req.response().end();
      });
    });
    startServer();
    HttpRequest post = client.post(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    if (body instanceof Buffer) {
      post.sendBuffer((Buffer) body, onSuccess(resp -> {
        complete();
      }));
    } else if (body instanceof JsonObject) {
      post.sendJsonObject((JsonObject) body, onSuccess(resp -> {
        complete();
      }));
    } else {
      post.sendJson(body, onSuccess(resp -> {
        complete();
      }));
    }
    await();
  }

  @Test
  public void testConnectError() throws Exception {
    HttpRequest get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(onFailure(err -> {
      assertTrue(err instanceof ConnectException);
      complete();
    }));
    await();
  }

  @Test
  public void testRequestSendError() throws Exception {
    HttpRequest post = client.post(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    server.requestHandler(req -> {
      req.handler(buff -> {
        req.connection().close();
      });
    });
    startServer();
    post.putHeader("Content-Length", "2048")
        .sendStream(new ReadStream<Buffer>() {
          @Override
          public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
          }
          @Override
          public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            handler.handle(TestUtils.randomBuffer(1024));
            return this;
          }
          @Override
          public ReadStream<Buffer> pause() {
            return this;
          }
          @Override
          public ReadStream<Buffer> resume() {
            return this;
          }
          @Override
          public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
            return this;
          }
        }, onFailure(err -> {
          // Should be a connection reset by peer or closed
          complete();
        }));
    await();
  }

  @Test
  public void testRequestPumpError() throws Exception {
    waitFor(2);
    HttpRequest post = client.post(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    CompletableFuture<Void> done = new CompletableFuture<>();
    server.requestHandler(req -> {
      req.response().closeHandler(v -> {
        complete();
      });
      req.handler(buff -> {
        done.complete(null);
      });
    });
    Throwable cause = new Throwable();
    startServer();
    post.sendStream(new ReadStream<Buffer>() {
          @Override
          public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            if (handler != null) {
              done.thenAccept(v -> {
                handler.handle(cause);
              });
            }
            return this;
          }
          @Override
          public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            if (handler != null) {
              handler.handle(TestUtils.randomBuffer(1024));
            }
            return this;
          }
          @Override
          public ReadStream<Buffer> pause() {
            return this;
          }
          @Override
          public ReadStream<Buffer> resume() {
            return this;
          }
          @Override
          public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
            return this;
          }
        }, onFailure(err -> {
          assertSame(cause, err);
          complete();
        }));
    await();
  }

  @Test
  public void testRequestPumpErrorNotYetConnected() throws Exception {
    HttpRequest post = client.post(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    server.requestHandler(req -> {
      fail();
    });
    Throwable cause = new Throwable();
    startServer();
    post.sendStream(new ReadStream<Buffer>() {
      Handler<Throwable> exceptionHandler;
      @Override
      public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        exceptionHandler = handler;
        return this;
      }
      @Override
      public ReadStream<Buffer> handler(Handler<Buffer> handler) {
        if (handler != null) {
          handler.handle(TestUtils.randomBuffer(1024));
          vertx.runOnContext(v -> {
            exceptionHandler.handle(cause);
          });
        }
        return this;
      }
      @Override
      public ReadStream<Buffer> pause() {
        return this;
      }
      @Override
      public ReadStream<Buffer> resume() {
        return this;
      }
      @Override
      public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
        return this;
      }
    }, onFailure(err -> {
      assertSame(cause, err);
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseBodyAsBuffer() throws Exception {
    Buffer expected = TestUtils.randomBuffer(2000);
    server.requestHandler(req -> {
      req.response().end(expected);
    });
    startServer();
    HttpRequest get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      assertEquals(expected, resp.body());
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseBodyAsAsJsonObject() throws Exception {
    JsonObject expected = new JsonObject().put("cheese", "Goat Cheese").put("wine", "Condrieu");
    server.requestHandler(req -> {
      req.response().end(expected.encode());
    });
    startServer();
    HttpRequest get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(BodyCodec.jsonObject(), onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      assertEquals(expected, resp.body());
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseBodyAsAsJsonMapped() throws Exception {
    JsonObject expected = new JsonObject().put("cheese", "Goat Cheese").put("wine", "Condrieu");
    server.requestHandler(req -> {
      req.response().end(expected.encode());
    });
    startServer();
    HttpRequest get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(BodyCodec.json(WineAndCheese.class), onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      assertEquals(new WineAndCheese().setCheese("Goat Cheese").setWine("Condrieu"), resp.body());
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseBodyDiscarded() throws Exception {
    server.requestHandler(req -> {
      req.response().end(TestUtils.randomAlphaString(1024));
    });
    startServer();
    HttpRequest get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(BodyCodec.none(), onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      assertEquals(null, resp.body());
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseUnknownContentTypeBodyAsJsonObject() throws Exception {
    JsonObject expected = new JsonObject().put("cheese", "Goat Cheese").put("wine", "Condrieu");
    server.requestHandler(req -> {
      req.response().end(expected.encode());
    });
    startServer();
    HttpRequest get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      assertEquals(expected, resp.bodyAsJsonObject());
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseUnknownContentTypeBodyAsJsonMapped() throws Exception {
    JsonObject expected = new JsonObject().put("cheese", "Goat Cheese").put("wine", "Condrieu");
    server.requestHandler(req -> {
      req.response().end(expected.encode());
    });
    startServer();
    HttpRequest get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      assertEquals(new WineAndCheese().setCheese("Goat Cheese").setWine("Condrieu"), resp.bodyAsJson(WineAndCheese.class));
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseBodyUnmarshallingError() throws Exception {
    server.requestHandler(req -> {
      req.response().end("not-json-object");
    });
    startServer();
    HttpRequest get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(BodyCodec.jsonObject(), onFailure(err -> {
      assertTrue(err instanceof DecodeException);
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseBodyStream() throws Exception {
    AtomicBoolean paused = new AtomicBoolean();
    server.requestHandler(req -> {
      HttpServerResponse resp = req.response();
      resp.setChunked(true);
      vertx.setPeriodic(1, id -> {
        if (!resp.writeQueueFull()) {
          resp.write(TestUtils.randomAlphaString(1024));
        } else {
          resp.drainHandler(v -> {
            resp.end();
          });
          paused.set(true);
          vertx.cancelTimer(id);
        }
      });
    });
    startServer();
    CompletableFuture<Void> resume = new CompletableFuture<>();
    AtomicInteger size = new AtomicInteger();
    AtomicBoolean ended = new AtomicBoolean();
    WriteStream<Buffer> stream = new WriteStream<Buffer>() {
      boolean paused = true;
      Handler<Void> drainHandler;
      {
        resume.thenAccept(v -> {
          paused = false;
          if (drainHandler != null) {
            drainHandler.handle(null);
          }
        });
      }
      @Override
      public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        return this;
      }
      @Override
      public WriteStream<Buffer> write(Buffer data) {
        size.addAndGet(data.length());
        return this;
      }
      @Override
      public void end() {
        ended.set(true);
      }
      @Override
      public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
        return this;
      }
      @Override
      public boolean writeQueueFull() {
        return paused;
      }
      @Override
      public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
        drainHandler = handler;
        return this;
      }
    };
    HttpRequest get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(BodyCodec.pipe(stream), onSuccess(resp -> {
      assertTrue(ended.get());
      assertEquals(200, resp.statusCode());
      assertEquals(null, resp.body());
      testComplete();
    }));
    waitUntil(paused::get);
    resume.complete(null);
    await();
  }

  @Test
  public void testResponseBodyStreamError() throws Exception {
    CompletableFuture<Void> fail = new CompletableFuture<>();
    server.requestHandler(req -> {
      HttpServerResponse resp = req.response();
      resp.setChunked(true);
      resp.write(TestUtils.randomBuffer(2048));
      fail.thenAccept(v -> {
        resp.close();
      });
    });
    startServer();
    AtomicInteger received = new AtomicInteger();
    WriteStream<Buffer> stream = new WriteStream<Buffer>() {
      @Override
      public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        return this;
      }
      @Override
      public WriteStream<Buffer> write(Buffer data) {
        received.addAndGet(data.length());
        return this;
      }
      @Override
      public void end() {
      }
      @Override
      public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
        return this;
      }
      @Override
      public boolean writeQueueFull() {
        return false;
      }
      @Override
      public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
        return this;
      }
    };
    HttpRequest get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(BodyCodec.pipe(stream), onFailure(err -> {
      testComplete();
    }));
    waitUntil(() -> received.get() == 2048);
    fail.complete(null);
    await();
  }

  @Test
  public void testResponseBodyCodecError() throws Exception {
    server.requestHandler(req -> {
      HttpServerResponse resp = req.response();
      resp.setChunked(true);
      resp.end(TestUtils.randomBuffer(2048));
    });
    startServer();
    RuntimeException cause = new RuntimeException();
    WriteStream<Buffer> stream = new WriteStream<Buffer>() {
      Handler<Throwable> exceptionHandler;
      @Override
      public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        exceptionHandler = handler;
        return this;
      }
      @Override
      public WriteStream<Buffer> write(Buffer data) {
        exceptionHandler.handle(cause);
        return this;
      }
      @Override
      public void end() {
      }
      @Override
      public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
        return this;
      }
      @Override
      public boolean writeQueueFull() {
        return false;
      }
      @Override
      public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
        return this;
      }
    };
    HttpRequest get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(BodyCodec.pipe(stream), onFailure(err -> {
      assertSame(cause, err);
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseJsonObjectMissingBody() throws Exception {
    testResponseMissingBody(BodyCodec.jsonObject());
  }

  @Test
  public void testResponseJsonMissingBody() throws Exception {
    testResponseMissingBody(BodyCodec.json(WineAndCheese.class));
  }

  @Test
  public void testResponseWriteStreamMissingBody() throws Exception {
    AtomicInteger length = new AtomicInteger();
    AtomicBoolean ended = new AtomicBoolean();
    WriteStream<Buffer> stream = new WriteStream<Buffer>() {
      @Override
      public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        return this;
      }
      @Override
      public WriteStream<Buffer> write(Buffer data) {
        length.addAndGet(data.length());
        return this;
      }
      @Override
      public void end() {
        ended.set(true);
      }
      @Override
      public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
        return this;
      }
      @Override
      public boolean writeQueueFull() {
        return false;
      }
      @Override
      public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
        return this;
      }
    };
    testResponseMissingBody(BodyCodec.pipe(stream));
    assertTrue(ended.get());
    assertEquals(0, length.get());
  }

  private <R> void testResponseMissingBody(BodyCodec<R> codec) throws Exception {
    server.requestHandler(req -> {
      req.response().setStatusCode(403).end();
    });
    startServer();
    HttpRequest get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(codec, onSuccess(resp -> {
      assertEquals(403, resp.statusCode());
      assertNull(resp.body());
      testComplete();
    }));
    await();
  }

  @Test
  public void testHttpResponseError() throws Exception {
    server.requestHandler(req -> {
      req.response().setChunked(true).write(Buffer.buffer("some-data")).close();
    });
    startServer();
    HttpRequest get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(BodyCodec.jsonObject(), onFailure(err -> {
      assertTrue(err instanceof VertxException);
      testComplete();
    }));
    await();
  }

  @Test
  public void testTimeout() throws Exception {
    AtomicInteger count = new AtomicInteger();
    server.requestHandler(req -> {
      count.incrementAndGet();
    });
    startServer();
    HttpRequest get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.timeout(50).send(onFailure(err -> {
      assertEquals(err.getClass(), TimeoutException.class);
      testComplete();
    }));
    await();
  }

  @Test
  public void testQueryParam() throws Exception {
    testRequest(client -> client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/").addQueryParam("param", "param_value"), req -> {
      assertEquals("param=param_value", req.query());
      assertEquals("param_value", req.getParam("param"));
    });
  }

  @Test
  public void testQueryParamMulti() throws Exception {
    testRequest(client -> client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/").addQueryParam("param", "param_value1").addQueryParam("param", "param_value2"), req -> {
      assertEquals("param=param_value1&param=param_value2", req.query());
      assertEquals(Arrays.asList("param_value1", "param_value2"), req.params().getAll("param"));
    });
  }

  @Test
  public void testQueryParamAppend() throws Exception {
    testRequest(client -> client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/?param1=param1_value1").addQueryParam("param1", "param1_value2").addQueryParam("param2", "param2_value"), req -> {
      assertEquals("param1=param1_value1&param1=param1_value2&param2=param2_value", req.query());
      assertEquals("param1_value2", req.getParam("param1"));
      assertEquals("param2_value", req.getParam("param2"));
    });
  }

  @Test
  public void testOverwriteQueryParams() throws Exception {
    testRequest(client -> client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/?param=param_value1").setQueryParam("param", "param_value2"), req -> {
      assertEquals("param=param_value2", req.query());
      assertEquals("param_value2", req.getParam("param"));
    });
  }

  @Test
  public void testQueryParamEncoding() throws Exception {
    testRequest(client -> client
      .get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/")
      .addQueryParam("param1", " ")
      .addQueryParam("param2", "\u20AC"), req -> {
      assertEquals("param1=%20&param2=%E2%82%AC", req.query());
      assertEquals(" ", req.getParam("param1"));
      assertEquals("\u20AC", req.getParam("param2"));
    });
  }

  @Test
  public void testFormUrlEncoded() throws Exception {
    server.requestHandler(req -> {
      req.setExpectMultipart(true);
      req.endHandler(v -> {
        assertEquals("param1_value", req.getFormAttribute("param1"));
        req.response().end();
      });
    });
    startServer();
    MultiMap form = MultiMap.caseInsensitiveMultiMap();
    form.add("param1", "param1_value");
    HttpRequest builder = client.post("/somepath");
    builder.sendForm(form, onSuccess(resp -> {
      complete();
    }));
    await();
  }

  @Test
  public void testFormMultipart() throws Exception {
    server.requestHandler(req -> {
      req.setExpectMultipart(true);
      req.endHandler(v -> {
        assertEquals("param1_value", req.getFormAttribute("param1"));
        req.response().end();
      });
    });
    startServer();
    MultiMap form = MultiMap.caseInsensitiveMultiMap();
    form.add("param1", "param1_value");
    HttpRequest builder = client.post("/somepath");
    builder.putHeader("content-type", "multipart/form-data");
    builder.sendForm(form, onSuccess(resp -> {
      complete();
    }));
    await();
  }

  private <R> void handleRetry(HttpContext<R> context) {
    Handler<AsyncResult<HttpResponse<R>>> originalHandler = context.getResponseHandler();
    AtomicInteger payload = context.getPayload("retry-interceptors");
    if (payload == null) {
      payload = new AtomicInteger(4);
      context.setPayload("retry-interceptors", payload);
    }
    AtomicInteger count = payload;
    context.setRequest(context.getRequest().copy());
    context.setResponseHandler(ar -> {
      if (ar.succeeded()) {
        originalHandler.handle(ar);
      } else {
        if (count.decrementAndGet() > 0) {
          context.send();
        } else {
          originalHandler.handle(ar);
        }
      }
    });
    context.next();
  }

  @Test
  public void testRetryInterceptor() throws Exception {
    AtomicInteger times = new AtomicInteger();
    server.requestHandler(req -> {
      if (times.incrementAndGet() == 4) {
        req.response().end();
      } else {
        req.response().close();
      }
    });
    startServer();
    ((WebClientInternal) client).addInterceptor(this::handleRetry);
    HttpRequest builder = client.get("/somepath");
    builder.send(onSuccess(resp -> {
      assertEquals(4, times.get());
      complete();
    }));
    await();
  }

  private <R> void handleMutateRequest(HttpContext<R> context) {
    context.getRequest().host("localhost");
    context.getRequest().port(8080);
    context.next();
  }

  @Test
  public void testMutateRequestInterceptor() throws Exception {
    server.requestHandler(req -> {
      req.response().end();
    });
    startServer();
    ((WebClientInternal) client).addInterceptor(this::handleMutateRequest);
    HttpRequest builder = client.get("/somepath").host("another-host").port(8081);
    builder.send(onSuccess(resp -> {
      complete();
    }));
    await();
  }

  private <R> void handleMutateResponse(HttpContext<R> context) {
    Handler<AsyncResult<HttpResponse<R>>> responseHandler = context.getResponseHandler();
    context.setResponseHandler(ar -> {
      if (ar.succeeded()) {
        HttpResponse<R> resp = ar.result();
        assertEquals(500, resp.statusCode());
        responseHandler.handle(Future.succeededFuture(new HttpResponseImpl<R>() {
          @Override
          public int statusCode() {
            return 200;
          }
        }));
      } else {
        responseHandler.handle(ar);
      }
    });
    context.next();
  }

  @Test
  public void testMutateResponseInterceptor() throws Exception {
    server.requestHandler(req -> {
      req.response().setStatusCode(500).end();
    });
    startServer();
    ((WebClientInternal) client).addInterceptor(this::handleMutateResponse);
    HttpRequest builder = client.get("/somepath");
    builder.send(onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      complete();
    }));
    await();
  }

  private <R> void handleCacheInterceptor(HttpContext<R> context) {
    context.getResponseHandler().handle(Future.succeededFuture(new HttpResponseImpl<>()));
  }

  @Test
  public void testCacheInterceptor() throws Exception {
    server.requestHandler(req -> {
      fail();
    });
    startServer();
    ((WebClientInternal) client).addInterceptor(this::handleCacheInterceptor);
    HttpRequest builder = client.get("/somepath").host("localhost").port(8080);
    builder.send(onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      complete();
    }));
    await();
  }

  private static class HttpResponseImpl<R> implements HttpResponse<R> {
    @Override
    public HttpVersion version() {
      return HttpVersion.HTTP_1_1;
    }

    @Override
    public int statusCode() {
      return 200;
    }

    @Override
    public String statusMessage() {
      return null;
    }

    @Override
    public MultiMap headers() {
      return null;
    }

    @Override
    public String getHeader(String headerName) {
      return null;
    }

    @Override
    public MultiMap trailers() {
      return null;
    }

    @Override
    public String getTrailer(String trailerName) {
      return null;
    }

    @Override
    public List<String> cookies() {
      return null;
    }

    @Override
    public R body() {
      return null;
    }

    @Override
    public Buffer bodyAsBuffer() {
      return null;
    }
  }
}
