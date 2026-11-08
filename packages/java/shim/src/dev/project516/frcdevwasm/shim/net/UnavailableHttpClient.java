package dev.project516.frcdevwasm.shim.net;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * An HttpClient whose requests fail with an IOException. The JDK client needs sockets and
 * selectors, which the browser JVM does not have. The vendor jar transform points calls to
 * {@code HttpClient.newHttpClient()} and {@code newBuilder()} here, so libraries that only
 * poll a robot radio or a coprocessor see the connection as down.
 */
public final class UnavailableHttpClient extends HttpClient {
  private static final String MESSAGE = "HTTP requests are not available in the browser";

  private UnavailableHttpClient() {}

  public static HttpClient newHttpClient() {
    return new UnavailableHttpClient();
  }

  public static HttpClient.Builder newBuilder() {
    return new Builder();
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return Optional.empty();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return Optional.empty();
  }

  @Override
  public Redirect followRedirects() {
    return Redirect.NEVER;
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return Optional.empty();
  }

  @Override
  public SSLContext sslContext() {
    return null;
  }

  @Override
  public SSLParameters sslParameters() {
    return new SSLParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return Optional.empty();
  }

  @Override
  public Version version() {
    return Version.HTTP_1_1;
  }

  @Override
  public Optional<Executor> executor() {
    return Optional.empty();
  }

  @Override
  public <T> HttpResponse<T> send(
      HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
    throw new IOException(MESSAGE);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
    return CompletableFuture.failedFuture(new IOException(MESSAGE));
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> responseBodyHandler,
      HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
    return CompletableFuture.failedFuture(new IOException(MESSAGE));
  }

  private static final class Builder implements HttpClient.Builder {
    @Override
    public Builder cookieHandler(CookieHandler cookieHandler) {
      return this;
    }

    @Override
    public Builder connectTimeout(Duration duration) {
      return this;
    }

    @Override
    public Builder sslContext(SSLContext sslContext) {
      return this;
    }

    @Override
    public Builder sslParameters(SSLParameters sslParameters) {
      return this;
    }

    @Override
    public Builder executor(Executor executor) {
      return this;
    }

    @Override
    public Builder followRedirects(Redirect policy) {
      return this;
    }

    @Override
    public Builder version(Version version) {
      return this;
    }

    @Override
    public Builder priority(int priority) {
      return this;
    }

    @Override
    public Builder proxy(ProxySelector selector) {
      return this;
    }

    @Override
    public Builder authenticator(Authenticator authenticator) {
      return this;
    }

    @Override
    public HttpClient build() {
      return new UnavailableHttpClient();
    }
  }
}
