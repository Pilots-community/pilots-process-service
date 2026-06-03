package io.pilots.processservice.config;

import io.pilots.processservice.delegate.InvokeInternalApiDelegate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        return builder.build();
    }

    /** Default delegate — reads {@code internalApiUrl} + {@code payloadData}. */
    @Bean
    public InvokeInternalApiDelegate invokeInternalApiDelegate(RestClient restClient) {
        return new InvokeInternalApiDelegate(restClient);
    }

    /** Trucker-announcement delegate — reads {@code truckerApiUrl} + {@code truckerPayload}. */
    @Bean
    public InvokeInternalApiDelegate invokeTruckerApiDelegate(RestClient restClient) {
        return new InvokeInternalApiDelegate(restClient, "truckerApiUrl", "truckerPayload");
    }

    /** Measurement delegate — reads {@code measurementApiUrl} + {@code measurementPayload}. */
    @Bean
    public InvokeInternalApiDelegate invokeMeasurementApiDelegate(RestClient restClient) {
        return new InvokeInternalApiDelegate(restClient, "measurementApiUrl", "measurementPayload");
    }
}
