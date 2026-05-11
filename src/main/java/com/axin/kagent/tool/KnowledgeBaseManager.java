package com.axin.kagent.tool;

import com.axin.kagent.llm.LlmClient;
import com.axin.kagent.llm.Message;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库管理器：文档导入、检索、重排序。
 */
@Component
public class KnowledgeBaseManager implements ApplicationRunner {

    private static final int CHUNK_SIZE = 300;
    private static final Path KNOWLEDGE_DIR = Path.of("knowledge");
    private static final int RECALL_TOP_K = 10;
    private static final int FINAL_TOP_K = 3;

    /** LLM 重排序 Prompt */
    private static final String RERANK_PROMPT = """
        查询：「{query}」

        对以下 {count} 个文档片段与查询的相关性打分，选出最相关的 {topK} 条。
        优先选择直接回答查询的片段，其次选择提供背景知识的片段。
        不相关的不要选。如果不足 {topK} 条相关，只输出相关的。

        {candidates}

        请输出最相关片段的编号，JSON 数组格式，如: [1, 5, 3]
        只输出 JSON 数组，不要任何解释。""";

    /** Query 改写 Prompt */
    private static final String QUERY_REWRITE_PROMPT = """
        结合对话历史，将以下问题改写为适合知识库检索的查询文本。
        要求：还原指代（如"它""那个"）、使用技术术语、补充隐含上下文。
        不要回答问题，只输出改写后的查询文本，不超过 80 字。

        对话历史：
        {context}

        问题：{rawQuery}

        改写后的查询：""";

    private final VectorStore knowledgeStore;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseManager(
            @Qualifier("knowledgeVectorStore") VectorStore knowledgeStore,
            LlmClient llmClient,
            ObjectMapper objectMapper) {
        this.knowledgeStore = knowledgeStore;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    // --- 启动导入 ---

    @Override
    public void run(ApplicationArguments args) {
        if (Files.exists(KNOWLEDGE_DIR)) {
            try { ingest(KNOWLEDGE_DIR); }
            catch (IOException e) { System.out.println("⚠ 知识库导入失败：" + e.getMessage()); }
        } else {
            System.out.println("ℹ 知识库目录 knowledge/ 不存在，跳过导入。");
        }
    }

    // --- 检索 ---

    /**
     * 检索 + LLM 重排序（直接使用原始 query，不做改写）。
     */
    public List<Document> search(String query) {
        return search(query, FINAL_TOP_K);
    }

    /**
     * 检索 + Query 改写 + LLM 重排序。
     * 当有对话上下文时，先改写 query 补全指代和术语，再检索。
     *
     * @param rawQuery 用户原始问题
     * @param context  对话历史文本（可为 null 或空，此时不做改写）
     */
    public List<Document> search(String rawQuery, String context) {
        String query = (context != null && !context.isBlank())
            ? rewriteQuery(rawQuery, context)
            : rawQuery;
        return search(query, FINAL_TOP_K);
    }

    /**
     * 检索 + LLM 重排序。
     * 先向量检索 top-{@value #RECALL_TOP_K}，再用 LLM 打分选出 top-{@code finalTopK}。
     */
    public List<Document> search(String query, int finalTopK) {
        try {
            List<Document> candidates = knowledgeStore.similaritySearch(
                SearchRequest.builder().query(query).topK(RECALL_TOP_K).build());

            if (candidates.isEmpty()) return List.of();
            if (candidates.size() <= finalTopK) return candidates;

            return rerank(query, candidates, finalTopK);
        } catch (Exception e) {
            System.out.println("⚠ 知识库检索失败：" + e.getMessage());
            return List.of();
        }
    }

    /**
     * 将文档列表格式化为 Prompt 文本。空列表返回空字符串。
     */
    public String formatForPrompt(List<Document> docs) {
        if (docs == null || docs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("你已知的参考知识：\n");
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            String title = doc.getMetadata() != null
                ? (String) doc.getMetadata().getOrDefault("title", "参考")
                : "参考";
            String section = doc.getMetadata() != null
                ? (String) doc.getMetadata().getOrDefault("section", "")
                : "";
            sb.append("[").append(title);
            if (!section.isBlank()) sb.append(" > ").append(section);
            sb.append("] ").append(doc.getText());
            if (i < docs.size() - 1) sb.append("\n");
        }
        return sb.toString();
    }

    // --- 重排序 ---

    private List<Document> rerank(String query, List<Document> candidates, int topK) {
        String candidatesText = formatCandidates(candidates);
        String prompt = RERANK_PROMPT
            .replace("{query}", query)
            .replace("{count}", String.valueOf(candidates.size()))
            .replace("{topK}", String.valueOf(topK))
            .replace("{candidates}", candidatesText);

        String json = llmClient.think(List.of(new Message("user", prompt)));
        if (json == null || json.isBlank()) return candidates.subList(0, topK);

        try {
            String clean = json.strip();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").strip();
            }
            List<Integer> indices = objectMapper.readValue(clean,
                new TypeReference<List<Integer>>() {});

            List<Document> result = new ArrayList<>();
            for (int idx : indices) {
                if (idx >= 1 && idx <= candidates.size()) {
                    result.add(candidates.get(idx - 1));
                }
                if (result.size() >= topK) break;
            }
            if (!result.isEmpty()) return result;
        } catch (Exception e) {
            System.out.println("⚠ 重排序解析失败，使用原始 top-" + topK);
        }
        return candidates.subList(0, topK);
    }

    private String formatCandidates(List<Document> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            Document doc = candidates.get(i);
            String title = doc.getMetadata() != null
                ? (String) doc.getMetadata().getOrDefault("title", "参考")
                : "参考";
            sb.append("[").append(i + 1).append("] ").append(title).append("\n");
            sb.append(doc.getText()).append("\n\n");
        }
        return sb.toString();
    }

    // --- Query 改写 ---

    private String rewriteQuery(String rawQuery, String context) {
        String prompt = QUERY_REWRITE_PROMPT
            .replace("{context}", context)
            .replace("{rawQuery}", rawQuery);

        String rewritten = llmClient.think(List.of(new Message("user", prompt)));
        if (rewritten == null || rewritten.isBlank()) return rawQuery;

        String result = rewritten.strip();
        System.out.println("📝 Query 改写: \"" + rawQuery + "\" → \"" + result + "\"");
        return result;
    }

    // --- 文档导入（不变） ---

    public int ingest(Path dir) throws IOException {
        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream.filter(this::isSupported).toList();
        }
        int total = 0;
        for (Path file : files) {
            DocumentReader reader = readerFor(file);
            if (reader != null) total += ingest(reader);
        }
        System.out.println("📚 知识库导入完成，共 " + total + " 个片段");
        return total;
    }

    private int ingest(DocumentReader reader) {
        List<DocumentReader.Block> blocks = reader.parse();
        String title = reader.getTitle();
        List<Document> documents = new ArrayList<>();
        int chunkIdx = 0;
        for (DocumentReader.Block block : blocks) {
            for (String chunk : splitBlock(block)) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("title", title);
                meta.put("type", block.type());
                meta.put("chunkIndex", String.valueOf(chunkIdx));
                if (!block.sectionPath().isBlank()) meta.put("section", block.sectionPath());
                documents.add(new Document(deterministicId(title, chunkIdx), chunk, meta));
                chunkIdx++;
            }
        }
        if (!documents.isEmpty()) {
            knowledgeStore.add(documents);
            System.out.println("  ✅ " + title + " → " + documents.size() + " 个片段");
        }
        return documents.size();
    }

    private boolean isSupported(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".md") || n.endsWith(".txt") || n.endsWith(".docx")
            || n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".pdf");
    }

    private DocumentReader readerFor(Path file) {
        String n = file.getFileName().toString().toLowerCase();
        try {
            if (n.endsWith(".md")) return new MarkdownReader(file);
            if (n.endsWith(".txt")) return new PlainTextReader(file);
            if (n.endsWith(".docx")) return new DocxReader(file);
            if (n.endsWith(".html") || n.endsWith(".htm")) return new HtmlReader(file);
            if (n.endsWith(".pdf")) return new PdfReader(file);
        } catch (IOException e) { System.out.println("⚠ 无法读取 " + n); }
        return null;
    }

    List<String> splitBlock(DocumentReader.Block block) {
        List<String> result = new ArrayList<>();
        String text = block.content().replace("\r\n", "\n").replace("\r", "\n");
        if ("heading".equals(block.type())) { result.add(text); return result; }
        if ("code".equals(block.type())) {
            result.add(text.length() > CHUNK_SIZE * 2
                ? text.substring(0, CHUNK_SIZE * 2) + "\n...(已截断)" : text);
            return result;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            if (end < text.length()) {
                int nl = text.lastIndexOf('\n', end);
                if (nl > start + CHUNK_SIZE / 2) end = nl;
            }
            result.add(text.substring(start, end).strip());
            if (end >= text.length()) break;
            start = end - 20;
        }
        return result;
    }

    private String deterministicId(String title, int idx) {
        return UUID.nameUUIDFromBytes((title + "/" + idx).getBytes()).toString();
    }
}
