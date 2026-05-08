package com.axin.kagent.session;

import com.axin.kagent.llm.LlmClient;
import com.axin.kagent.llm.Message;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户画像管理器，跨会话持久化用户信息（长期记忆）。
 *
 * <p>存储：主路径 Redis Hash (key=user:{id}:profile, 无 TTL)，兜底 MySQL (user_profile 表)。
 * 每轮对话结束后异步提取新信息，增量合并已有画像。
 */
@Component
public class UserProfileManager {

    private static final String KEY_PREFIX = "user:";
    private static final String KEY_SUFFIX = ":profile";

    private static final String EXTRACT_PROMPT = """
        从以下对话中提取关于用户的长期信息（可跨会话保留的事实）。
        只提取用户自身的信息，不要提取对话中讨论的第三方信息。

        已有画像：
        {existingProfile}

        本轮对话：
        用户问：{question}
        助手答：{answer}

        请以 JSON 格式输出需要更新或新增的字段（只输出有变化的字段）：
        {
          "role": "用户角色/职位",
          "techStack": "用户的技术栈",
          "preferences": "用户偏好/习惯",
          "currentProject": "当前在做项目",
          "extra": {"key": "value"}
        }
        只输出 JSON，不要任何解释。没有新信息时输出 {}""";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final LlmClient llmClient;
    private final UserProfileMapper profileMapper;

    public UserProfileManager(StringRedisTemplate redis, ObjectMapper objectMapper,
                              LlmClient llmClient, UserProfileMapper profileMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.llmClient = llmClient;
        this.profileMapper = profileMapper;
    }

    public UserProfile getProfile(String userId) {
        // Redis
        Map<Object, Object> hash = redis.opsForHash().entries(key(userId));
        if (!hash.isEmpty()) return buildFromRedisHash(userId, hash);

        // MySQL 兜底
        try {
            UserProfileRow row = profileMapper.findById(userId);
            if (row != null) {
                UserProfile profile = new UserProfile(userId);
                row.applyTo(profile);
                if (row.getExtra() != null && !row.getExtra().isBlank()) {
                    try {
                        profile.setExtra(objectMapper.readValue(row.getExtra(),
                            new TypeReference<LinkedHashMap<String, String>>() {}));
                    } catch (Exception ignored) {}
                }
                saveToRedis(userId, profile);
                System.out.println("📦 MySQL → Redis 恢复用户画像 " + userId);
                return profile;
            }
        } catch (Exception e) {
            System.out.println("⚠ MySQL 读取画像失败：" + e.getMessage());
        }

        return new UserProfile(userId);
    }

    public void extractAndUpdate(String userId, String question, String answer) {
        if (userId == null || question == null || answer == null) return;

        UserProfile existing = getProfile(userId);
        String existingText = existing.toPromptText();

        String json = llmClient.think(List.of(new Message("user", EXTRACT_PROMPT
            .replace("{existingProfile}", existingText.isBlank() ? "（无）" : existingText)
            .replace("{question}", question)
            .replace("{answer}", answer))));

        if (json == null || json.isBlank() || "{}".equals(json.strip())) return;

        try {
            String clean = json.strip();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").strip();
            }
            Map<String, Object> updates = objectMapper.readValue(clean,
                new TypeReference<LinkedHashMap<String, Object>>() {});
            mergeProfile(userId, existing, updates);
        } catch (Exception e) {
            System.out.println("⚠ 画像提取解析失败：" + e.getMessage());
        }
    }

    // --- storage ---

    private String key(String userId) { return KEY_PREFIX + userId + KEY_SUFFIX; }

    private void saveToRedis(String userId, UserProfile profile) {
        Map<String, String> hash = new LinkedHashMap<>();
        if (profile.getRole() != null) hash.put("role", profile.getRole());
        if (profile.getTechStack() != null) hash.put("techStack", profile.getTechStack());
        if (profile.getPreferences() != null) hash.put("preferences", profile.getPreferences());
        if (profile.getCurrentProject() != null) hash.put("currentProject", profile.getCurrentProject());
        if (!profile.getExtra().isEmpty()) {
            try { hash.put("extra", objectMapper.writeValueAsString(profile.getExtra())); } catch (Exception ignored) {}
        }
        if (profile.getUpdatedAt() != null) hash.put("updatedAt", profile.getUpdatedAt().toString());
        if (!hash.isEmpty()) redis.opsForHash().putAll(key(userId), hash);
    }

    private void saveProfile(String userId, UserProfile profile) {
        saveToRedis(userId, profile);

        try {
            String extraJson = null;
            if (!profile.getExtra().isEmpty()) {
                try { extraJson = objectMapper.writeValueAsString(profile.getExtra()); } catch (Exception ignored) {}
            }
            profileMapper.save(UserProfileRow.from(profile, extraJson));
        } catch (Exception e) {
            System.out.println("⚠ MySQL 画像写入失败：" + e.getMessage());
        }
    }

    // --- helpers unchanged ---

    private UserProfile buildFromRedisHash(String userId, Map<Object, Object> hash) {
        UserProfile profile = new UserProfile(userId);
        profile.setRole(str(hash.get("role")));
        profile.setTechStack(str(hash.get("techStack")));
        profile.setPreferences(str(hash.get("preferences")));
        profile.setCurrentProject(str(hash.get("currentProject")));
        String extraStr = str(hash.get("extra"));
        if (extraStr != null && !extraStr.isBlank()) {
            try {
                profile.setExtra(objectMapper.readValue(extraStr,
                    new TypeReference<LinkedHashMap<String, String>>() {}));
            } catch (Exception ignored) {}
        }
        String ua = str(hash.get("updatedAt"));
        if (ua != null) profile.setUpdatedAt(Instant.parse(ua));
        return profile;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }

    @SuppressWarnings("unchecked")
    private void mergeProfile(String userId, UserProfile profile, Map<String, Object> updates) {
        boolean changed = false;
        if (setIf(profile, updates, "role")) changed = true;
        if (setIf(profile, updates, "techStack")) changed = true;
        if (setIf(profile, updates, "preferences")) changed = true;
        if (setIf(profile, updates, "currentProject")) changed = true;
        if (updates.containsKey("extra") && updates.get("extra") instanceof Map) {
            profile.getExtra().putAll((Map<String, String>) updates.get("extra"));
            changed = true;
        }
        if (changed) { profile.setUpdatedAt(Instant.now()); saveProfile(userId, profile); }
    }

    private boolean setIf(UserProfile profile, Map<String, Object> updates, String key) {
        if (updates.containsKey(key) && updates.get(key) != null && !updates.get(key).toString().isBlank()) {
            switch (key) {
                case "role": profile.setRole(updates.get(key).toString()); break;
                case "techStack": profile.setTechStack(updates.get(key).toString()); break;
                case "preferences": profile.setPreferences(updates.get(key).toString()); break;
                case "currentProject": profile.setCurrentProject(updates.get(key).toString()); break;
            }
            return true;
        }
        return false;
    }

    private boolean notBlank(Object o) { return o != null && !o.toString().isBlank(); }
}
