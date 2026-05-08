package com.axin.kagent.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户画像（长期记忆），跨会话持久化。
 *
 * <p>核心字段固定以确保一致性，extra map 兜底灵活维度。
 * 存储在 Redis Hash {@code user:{userId}:profile}，无 TTL。
 */
public class UserProfile {

    private String userId;
    private String role;
    private String techStack;
    private String preferences;
    private String currentProject;
    private Map<String, String> extra = new LinkedHashMap<>();
    private Instant updatedAt;

    public UserProfile() {
    }

    @JsonCreator
    public UserProfile(@JsonProperty("userId") String userId) {
        this.userId = userId;
        this.updatedAt = Instant.now();
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getTechStack() { return techStack; }
    public void setTechStack(String techStack) { this.techStack = techStack; }

    public String getPreferences() { return preferences; }
    public void setPreferences(String preferences) { this.preferences = preferences; }

    public String getCurrentProject() { return currentProject; }
    public void setCurrentProject(String currentProject) { this.currentProject = currentProject; }

    public Map<String, String> getExtra() { return extra; }
    public void setExtra(Map<String, String> extra) { this.extra = extra != null ? extra : new LinkedHashMap<>(); }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /**
     * 格式化为 Prompt 可用的纯文本。空画像返回空字符串。
     */
    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        if (role != null && !role.isBlank()) sb.append("角色：").append(role).append("；");
        if (techStack != null && !techStack.isBlank()) sb.append("技术栈：").append(techStack).append("；");
        if (preferences != null && !preferences.isBlank()) sb.append("偏好：").append(preferences).append("；");
        if (currentProject != null && !currentProject.isBlank()) sb.append("当前项目：").append(currentProject).append("；");
        for (var e : extra.entrySet()) {
            sb.append(e.getKey()).append("：").append(e.getValue()).append("；");
        }
        if (sb.isEmpty()) return "";
        return "用户画像：" + sb;
    }
}
