package com.axin.kagent.session;

import java.time.Instant;

/**
 * user_profile 表的行映射，供 MyBatis 反序列化。
 */
public class UserProfileRow {

    private String userId;
    private String role;
    private String techStack;
    private String preferences;
    private String currentProject;
    private String extra;
    private Instant updatedAt;

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

    public String getExtra() { return extra; }
    public void setExtra(String extra) { this.extra = extra; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public static UserProfileRow from(UserProfile profile, String extraJson) {
        UserProfileRow row = new UserProfileRow();
        row.userId = profile.getUserId();
        row.role = profile.getRole();
        row.techStack = profile.getTechStack();
        row.preferences = profile.getPreferences();
        row.currentProject = profile.getCurrentProject();
        row.extra = extraJson;
        row.updatedAt = profile.getUpdatedAt();
        return row;
    }

    public void applyTo(UserProfile profile) {
        profile.setRole(role);
        profile.setTechStack(techStack);
        profile.setPreferences(preferences);
        profile.setCurrentProject(currentProject);
        if (updatedAt != null) profile.setUpdatedAt(updatedAt);
    }
}
