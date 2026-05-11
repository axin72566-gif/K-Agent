package com.axin.kagent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 纯文本读取器：全文作为一个段落 Block。
 */
public class PlainTextReader implements DocumentReader {

    private final String title;
    private final String content;

    public PlainTextReader(Path file) throws IOException {
        this.title = file.getFileName().toString();
        this.content = Files.readString(file);
    }

    @Override
    public String getTitle() { return title; }

    @Override
    public List<Block> parse() {
        if (content.isBlank()) return List.of();
        return List.of(new Block("paragraph", content.strip(), 0, "", 1,
            (int) content.lines().count()));
    }
}
