package io.pilots.processservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pilots.processservice.delegate.EdcNotifyDelegate;
import io.pilots.processservice.delegate.HttpPostDelegate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        return builder.build();
    }

    @Bean
    public HttpPostDelegate httpPostDelegate(RestClient restClient) {
        return new HttpPostDelegate(restClient);
    }

    @Bean
    public EdcNotifyDelegate edcNotifyDelegate(RestClient restClient, ObjectMapper objectMapper) {
        return new EdcNotifyDelegate(restClient, objectMapper);
    }
}
