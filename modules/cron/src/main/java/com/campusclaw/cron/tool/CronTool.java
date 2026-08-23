/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.cron.tool;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolExecutionMode;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.cron.CronService;
import com.campusclaw.cron.model.CronJob;
import com.campusclaw.cron.model.CronPayload;
import com.campusclaw.cron.model.CronSchedule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 管理自动绑定当前受管 Agent 的 Cron Job。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class CronTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CronService cronService;

    private final String agentId;

    public CronTool(CronService cronService, String agentId) {
        this.cronService = cronService;
        this.agentId = agentId;
    }

    @Override
    public String name() {
        return "Cron";
    }

    @Override
    public String label() {
        return name();
    }

    @Override
    public String description() {
        return "Manage scheduled tasks bound to the current managed Agent.";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode properties = MAPPER.createObjectNode();
        addAction(properties);
        addCreateFields(properties);
        addLookupFields(properties);
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("action"));
        schema.put("additionalProperties", false);
        schema.set("oneOf", actionRequirements());
        return schema;
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate) {
        ensureNotCancelled(signal);
        String action = requireText(params, "action");
        return switch (action) {
            case "create" -> create(params);
            case "list" -> list();
            case "delete" -> delete(params);
            case "trigger" -> trigger(params);
            case "status" -> status(params);
            case "runs" -> runs(params);
            default -> throw new IllegalArgumentException("Unknown Cron action: " + action);
        };
    }

    private AgentToolResult create(Map<String, Object> params) {
        String name = requireText(params, "name");
        String prompt = requireText(params, "prompt");
        CronSchedule schedule = parseSchedule(
                requireText(params, "schedule_type"),
                requireText(params, "schedule_value"),
                optionalText(params, "timezone"));
        CronPayload payload = new CronPayload.AgentPrompt(agentId, prompt);
        CronJob job = cronService.createJob(name, optionalText(params, "description"), schedule, payload);
        return text("Created Cron Job " + job.id());
    }

    private AgentToolResult list() {
        List<CronJob> jobs = cronService.listJobs().stream()
                .filter(this::ownedByCurrentAgent)
                .toList();
        if (jobs.isEmpty()) {
            return text("No Cron Jobs configured for the current Agent.");
        }
        String result = jobs.stream()
                .map(job -> job.id() + " " + job.name() + " enabled=" + job.enabled())
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        return text(result);
    }

    private AgentToolResult delete(Map<String, Object> params) {
        CronJob job = requireOwnedJob(requireText(params, "job_id"));
        return text(cronService.deleteJob(job.id()) ? "Deleted Cron Job " + job.id() : "Cron Job not found");
    }

    private AgentToolResult trigger(Map<String, Object> params) {
        CronJob job = requireOwnedJob(requireText(params, "job_id"));
        var run = cronService.triggerJob(job.id());
        return text("Triggered Cron Job " + job.id() + " as run " + run.runId());
    }

    private AgentToolResult status(Map<String, Object> params) {
        CronJob job = requireOwnedJob(requireText(params, "job_id"));
        return text("Cron Job " + job.id() + " enabled=" + job.enabled()
                + " totalRuns=" + job.state().totalRuns()
                + " nextRunAt=" + job.state().nextRunAtMs());
    }

    private AgentToolResult runs(Map<String, Object> params) {
        CronJob job = requireOwnedJob(requireText(params, "job_id"));
        int limit = params.get("limit") instanceof Number value ? value.intValue() : 10;
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        String output = cronService.getRecentRuns(job.id(), limit).stream()
                .map(run -> run.runId() + " " + run.status() + " " + run.startedAtMs())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("No runs for Cron Job " + job.id());
        return text(output);
    }

    private CronJob requireOwnedJob(String jobId) {
        CronJob job = cronService.getJob(jobId).orElseThrow(() -> new IllegalArgumentException("Cron Job not found"));
        if (!ownedByCurrentAgent(job)) {
            throw new IllegalArgumentException("Cron Job not found");
        }
        return job;
    }

    private boolean ownedByCurrentAgent(CronJob job) {
        return job.payload() instanceof CronPayload.AgentPrompt payload && agentId.equals(payload.agentId());
    }

    private static CronSchedule parseSchedule(String type, String value, String timezone) {
        return switch (type) {
            case "at" -> new CronSchedule.At(parseAt(value));
            case "every" -> new CronSchedule.Every(parsePositiveLong(value));
            case "cron" -> parseCron(value, timezone);
            default -> throw new IllegalArgumentException("Unknown schedule type: " + type);
        };
    }

    private static long parseAt(String value) {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid at schedule", exception);
        }
    }

    private static long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("Interval must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid interval", exception);
        }
    }

    private static CronSchedule parseCron(String expression, String timezone) {
        org.springframework.scheduling.support.CronExpression.parse(expression);
        if (timezone != null) {
            ZoneId.of(timezone);
        }
        return new CronSchedule.CronExpr(expression, timezone);
    }

    private static void addAction(ObjectNode properties) {
        ObjectNode action = properties.putObject("action").put("type", "string");
        action.set(
                "enum",
                MAPPER.createArrayNode()
                        .add("create")
                        .add("list")
                        .add("delete")
                        .add("trigger")
                        .add("status")
                        .add("runs"));
    }

    private static void addCreateFields(ObjectNode properties) {
        addString(properties, "name", "Job name for create.");
        addString(properties, "description", "Optional Job description for create.");
        addString(properties, "prompt", "Prompt for create.");
        ObjectNode type = properties.putObject("schedule_type").put("type", "string");
        type.set("enum", MAPPER.createArrayNode().add("at").add("every").add("cron"));
        addString(properties, "schedule_value", "ISO timestamp, interval milliseconds, or cron expression.");
        addString(properties, "timezone", "Optional timezone for a cron expression.");
    }

    private static void addLookupFields(ObjectNode properties) {
        addString(properties, "job_id", "Job ID for delete, trigger, status, or runs.");
        properties
                .putObject("limit")
                .put("type", "integer")
                .put("description", "Run count for runs; omitted means 10.");
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode actionRequirements() {
        var oneOf = MAPPER.createArrayNode();
        oneOf.add(requiredFor("create", "name", "prompt", "schedule_type", "schedule_value"));
        oneOf.add(requiredFor("list"));
        for (String action : List.of("delete", "trigger", "status", "runs")) {
            oneOf.add(requiredFor(action, "job_id"));
        }
        return oneOf;
    }

    private static ObjectNode requiredFor(String action, String... fields) {
        ObjectNode branch = MAPPER.createObjectNode();
        branch.set(
                "properties",
                MAPPER.createObjectNode()
                        .set("action", MAPPER.createObjectNode().put("const", action)));
        if (fields.length > 0) {
            var required = branch.putArray("required");
            for (String field : fields) {
                required.add(field);
            }
        }
        return branch;
    }

    private static void addString(ObjectNode properties, String name, String description) {
        properties.putObject(name).put("type", "string").put("description", description);
    }

    private static String requireText(Map<String, Object> params, String name) {
        String value = optionalText(params, name);
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String optionalText(Map<String, Object> params, String name) {
        Object value = params.get(name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static AgentToolResult text(String value) {
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(value)), null);
    }

    private static void ensureNotCancelled(CancellationToken signal) {
        if (signal != null && signal.isCancelled()) {
            throw new java.util.concurrent.CancellationException("Tool execution was cancelled");
        }
    }
}
