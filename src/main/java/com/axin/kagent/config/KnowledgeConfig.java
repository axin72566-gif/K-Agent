package com.axin.kagent.config;

import io.qdrant.client.QdrantClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提供独立的知识库 QdrantVectorStore Bean（collection = knowledge_base）。
 */
@Configuration
public class KnowledgeConfig {

    @Bean("knowledgeVectorStore")
    public QdrantVectorStore knowledgeVectorStore(
            QdrantClient qdrantClient,
            @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel) {
        return QdrantVectorStore.builder(qdrantClient, embeddingModel)
            .collectionName("knowledge_base")
            .initializeSchema(true)
            .build();
    }
}
