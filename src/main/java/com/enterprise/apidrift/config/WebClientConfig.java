package com.enterprise.apidrift.config;

import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final EgressProxyProperties egressProps;

    @Bean
    public WebClient egressWebClient() {
        HttpClient httpClient = HttpClient.create()
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(
                                egressProps.getReadTimeoutSeconds(), TimeUnit.SECONDS)))
                .responseTimeout(Duration.ofSeconds(egressProps.getReadTimeoutSeconds()))
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        egressProps.getConnectTimeoutSeconds() * 1000);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize((int) egressProps.getMaxPayloadSizeBytes()))
                .build();
    }
}
