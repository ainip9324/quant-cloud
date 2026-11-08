package com.quant.data.market;

import com.quant.common.model.Kline;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BitgetRestClient {
    private final OkHttpClient client;
    private final String baseUrl;
    private final BitgetCandleResponseParser parser = new BitgetCandleResponseParser();

    public BitgetRestClient(
            @Value("${bitget.base-url:https://api.bitget.com}") String baseUrl,
            @Value("${bitget.proxy.enabled:false}") boolean proxyEnabled,
            @Value("${bitget.proxy.host:127.0.0.1}") String proxyHost,
            @Value("${bitget.proxy.port:7897}") int proxyPort) {
        this.baseUrl = baseUrl;
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .protocols(List.of(Protocol.HTTP_1_1))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS);
        if (proxyEnabled) {
            builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
        }
        this.client = builder.build();
    }

    public List<Kline> fetch(String path, Map<String, String> params) {
        return execute(path, params, null);
    }

    List<Kline> fetchBefore(String path, Map<String, String> params, long deadlineNanos) {
        return execute(path, params, deadlineNanos);
    }

    private List<Kline> execute(String path, Map<String, String> params, Long deadlineNanos) {
        HttpUrl.Builder url = Objects.requireNonNull(HttpUrl.parse(baseUrl + path), "invalid Bitget base URL").newBuilder();
        params.forEach(url::addQueryParameter);
        var call = client.newCall(new Request.Builder().url(url.build()).get().build());
        if (deadlineNanos != null) {
            if (deadlineNanos <= System.nanoTime()) throw new IllegalStateException("Bitget history request budget exhausted");
            call.timeout().deadlineNanoTime(deadlineNanos);
        }
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) throw new IllegalStateException("Bitget HTTP request failed");
            return parser.parse(Objects.requireNonNull(response.body()).string());
        } catch (Exception e) {
            if (e instanceof IllegalStateException failure) throw failure;
            throw new IllegalStateException("Bitget market request failed", e);
        }
    }

}
