package com.axin.kagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "serp-api")
public class SerpApiProperties {
    private String apiKey;
    private String baseUrl = "https://serpapi.com/search";
}
