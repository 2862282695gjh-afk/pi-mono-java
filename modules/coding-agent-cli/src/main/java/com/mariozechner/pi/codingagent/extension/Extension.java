package com.mariozechner.pi.codingagent.extension;

import com.mariozechner.pi.agent.event.AgentEventListener;
import com.mariozechner.pi.agent.tool.AgentTool;
import com.mariozechner.pi.agent.tool.AfterToolCallHandler;
import com.mariozechner.pi.agent.tool.BeforeToolCallHandler;
import com.mariozechner.pi.codingagent.command.SlashCommand;

import java.util.List;

/**
 * An extension that can contribute tools, commands, and hooks to the coding agent.
 *
 * <p>Extensions are discovered by the {@link ExtensionRegistry} from configured
 * packages (npm/git/local directories). Each extension declares what it provides
 * via the getter methods below.
 */
public interface Extension {

    /** Unique identifier for this extension. */
    String id();

    /** Human-readable name. */
    String name();

    /** Additional tools provided by this extension. */
    default List<AgentTool> tools() { return List.of(); }

    /** Additional slash commands provided by this extension. */
    default List<SlashCommand> commands() { return List.of(); }

    /** Before-tool-call hooks. */
    default List<BeforeToolCallHandler> beforeToolCallHandlers() { return List.of(); }

    /** After-tool-call hooks. */
    default List<AfterToolCallHandler> afterToolCallHandlers() { return List.of(); }

    /** Event listeners. */
    default List<AgentEventListener> eventListeners() { return List.of(); }

    /** Called when the extension is loaded. */
    default void onLoad() {}

    /** Called when the extension is unloaded. */
    default void onUnload() {}
}
