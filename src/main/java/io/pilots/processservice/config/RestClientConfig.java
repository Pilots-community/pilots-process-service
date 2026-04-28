package io.pilots.processservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * General-purpose RestClient for outbound HTTP calls made by Flowable delegates.
     * Spring Boot auto-configures a RestClient.Builder with sensible defaults
     * (message converters, etc.) which we accept as-is.
     */
    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        return builder.build();
    }
}
