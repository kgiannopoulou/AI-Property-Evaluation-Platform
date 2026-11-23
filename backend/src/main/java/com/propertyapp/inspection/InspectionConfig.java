package com.propertyapp.inspection;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(InspectionProperties.class)
class InspectionConfig {

	@Bean
	AiInspectionClient aiInspectionClient(InspectionProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(properties.aiTimeout());

		RestClient.Builder builder = RestClient.builder().baseUrl(properties.aiServiceUrl()).requestFactory(factory);
		if (properties.aiServiceToken() != null && !properties.aiServiceToken().isBlank()) {
			builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.aiServiceToken());
		}
		return new AiInspectionClient(builder.build());
	}
}
