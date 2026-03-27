package com.mariozechner.pi.tui;

import com.mariozechner.pi.tui.component.Container;
import com.mariozechner.pi.tui.terminal.Terminal;
import com.mariozechner.pi.tui.terminal.TerminalSize;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Full-screen TUI renderer. Manages a component tree, renders it to terminal lines,
 * and uses synchronized output for flicker-free display.
 *
 * <p>Rendering strategy: always clear-and-redraw the visible viewport.
 * This is simple, correct, and matches how pi-mono TS handles structural changes.
 * The synchronized output escape sequences prevent flicker.
 */
public class Tui {

    private static final String SYNC_START = "\033[?2026h";
    private static final String SYNC_END = "\033[?2026l";
    private static final String CLEAR_LINE = "\033[2K";
    private static final String HIDE_CURSOR = "\033[?25l";
    private static final String SHOW_CURSOR = "\033[?25h";
    private static final String HOME = "\033[H";
    private static final String ERASE_SCREEN = "\033[2J";

    private final Terminal terminal;

    private Container root;
    private Consumer<String> inputHandler;
    private volatile boolean running = false;
    private int lastHeight = 0;

    public Tui(Terminal terminal) {
        this.terminal = Objects.requireNonNull(terminal);
    }

    public void setRoot(Container root) {
        this.root = root;
    }

    public void setInputHandler(Consumer<String> handler) {
        this.inputHandler = handler;
    }

    /** Start the TUI. Enters raw mode, hides cursor, clears screen. */
    public void start() {
        running = true;
        terminal.enterRawMode();
        terminal.write(HIDE_CURSOR + ERASE_SCREEN + HOME);

        terminal.onInput(data -> {
            if (inputHandler != null) {
                inputHandler.accept(data);
            }
        });

        terminal.onResize(size -> render());
    }

    /** Stop the TUI. Shows cursor, moves below content, exits raw mode. */
    public void stop() {
        running = false;
        // Move cursor below all content and show it
        TerminalSize size = terminal.getSize();
        terminal.write("\033[" + size.height() + ";1H" + SHOW_CURSOR + "\r\n");
        terminal.exitRawMode();
    }

    /**
     * Synchronously render the component tree to the terminal.
     * Always redraws the full viewport — simple, correct, flicker-free with sync output.
     */
    public synchronized void render() {
        if (!running || root == null) return;

        TerminalSize size = terminal.getSize();
        int width = size.width();
        int height = size.height();
        if (width <= 0 || height <= 0) return;

        // Render component tree
        List<String> allLines = root.render(width);

        // Build output buffer with synchronized output
        var sb = new StringBuilder(allLines.size() * (width + 20));
        sb.append(SYNC_START);
        sb.append(HOME); // Move cursor to top-left

        // Show only the last `height` lines (viewport scrolling)
        int startLine = Math.max(0, allLines.size() - height);
        int visibleCount = Math.min(allLines.size(), height);

        for (int i = 0; i < visibleCount; i++) {
            sb.append(CLEAR_LINE);
            sb.append(allLines.get(startLine + i));
            if (i < visibleCount - 1) {
                sb.append("\r\n");
            }
        }

        // Clear any remaining rows below content (from previous renders)
        for (int i = visibleCount; i < height; i++) {
            sb.append("\r\n").append(CLEAR_LINE);
        }

        sb.append(SYNC_END);
        terminal.write(sb.toString());
        lastHeight = height;
    }

    public Terminal getTerminal() {
        return terminal;
    }
}
