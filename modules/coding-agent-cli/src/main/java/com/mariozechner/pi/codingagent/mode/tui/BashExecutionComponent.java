package com.mariozechner.pi.codingagent.mode.tui;

import com.mariozechner.pi.tui.Component;
import com.mariozechner.pi.tui.ansi.AnsiUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays a user-initiated bash command ({@code !} prefix) and its output.
 * Matches pi-mono TS bash execution display.
 */
public class BashExecutionComponent implements Component {

    private static final String ANSI_DIM = "\033[2m";
    private static final String ANSI_BOLD = "\033[1m";
    private static final String ANSI_YELLOW = "\033[33m";
    private static final String ANSI_RED = "\033[31m";
    private static final String ANSI_GREEN = "\033[32m";
    private static final String ANSI_RESET = "\033[0m";

    private final String command;
    private final boolean excluded;
    private String output;
    private Integer exitCode;
    private boolean complete;

    /**
     * @param command  the bash command text
     * @param excluded true if this was a {@code !!} command (excluded from context)
     */
    public BashExecutionComponent(String command, boolean excluded) {
        this.command = command;
        this.excluded = excluded;
    }

    public void setResult(String output, Integer exitCode) {
        this.output = output;
        this.exitCode = exitCode;
        this.complete = true;
    }

    @Override
    public List<String> render(int width) {
        var lines = new ArrayList<String>();
        lines.add(""); // spacer

        // Command line: $ command (or $$ for excluded)
        String prefix = excluded ? "$$" : "$";
        String excludedHint = excluded ? ANSI_DIM + " (no context)" + ANSI_RESET : "";
        lines.add(ANSI_YELLOW + ANSI_BOLD + "  " + prefix + " " + ANSI_RESET
                + ANSI_BOLD + command + ANSI_RESET + excludedHint);

        if (!complete) {
            lines.add(ANSI_DIM + "    running..." + ANSI_RESET);
            return lines;
        }

        // Output lines
        if (output != null && !output.isEmpty()) {
            int contentWidth = Math.max(1, width - 4);
            String[] outputLines = output.split("\n", -1);
            // Limit display to 50 lines
            int maxLines = 50;
            int linesToShow = Math.min(outputLines.length, maxLines);
            for (int i = 0; i < linesToShow; i++) {
                String line = outputLines[i];
                if (AnsiUtils.visibleWidth(line) > contentWidth) {
                    line = AnsiUtils.sliceByColumn(line, 0, Math.max(1, contentWidth - 3)) + "...";
                }
                lines.add(ANSI_DIM + "    " + line + ANSI_RESET);
            }
            if (outputLines.length > maxLines) {
                lines.add(ANSI_DIM + "    ... " + (outputLines.length - maxLines) + " more lines" + ANSI_RESET);
            }
        }

        // Exit code
        if (exitCode != null && exitCode != 0) {
            lines.add(ANSI_RED + "    exit code: " + exitCode + ANSI_RESET);
        } else if (exitCode == null) {
            lines.add(ANSI_RED + "    timed out" + ANSI_RESET);
        }

        return lines;
    }

    @Override
    public void invalidate() { }
}
