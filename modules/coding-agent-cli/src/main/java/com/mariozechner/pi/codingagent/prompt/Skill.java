package com.mariozechner.pi.codingagent.prompt;

/**
 * A loadable skill that extends agent capabilities with specialized knowledge or workflows.
 *
 * @param name        the skill identifier (e.g. "commit", "review-pr")
 * @param description human-readable description of what the skill does
 * @param location    file path or URI where the skill definition lives
 */
public record Skill(
        String name,
        String description,
        String location
) {
}
