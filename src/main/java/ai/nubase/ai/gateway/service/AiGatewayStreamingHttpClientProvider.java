package ai.nubase.ai.gateway.service;

import ai.nubase.ai.gateway.config.AiGatewayStreamingProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class AiGatewayStreamingHttpClientProvider {

    private final Dispatcher dispatcher;
    private final OkHttpClient baseClient;
    private final ConcurrentMap<ClientProfile, OkHttpClient> clients = new ConcurrentHashMap<>();

    public AiGatewayStreamingHttpClientProvider(AiGatewayStreamingProperties properties) {
        AiGatewayStreamingProperties.DispatcherLimits limits =
                Objects.requireNonNull(properties.getDispatcher(), "streaming dispatcher configuration is required");
        if (!limits.isCapacityValid()) {
            throw new IllegalArgumentException(
                    "streaming maxRequests must be greater than or equal to maxRequestsPerHost");
        }

        dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(limits.getMaxRequests());
        dispatcher.setMaxRequestsPerHost(limits.getMaxRequestsPerHost());

        baseClient = new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .addInterceptor(new StreamingTimingInterceptor())
                .build();

        log.info("AI gateway streaming dispatcher initialized: maxRequests={}, maxRequestsPerHost={}",
                dispatcher.getMaxRequests(), dispatcher.getMaxRequestsPerHost());
    }

    public EventSource newEventSource(
            Request request,
            EventSourceListener listener,
            int connectTimeoutMs,
            int readTimeoutMs,
            int writeTimeoutMs,
            String requestId,
            String protocol,
            String upstream) {
        validateTimeout("connectTimeoutMs", connectTimeoutMs, false);
        validateTimeout("readTimeoutMs", readTimeoutMs, true);
        validateTimeout("writeTimeoutMs", writeTimeoutMs, false);

        ClientProfile profile = new ClientProfile(connectTimeoutMs, readTimeoutMs, writeTimeoutMs);
        OkHttpClient client = clients.computeIfAbsent(profile, this::createClient);
        StreamingCallMetadata metadata = new StreamingCallMetadata(
                requestId,
                protocol,
                upstream,
                authority(request.url()),
                System.nanoTime());
        Request trackedRequest = request.newBuilder()
                .tag(StreamingCallMetadata.class, metadata)
                .build();

        log.info("AI gateway SSE submitted: requestId={}, protocol={}, upstream={}, authority={}, "
                        + "runningCalls={}, queuedCalls={}, maxRequests={}, maxRequestsPerHost={}",
                requestId, protocol, upstream, metadata.authority(),
                dispatcher.runningCallsCount(), dispatcher.queuedCallsCount(),
                dispatcher.getMaxRequests(), dispatcher.getMaxRequestsPerHost());

        return EventSources.createFactory(client).newEventSource(trackedRequest, listener);
    }

    Dispatcher dispatcher() {
        return dispatcher;
    }

    private OkHttpClient createClient(ClientProfile profile) {
        return baseClient.newBuilder()
                .connectTimeout(profile.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(profile.readTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(profile.writeTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
    }

    private void validateTimeout(String name, int timeoutMs, boolean allowZero) {
        if (timeoutMs < 0 || (!allowZero && timeoutMs == 0)) {
            throw new IllegalArgumentException(name + " must be " + (allowZero ? "non-negative" : "positive"));
        }
    }

    private String authority(HttpUrl url) {
        return url.scheme() + "://" + url.host() + ":" + url.port();
    }

    private record ClientProfile(int connectTimeoutMs, int readTimeoutMs, int writeTimeoutMs) {
    }

    private record StreamingCallMetadata(
            String requestId,
            String protocol,
            String upstream,
            String authority,
            long submittedAtNanos) {
    }

    private final class StreamingTimingInterceptor implements Interceptor {

        @Override
        public Response intercept(Chain chain) throws IOException {
            StreamingCallMetadata metadata = chain.request().tag(StreamingCallMetadata.class);
            if (metadata == null) {
                return chain.proceed(chain.request());
            }
            log.info("AI gateway SSE network started: requestId={}, protocol={}, upstream={}, authority={}, "
                            + "queueWaitMs={}, runningCalls={}, queuedCalls={}",
                    metadata.requestId(), metadata.protocol(), metadata.upstream(), metadata.authority(),
                    elapsedMillis(metadata.submittedAtNanos()),
                    dispatcher.runningCallsCount(), dispatcher.queuedCallsCount());

            try {
                Response response = chain.proceed(chain.request());
                log.info("AI gateway SSE response headers received: requestId={}, protocol={}, upstream={}, "
                                + "authority={}, status={}, firstHeaderMs={}",
                        metadata.requestId(), metadata.protocol(), metadata.upstream(), metadata.authority(),
                        response.code(), elapsedMillis(metadata.submittedAtNanos()));
                return response;
            } catch (IOException exception) {
                log.warn("AI gateway SSE call failed: requestId={}, protocol={}, upstream={}, authority={}, "
                                + "durationMs={}, runningCalls={}, queuedCalls={}, errorType={}",
                        metadata.requestId(), metadata.protocol(), metadata.upstream(), metadata.authority(),
                        elapsedMillis(metadata.submittedAtNanos()),
                        dispatcher.runningCallsCount(), dispatcher.queuedCallsCount(),
                        exception.getClass().getSimpleName());
                throw exception;
            }
        }

        private long elapsedMillis(long startNanos) {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        }
    }
}
