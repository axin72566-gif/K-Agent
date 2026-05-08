package com.axin.kagent.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ToolExecutor {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolExecutor(List<Tool> toolList) {
        for (Tool tool : toolList) {
            registerTool(tool);
        }
    }

    public void registerTool(Tool tool) {
        if (tools.containsKey(tool.getName())) {
            System.out.println("警告：工具 '" + tool.getName() + "' 已存在，将被覆盖。");
        }
        tools.put(tool.getName(), tool);
        System.out.println("工具 '" + tool.getName() + "' 已注册。");
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }

    public String getAvailableTools() {
        return tools.values().stream()
            .map(t -> "- " + t.getName() + ": " + t.getDescription())
            .collect(Collectors.joining("\n"));
    }
}
