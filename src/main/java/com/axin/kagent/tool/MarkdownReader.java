package com.axin.kagent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 结构化读取器。
 */
public class MarkdownReader implements DocumentReader {

    private enum State { NORMAL, CODE, LIST }

    private final String title;
    private final List<String> lines;
    private final List<Block> blocks = new ArrayList<>();
    private final List<String> sectionStack = new ArrayList<>();
    private State state = State.NORMAL;
    private final StringBuilder buf = new StringBuilder();
    private int blockStart = 1;

    public MarkdownReader(Path file) throws IOException {
        this.title = file.getFileName().toString();
        this.lines = Files.readAllLines(file);
    }

    @Override
    public String getTitle() { return title; }

    @Override
    public List<Block> parse() {
        blocks.clear();
        sectionStack.clear();
        state = State.NORMAL;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNum = i + 1;
            switch (state) {
                case CODE  -> parseCodeLine(line, lineNum);
                case LIST  -> parseListLine(line, lineNum);
                default    -> parseNormalLine(line, lineNum);
            }
        }
        flushBlock(lines.size());
        return List.copyOf(blocks);
    }

    static String headingText(String line) {
        String trimmed = line.strip();
        if (!trimmed.startsWith("#")) return null;
        int i = 0;
        while (i < trimmed.length() && trimmed.charAt(i) == '#') i++;
        if (i == 0 || i > 6) return null;
        if (i < trimmed.length() && trimmed.charAt(i) != ' ') return null;
        return trimmed.substring(i).strip();
    }

    static int headingLevel(String line) {
        String trimmed = line.strip();
        if (!trimmed.startsWith("#")) return 0;
        int i = 0;
        while (i < trimmed.length() && trimmed.charAt(i) == '#') i++;
        if (i > 6) return 0;
        if (i < trimmed.length() && trimmed.charAt(i) != ' ') return 0;
        return i;
    }

    private void parseNormalLine(String line, int lineNum) {
        if (line.strip().startsWith("```")) {
            flushBlock(lineNum - 1);
            state = State.CODE;
            blockStart = lineNum;
            return;
        }
        String h = headingText(line);
        if (h != null) {
            flushBlock(lineNum - 1);
            int level = headingLevel(line);
            updateSectionPath(h, level);
            blocks.add(new Block("heading", h, level, currentSectionPath(), lineNum, lineNum));
            buf.setLength(0);
            return;
        }
        if (isListItem(line)) {
            flushBlock(lineNum - 1);
            state = State.LIST;
            blockStart = lineNum;
            buf.setLength(0);
            buf.append(line.strip()).append("\n");
            return;
        }
        if (line.isBlank()) {
            flushBlock(lineNum - 1);
            return;
        }
        if (buf.isEmpty()) blockStart = lineNum;
        buf.append(line).append("\n");
    }

    private void parseCodeLine(String line, int lineNum) {
        if (line.strip().startsWith("```")) {
            String content = buf.toString().strip();
            if (!content.isEmpty()) {
                blocks.add(new Block("code", content, 0, "", blockStart, lineNum));
            }
            buf.setLength(0);
            state = State.NORMAL;
            return;
        }
        buf.append(line).append("\n");
    }

    private void parseListLine(String line, int lineNum) {
        if (isListItem(line)) { buf.append(line.strip()).append("\n"); return; }
        if (line.isBlank()) { flushBlock(lineNum - 1); state = State.NORMAL; return; }
        buf.append(line.strip()).append("\n");
    }

    private void flushBlock(int lineEnd) {
        String content = buf.toString().strip();
        buf.setLength(0);
        if (!content.isEmpty()) {
            String type = (state == State.LIST) ? "list" : "paragraph";
            blocks.add(new Block(type, content, 0, currentSectionPath(), blockStart, lineEnd));
        }
        blockStart = lineEnd + 1;
    }

    private void updateSectionPath(String heading, int level) {
        while (sectionStack.size() >= level) {
            sectionStack.remove(sectionStack.size() - 1);
        }
        sectionStack.add(heading);
    }

    private String currentSectionPath() {
        return sectionStack.isEmpty() ? "" : String.join(" > ", sectionStack);
    }

    private boolean isListItem(String line) {
        String s = line.strip();
        if (s.isEmpty()) return false;
        return s.startsWith("- ") || s.startsWith("* ") || s.matches("^\\d+\\.\\s.*");
    }
}
