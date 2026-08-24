/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.campusclaw.agent.Agent;
import com.campusclaw.agent.event.MessageUpdateEvent;
import com.campusclaw.agent.state.AgentState;
import com.campusclaw.agent.util.LoggingUncaughtExceptionHandler;
import com.campusclaw.ai.stream.AssistantMessageEvent;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.codingagent.command.SlashCommandRegistry;
import com.campusclaw.codingagent.command.builtin.HelpCommand;
import com.campusclaw.codingagent.command.builtin.QuitCommand;
import com.campusclaw.codingagent.session.AgentSession;
import com.campusclaw.codingagent.skill.SkillRegistry;
import com.campusclaw.codingagent.tool.bash.BashExecutor;
import com.campusclaw.tui.terminal.TestTerminal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InteractiveModeTest {

    @Mock
    AgentSession session;

    @Mock
    Agent agent;

    @Mock
    BashExecutor bashExecutor;

    InputReadyTerminal terminal;
    AgentState state;
    SlashCommandRegistry registry;
    InteractiveMode mode;

    @BeforeEach
    void setUp() {
        terminal = new InputReadyTerminal(80, 24);
        state = new AgentState();
        registry = new SlashCommandRegistry();
        registry.register(new HelpCommand(registry));
        registry.register(new QuitCommand());
        mode = new InteractiveMode(registry, bashExecutor, null, null, null, null);

        when(session.getAgent()).thenReturn(agent);
        when(agent.getState()).thenReturn(state);
        when(session.getSkillRegistry()).thenReturn(new SkillRegistry());
        when(session.getPromptTemplates()).thenReturn(List.of());
    }

    // -------------------------------------------------------------------
    // Event handling (unit tests — no TUI needed)
    // -------------------------------------------------------------------

    @Nested
    class EventHandling {

        @Test
        void textDeltaUpdatesAssistantComponent() {
            var partial = new AssistantMessage(
                    List.of(new TextContent("Hello", null)),
                    "messages",
                    "anthropic",
                    "model",
                    null,
                    Usage.empty(),
                    null,
                    null,
                    1L);
            var delta = new AssistantMessageEvent.TextDeltaEvent(0, "Hello", partial);
            var event = new MessageUpdateEvent(partial, delta);

            var component = new com.campusclaw.codingagent.mode.tui.AssistantMessageComponent();
            component.appendText("Hello");
            assertTrue(component.hasContent());

            var lines = component.render(80);
            assertTrue(lines.stream().anyMatch(l -> l.contains("Hello")));
        }

        @Test
        void thinkingDeltaRendersInItalic() {
            var component = new com.campusclaw.codingagent.mode.tui.AssistantMessageComponent();
            component.appendThinking("Let me think...");

            var lines = component.render(80);
            String output = String.join("\n", lines);
            assertTrue(output.contains("Let me think"));
            assertTrue(output.contains("\033[3m")); // italic
        }

        @Test
        void toolStatusShowsRunningThenDone() {
            var tool = new com.campusclaw.codingagent.mode.tui.ToolStatusComponent("bash");
            var running = tool.render(80);
            String runningOutput = String.join("", running);

            // Tool shows bold name on pending bg
            assertTrue(runningOutput.contains("bash"));

            tool.setComplete(false);
            var done = tool.render(80);
            String doneOutput = String.join("", done);

            // Tool shows bold name on success bg
            assertTrue(doneOutput.contains("bash"));
        }

        @Test
        void toolStatusShowsFailed() {
            var tool = new com.campusclaw.codingagent.mode.tui.ToolStatusComponent("bash");
            tool.setComplete(true);
            var lines = tool.render(80);
            String output = String.join("", lines);

            // Tool shows bold name on error bg
            assertTrue(output.contains("bash"));
            assertTrue(output.contains("\033[48;2;60;40;40m")); // error bg
        }
    }

    // -------------------------------------------------------------------
    // Footer component
    // -------------------------------------------------------------------

    @Nested
    class FooterTests {

        @Test
        void rendersModelInfo() {
            var footer = new com.campusclaw.codingagent.mode.tui.FooterComponent();
            footer.setModel("zai", "glm-5", 200000, false);
            var lines = footer.render(80);

            String output = String.join("\n", lines);
            assertTrue(output.contains("glm-5"));
            assertTrue(output.contains("zai"));
        }

        @Test
        void rendersTokenStats() {
            var footer = new com.campusclaw.codingagent.mode.tui.FooterComponent();
            footer.setModel("zai", "glm-5", 200000, false);
            footer.updateUsage(1500, 200, 0, 0, 0.001);
            var lines = footer.render(80);

            String output = String.join("\n", lines);
            assertTrue(output.contains("1.5k")); // input tokens
            assertTrue(output.contains("200")); // output tokens
        }

        @Test
        void rendersPwdAndStatsLines() {
            var footer = new com.campusclaw.codingagent.mode.tui.FooterComponent();
            footer.setModel("zai", "glm-5", 200000, false);
            footer.setCwd(System.getProperty("user.home") + "/project");
            var lines = footer.render(80);
            assertEquals(2, lines.size()); // pwd + stats
            assertTrue(lines.get(0).contains("~")); // pwd with ~ substitution
        }

        @Test
        void contextPercentageColorCoding() {
            var footer = new com.campusclaw.codingagent.mode.tui.FooterComponent();
            footer.setModel("zai", "glm-5", 1000, false);

            // 95% usage — should be red
            footer.updateUsage(950, 0, 0, 0, 0);
            var lines = footer.render(120);
            String output = String.join("\n", lines);
            assertTrue(output.contains("\033[31m")); // red
        }

        @Test
        void tokenFormattingMillions() {
            assertEquals("1.5M", com.campusclaw.codingagent.mode.tui.FooterComponent.formatTokens(1500000));
            assertEquals("15M", com.campusclaw.codingagent.mode.tui.FooterComponent.formatTokens(15000000));
            assertEquals("200k", com.campusclaw.codingagent.mode.tui.FooterComponent.formatTokens(200000));
            assertEquals("1.5k", com.campusclaw.codingagent.mode.tui.FooterComponent.formatTokens(1500));
            assertEquals("500", com.campusclaw.codingagent.mode.tui.FooterComponent.formatTokens(500));
        }
    }

    // -------------------------------------------------------------------
    // Bash execution component
    // -------------------------------------------------------------------

    @Nested
    class BashExecutionTests {

        @Test
        void rendersCommandAndOutput() {
            var comp = new com.campusclaw.codingagent.mode.tui.BashExecutionComponent("ls -la", false);
            comp.setResult("file1.txt\nfile2.txt", 0);
            var lines = comp.render(80);
            String output = String.join("\n", lines);
            String stripped = output.replaceAll("\033\\[[;\\d]*[a-zA-Z]", "");
            assertTrue(stripped.contains("$ ls -la"));
            assertTrue(stripped.contains("file1.txt"));
        }

        @Test
        void excludedCommandShowsDollarDollar() {
            var comp = new com.campusclaw.codingagent.mode.tui.BashExecutionComponent("pwd", true);
            comp.setResult("/home/user", 0);
            var lines = comp.render(80);
            String output = String.join("\n", lines);
            String stripped = output.replaceAll("\033\\[[;\\d]*[a-zA-Z]", "");
            assertTrue(stripped.contains("$$ pwd"));
            assertTrue(stripped.contains("no context"));
        }

        @Test
        void showsExitCodeOnError() {
            var comp = new com.campusclaw.codingagent.mode.tui.BashExecutionComponent("bad-cmd", false);
            comp.setResult("command not found", 127);
            var lines = comp.render(80);
            String output = String.join("\n", lines);
            assertTrue(output.contains("(exit 127)"));
        }

        @Test
        void showsRunningWhenIncomplete() {
            var comp = new com.campusclaw.codingagent.mode.tui.BashExecutionComponent("sleep 10", false);
            var lines = comp.render(80);
            String output = String.join("\n", lines);

            // BashExecutionComponent shows "running..." in gray when incomplete
            String stripped = output.replaceAll("\033\\[[;\\d]*[a-zA-Z]", "");
            assertTrue(stripped.contains("running..."));
        }
    }

    // -------------------------------------------------------------------
    // REPL integration (full run with TUI)
    // -------------------------------------------------------------------

    @Nested
    class ReplIntegration {

        @Test
        void ctrlDExitsCleanly() throws InterruptedException {
            InputDriver driver = startInputDriver(() -> {
                terminal.simulateInput("\u0004");
            });

            mode.run(session, terminal);
            joinInputDriver(driver);
            assertFalse(terminal.getFullOutput().contains("Error"));
        }

        @Test
        void showsWelcomeMessage() throws InterruptedException {
            InputDriver driver = startInputDriver(() -> {
                terminal.simulateInput("\u0004");
            });

            mode.run(session, terminal);
            joinInputDriver(driver);

            // Welcome text may scroll off in small terminal; check for content that's visible
            String output = terminal.getFullOutput();
            assertTrue(output.contains("CampusClaw can explain") || output.contains("v0.1.0"));
        }

        @Test
        void welcomeMessageShowsBashHints() throws InterruptedException {
            InputDriver driver = startInputDriver(() -> {
                terminal.simulateInput("\u0004");
            });

            mode.run(session, terminal);
            joinInputDriver(driver);
            String output = terminal.getFullOutput();
            assertTrue(output.contains("run bash"));
        }

        @Test
        void slashHelpShowsCommands() throws InterruptedException {
            InputDriver driver = startInputDriver(() -> {
                typeChars("/help");
                terminal.simulateInput("\r");
                assertTrue(terminal.awaitOutputContains("Available commands"), "slash help output did not render");
                terminal.simulateInput("\u0004");
            });

            mode.run(session, terminal);
            joinInputDriver(driver);
            assertTrue(terminal.getFullOutput().contains("Available commands"));
        }

        @Test
        void promptIsSentToSession() throws InterruptedException {
            CountDownLatch promptCalled = new CountDownLatch(1);
            when(session.prompt("hello")).thenAnswer(invocation -> {
                promptCalled.countDown();
                return CompletableFuture.completedFuture(null);
            });

            InputDriver driver = startInputDriver(() -> {
                typeChars("hello");
                terminal.simulateInput("\r");
                promptCalled.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                terminal.simulateInput("\u0004");
            });

            mode.run(session, terminal);
            joinInputDriver(driver);
            verify(session).prompt("hello");
        }
    }

    // -------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------

    @Nested
    class InputValidation {

        @Test
        void throwsOnNullSession() {
            assertThrows(NullPointerException.class, () -> mode.run(null, terminal));
        }

        @Test
        void throwsOnNullTerminal() {
            assertThrows(NullPointerException.class, () -> mode.run(session, null));
        }

        @Test
        void throwsOnNullRegistry() {
            assertThrows(NullPointerException.class, () -> new InteractiveMode(null, null, null, null, null, null));
        }
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private static final int TEST_TIMEOUT_MS = 2_000;

    private void typeChars(String text) {
        for (char c : text.toCharArray()) {
            terminal.simulateInput(String.valueOf(c));
        }
    }

    private InputDriver startInputDriver(ThrowingRunnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                assertTrue(terminal.awaitInputReady(), "terminal input handler was not registered");
                action.run();
            } catch (Throwable e) {
                failure.set(e);
                terminal.simulateInput("\u0004");
            }
        });
        thread.setUncaughtExceptionHandler(LoggingUncaughtExceptionHandler.INSTANCE);
        thread.start();
        return new InputDriver(thread, failure);
    }

    private static void joinInputDriver(InputDriver driver) throws InterruptedException {
        driver.thread().join(TEST_TIMEOUT_MS);
        assertFalse(driver.thread().isAlive(), "input driver did not finish");
        Throwable failure = driver.failure().get();
        if (failure != null) {
            if (failure instanceof AssertionError assertionError) {
                throw assertionError;
            }
            throw new AssertionError("input driver failed", failure);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record InputDriver(Thread thread, AtomicReference<Throwable> failure) {}

    private static final class InputReadyTerminal extends TestTerminal {

        private final CountDownLatch inputReady = new CountDownLatch(1);

        private InputReadyTerminal(int width, int height) {
            super(width, height);
        }

        @Override
        public void onInput(Consumer<String> listener) {
            super.onInput(listener);
            inputReady.countDown();
        }

        @Override
        public synchronized void write(String data) {
            super.write(data);
            notifyAll();
        }

        @Override
        public synchronized String getFullOutput() {
            return super.getFullOutput();
        }

        private boolean awaitInputReady() throws InterruptedException {
            return inputReady.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        private synchronized boolean awaitOutputContains(String needle) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TEST_TIMEOUT_MS);
            while (!super.getFullOutput().contains(needle)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            return true;
        }
    }
}
