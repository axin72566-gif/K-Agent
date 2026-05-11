package com.axin.kagent.tool;

import java.util.List;

/**
 * 文档读取器统一接口。各格式实现产出统一的 Block 列表。
 */
public interface DocumentReader {

    /** 结构化块。 */
    record Block(
        String type,        // "heading" | "paragraph" | "code" | "list"
        String content,     // 纯文本内容
        int level,          // 标题层级 (h1=1...)，非标题=0
        String sectionPath, // 当前所在章节路径
        int lineStart,
        int lineEnd
    ) {}

    String getTitle();
    List<Block> parse();
}
