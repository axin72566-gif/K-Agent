package com.axin.kagent.tool;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 读取器：用 PDFBox 提取文本，启发式标题检测。
 *
 * <p>标题检测规则：独立成行 + 短于 60 字符 + 不以句号结尾 + 前面有/没有空行。
 */
public class PdfReader implements DocumentReader {

    private static final int TITLE_MAX_LENGTH = 60;

    private final String title;
    private final Path file;

    public PdfReader(Path file) {
        this.title = file.getFileName().toString();
        this.file = file;
    }

    @Override
    public String getTitle() { return title; }

    @Override
    public List<Block> parse() {
        List<Block> blocks = new ArrayList<>();
        List<String> stack = new ArrayList<>();

        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);

            String[] lines = text.split("\n");
            List<String> paraBuf = new ArrayList<>();
            boolean prevLineEmpty = true;

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].strip();
                if (line.isEmpty()) {
                    flushPara(blocks, paraBuf, stack);
                    prevLineEmpty = true;
                    continue;
                }

                // 启发式标题检测
                if (isLikelyTitle(line, prevLineEmpty, i == 0)) {
                    flushPara(blocks, paraBuf, stack);
                    // 猜层级：前面空行多+独立性强 → 级别高
                    int level = prevLineEmpty ? 2 : 3;
                    updateStack(stack, line, level);
                    blocks.add(new Block("heading", line, level,
                        String.join(" > ", stack), i + 1, i + 1));
                    prevLineEmpty = false;
                    continue;
                }

                paraBuf.add(line);
                prevLineEmpty = false;
            }
            flushPara(blocks, paraBuf, stack);
        } catch (IOException e) {
            System.out.println("⚠ PDF 解析失败：" + e.getMessage());
        }
        return blocks;
    }

    private boolean isLikelyTitle(String line, boolean prevEmpty, boolean isFirst) {
        if (line.length() > TITLE_MAX_LENGTH) return false;
        if (line.endsWith("。") || line.endsWith(".")) return false;
        // 首行、或前面有空行、或纯中文短语
        return isFirst || prevEmpty || line.matches("^[\\u4e00-\\u9fa5\\d\\s]+$");
    }

    private void flushPara(List<Block> blocks, List<String> buf, List<String> stack) {
        if (buf.isEmpty()) return;
        String content = String.join("\n", buf);
        String path = stack.isEmpty() ? "" : String.join(" > ", stack);
        blocks.add(new Block("paragraph", content, 0, path, 0, 0));
        buf.clear();
    }

    private void updateStack(List<String> stack, String heading, int level) {
        while (stack.size() >= level) stack.remove(stack.size() - 1);
        stack.add(heading);
    }
}
