package tech.maicraft.ampautocert;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Blocking transport intended only for the amp-autocert background worker. */
public final class JdkHttpTransport implements HttpTransport {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final HttpClient client;
    private final Duration requestTimeout;

    public JdkHttpTransport(Duration connectTimeout, Duration requestTimeout) {
        this(HttpClient.newBuilder().connectTimeout(connectTimeout).build(), requestTimeout);
    }

    JdkHttpTransport(HttpClient client, Duration requestTimeout) {
        this.client = client;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public Response send(String method, java.net.URI uri, Map<String, String> headers, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(requestTimeout);
        headers.forEach(request::header);
        request.method(method, body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        var responseFuture = client.sendAsync(request.build(),
                ignored -> new LimitedBodySubscriber(MAX_RESPONSE_BYTES));
        HttpResponse<byte[]> response;
        try {
            response = responseFuture.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            responseFuture.cancel(true);
            throw new HttpTimeoutException("HTTP request exceeded its deadline");
        } catch (InterruptedException interrupted) {
            responseFuture.cancel(true);
            throw interrupted;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("HTTP request failed", cause);
        }
        return new Response(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean done;

        private LimitedBodySubscriber(int limit) { this.limit = limit; }

        @Override public CompletionStage<byte[]> getBody() { return body; }

        @Override public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override public void onNext(List<ByteBuffer> chunks) {
            if (done) return;
            try {
                for (ByteBuffer chunk : chunks) {
                    if (chunk.remaining() > limit - output.size()) {
                        done = true;
                        subscription.cancel();
                        body.completeExceptionally(new IOException("HTTP response exceeded safe size limit"));
                        return;
                    }
                    byte[] bytes = new byte[chunk.remaining()];
                    chunk.get(bytes);
                    output.writeBytes(bytes);
                }
                subscription.request(1);
            } catch (RuntimeException failure) {
                done = true;
                subscription.cancel();
                body.completeExceptionally(failure);
            }
        }

        @Override public void onError(Throwable throwable) {
            if (!done) {
                done = true;
                body.completeExceptionally(throwable);
            }
        }

        @Override public void onComplete() {
            if (!done) {
                done = true;
                body.complete(output.toByteArray());
            }
        }
    }
}
