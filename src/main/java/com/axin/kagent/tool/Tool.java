package com.axin.kagent.tool;

public interface Tool {
    String getName();
    String getDescription();
    String execute(String input);
}
