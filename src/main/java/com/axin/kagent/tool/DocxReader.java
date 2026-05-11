package com.axin.kagent.tool;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * DOCX 读取器：用 Apache POI 解析，Heading → heading Block，正文 → paragraph Block。
 */
public class DocxReader implements DocumentReader {

    private final String title;
    private final Path file;

    public DocxReader(Path file) {
        this.title = file.getFileName().toString();
        this.file = file;
    }

    @Override
    public String getTitle() { return title; }

    @Override
    public List<Block> parse() {
        List<Block> blocks = new ArrayList<>();
        List<String> stack = new ArrayList<>();

        try (InputStream in = Files.newInputStream(file);
             XWPFDocument doc = new XWPFDocument(in)) {

            int lineNum = 1;
            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText();
                if (text.isBlank()) { lineNum++; continue; }

                String style = para.getStyle();
                if (style != null && style.matches("Heading\\d+")) {
                    int level = Integer.parseInt(style.replace("Heading", ""));
                    updateStack(stack, text.strip(), level);
                    blocks.add(new Block("heading", text.strip(), level,
                        String.join(" > ", stack), lineNum, lineNum));
                } else {
                    String path = stack.isEmpty() ? "" : String.join(" > ", stack);
                    blocks.add(new Block("paragraph", text.strip(), 0, path, lineNum, lineNum));
                }
                lineNum++;
            }
        } catch (IOException e) {
            System.out.println("⚠ DOCX 解析失败：" + e.getMessage());
        }
        return blocks;
    }

    private void updateStack(List<String> stack, String heading, int level) {
        while (stack.size() >= level) stack.remove(stack.size() - 1);
        stack.add(heading);
    }
}
