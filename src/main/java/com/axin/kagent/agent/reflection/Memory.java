package com.axin.kagent.agent.reflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Memory {

    private final List<Map<String, String>> records;

    public Memory() {
        this.records = new ArrayList<>();
    }

    public void addRecord(String recordType, String content) {
        Map<String, String> record = new LinkedHashMap<>();
        record.put("type", recordType);
        record.put("content", content);
        records.add(record);
        System.out.println("📝 记忆已更新，添加了一条'" + recordType + "'记录。");
    }

    public String getTrajectory() {
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> record : records) {
            String type = record.get("type");
            String content = record.get("content");
            if ("execution".equals(type)) {
                sb.append("--- 上次尝试（代码） ---\n").append(content);
            } else if ("reflection".equals(type)) {
                sb.append("--- 审查者反馈 ---\n").append(content);
            }
            sb.append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    public String getLastExecution() {
        for (int i = records.size() - 1; i >= 0; i--) {
            Map<String, String> record = records.get(i);
            if ("execution".equals(record.get("type"))) {
                return record.get("content");
            }
        }
        return null;
    }
}
