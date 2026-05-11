package com.axin.kagent.tool;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * HTML 读取器：用 JSoup 按标签解析，h1-h6 → heading，pre → code，其余 → paragraph。
 */
public class HtmlReader implements DocumentReader {

    private final String title;
    private final Path file;

    public HtmlReader(Path file) {
        this.title = file.getFileName().toString();
        this.file = file;
    }

    @Override
    public String getTitle() { return title; }

    @Override
    public List<Block> parse() {
        List<Block> blocks = new ArrayList<>();
        List<String> stack = new ArrayList<>();
        int lineNum = 1;

        try {
            Document doc = Jsoup.parse(Files.readString(file));
            // 移除 script/style/header/footer/nav
            doc.select("script,style,header,footer,nav").remove();

            for (Element el : doc.body().select("h1,h2,h3,h4,h5,h6,p,pre,li,blockquote")) {
                String tag = el.tagName();
                String text = el.wholeText().strip();
                if (text.isBlank()) continue;

                if (tag.matches("h[1-6]")) {
                    int level = Integer.parseInt(tag.substring(1));
                    updateStack(stack, text, level);
                    blocks.add(new Block("heading", text, level,
                        String.join(" > ", stack), lineNum, lineNum));
                } else if ("pre".equals(tag)) {
                    blocks.add(new Block("code", text, 0, "", lineNum, lineNum));
                } else if ("blockquote".equals(tag)) {
                    String path = stack.isEmpty() ? "" : String.join(" > ", stack);
                    blocks.add(new Block("paragraph", text, 0, path, lineNum, lineNum));
                } else if ("li".equals(tag)) {
                    String path = stack.isEmpty() ? "" : String.join(" > ", stack);
                    blocks.add(new Block("list", text, 0, path, lineNum, lineNum));
                } else {
                    String path = stack.isEmpty() ? "" : String.join(" > ", stack);
                    blocks.add(new Block("paragraph", text, 0, path, lineNum, lineNum));
                }
                lineNum++;
            }
        } catch (IOException e) {
            System.out.println("⚠ HTML 解析失败：" + e.getMessage());
        }
        return blocks;
    }

    private void updateStack(List<String> stack, String heading, int level) {
        while (stack.size() >= level) stack.remove(stack.size() - 1);
        stack.add(heading);
    }
}
