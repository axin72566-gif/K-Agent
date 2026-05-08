package com.axin.kagent.tool;

import com.axin.kagent.config.SerpApiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

@Component
public class SearchTool implements Tool {

    private final SerpApiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SearchTool(SerpApiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public String getName() {
        return "Search";
    }

    @Override
    public String getDescription() {
        return "一个网络搜索引擎。当你需要回答关于当前事件、事实以及知识库中找不到的信息时，请使用此工具。";
    }

    @Override
    public String execute(String query) {
        System.out.println("🔍 正在执行 [SerpApi] 网络搜索：" + query);
        try {
            String apiKey = properties.getApiKey();
            if (apiKey == null || apiKey.isBlank() || "your-serpapi-key".equals(apiKey)) {
                return "错误：SERPAPI_API_KEY 未在 application.yml 中配置。";
            }

            String url = String.format(
                "%s?engine=google&q=%s&api_key=%s&gl=cn&hl=zh-cn",
                properties.getBaseUrl(),
                URLEncoder.encode(query, StandardCharsets.UTF_8),
                apiKey
            );

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

            JsonNode results = objectMapper.readTree(response.body());

            if (results.has("answer_box_list")) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : results.get("answer_box_list")) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(item.asText());
                }
                return sb.toString();
            }
            if (results.has("answer_box") && results.get("answer_box").has("answer")) {
                return results.get("answer_box").get("answer").asText();
            }
            if (results.has("knowledge_graph") && results.get("knowledge_graph").has("description")) {
                return results.get("knowledge_graph").get("description").asText();
            }
            if (results.has("organic_results") && !results.get("organic_results").isEmpty()) {
                StringBuilder sb = new StringBuilder();
                int count = 0;
                for (JsonNode res : results.get("organic_results")) {
                    if (count >= 3) break;
                    String title = res.has("title") ? res.get("title").asText() : "";
                    String snippet = res.has("snippet") ? res.get("snippet").asText() : "";
                    sb.append("[").append(count + 1).append("] ").append(title).append("\n");
                    sb.append(snippet).append("\n\n");
                    count++;
                }
                return sb.toString().trim();
            }

            return "抱歉，未找到关于 '" + query + "' 的信息。";

        } catch (Exception e) {
            return "搜索时发生错误：" + e.getMessage();
        }
    }
}
