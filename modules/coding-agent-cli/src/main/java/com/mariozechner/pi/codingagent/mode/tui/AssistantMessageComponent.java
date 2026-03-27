package com.mariozechner.pi.codingagent.mode.tui;

import com.mariozechner.pi.tui.Component;
import com.mariozechner.pi.tui.component.MarkdownComponent;
import com.mariozechner.pi.tui.ansi.AnsiUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders an assistant message with optional thinking block and markdown-formatted text.
 * Updated incrementally during streaming.
 */
public class AssistantMessageComponent implements Component {

    private static final String ANSI_DIM = "\033[2m";
    private static final String ANSI_ITALIC = "\033[3m";
    private static final String ANSI_RESET = "\033[0m";

    private final StringBuilder thinkingContent = new StringBuilder();
    private final StringBuilder textContent = new StringBuilder();
    private final MarkdownComponent markdownComponent = new MarkdownComponent();

    private boolean complete = false;

    // Cache — includes complete state in key
    private String cachedThinking;
    private String cachedText;
    private boolean cachedComplete;
    private int cachedWidth = -1;
    private List<String> cachedLines;

    public void appendThinking(String delta) {
        thinkingContent.append(delta);
        invalidate();
    }

    public void appendText(String delta) {
        textContent.append(delta);
        invalidate();
    }

    public void setComplete(boolean complete) {
        this.complete = complete;
        invalidate();
    }

    public boolean hasContent() {
        return !thinkingContent.isEmpty() || !textContent.isEmpty();
    }

    @Override
    public void invalidate() {
        cachedLines = null;
        cachedWidth = -1;
    }

    @Override
    public List<String> render(int width) {
        String thinking = thinkingContent.toString();
        String text = textContent.toString();

        if (cachedLines != null && cachedWidth == width
                && cachedComplete == complete
                && thinking.equals(cachedThinking)
                && text.equals(cachedText)) {
            return cachedLines;
        }

        var lines = new ArrayList<String>();
        lines.add(""); // spacer before message

        // Thinking block — dim italic
        if (!thinking.isBlank()) {
            int contentWidth = Math.max(1, width - 4);
            List<String> thinkingLines = AnsiUtils.wrapTextWithAnsi(thinking.strip(), contentWidth);
            for (String line : thinkingLines) {
                lines.add(ANSI_DIM + ANSI_ITALIC + "  " + line + ANSI_RESET);
            }
            if (!text.isBlank()) {
                lines.add(""); // spacer between thinking and text
            }
        }

        // Text content — rendered as markdown
        if (!text.isBlank()) {
            markdownComponent.setContent(text);
            lines.addAll(markdownComponent.render(width));
        }

        // Streaming indicator — only when not complete AND no content yet
        if (!complete && text.isEmpty() && thinking.isEmpty()) {
            lines.add(ANSI_DIM + "  ..." + ANSI_RESET);
        }

        cachedThinking = thinking;
        cachedText = text;
        cachedComplete = complete;
        cachedWidth = width;
        cachedLines = lines;
        return lines;
    }
}
