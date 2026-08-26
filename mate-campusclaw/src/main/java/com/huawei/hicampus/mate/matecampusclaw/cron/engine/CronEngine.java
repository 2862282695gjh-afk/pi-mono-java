/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.cron.engine;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import com.huawei.hicampus.mate.matecampusclaw.agent.util.LoggingUncaughtExceptionHandler;
import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronEvent;
import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronJob;
import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronJobState;
import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronRunRecord;
import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronSchedule;
import com.huawei.hicampus.mate.matecampusclaw.cron.store.CronStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

/**
 * 管理 Cron Job 进程内调度、并发执行和生命周期的当前 Host 引擎。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class CronEngine {

    private static final Logger log = LoggerFactory.getLogger(CronEngine.class);
    private static final long STALE_THRESHOLD_MS = 2L * 60 * 60 * 1000;
    private static final int MAX_CONSECUTIVE_ERRORS = 3;

    private final CronStore store;
    private final CronJobExecutor executor;
    private final List<CronEventListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, ScheduledFuture<?>> scheduledJobs = new ConcurrentHashMap<>();
    private final ReentrantLock tickLock = new ReentrantLock();

    private volatile ScheduledExecutorService scheduler;
    private volatile boolean running;

    public CronEngine(CronStore store, CronJobExecutor executor) {
        this.store = store;
        this.executor = executor;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cron-engine");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler(LoggingUncaughtExceptionHandler.INSTANCE);
            return t;
        });

        // 清理残留的运行中标记。
        cleanStaleRunning();

        // 装配全部已启用任务。
        var jobs = store.load();
        for (var job : jobs) {
            if (job.enabled()) {
                scheduleJob(job);
            }
        }
        log.info(
                "Cron engine started with {} jobs ({} enabled)",
                jobs.size(),
                jobs.stream().filter(CronJob::enabled).count());
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        scheduledJobs.values().forEach(f -> f.cancel(false));
        scheduledJobs.clear();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        log.info("Cron engine stopped");
    }

    public boolean isRunning() {
        return running;
    }

    public void addListener(CronEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(CronEventListener listener) {
        listeners.remove(listener);
    }

    /**
     * 创建或更新任务后安装其调度计划。
     *
     * @param job 待安装或刷新的任务
     */
    public void scheduleJob(CronJob job) {
        if (!running || scheduler == null) {
            return;
        }

        // 取消该任务已有的调度计划。
        var existing = scheduledJobs.remove(job.id());
        if (existing != null) {
            existing.cancel(false);
        }

        if (!job.enabled()) {
            return;
        }

        long delayMs = computeNextDelay(job);
        if (delayMs < 0) {
            log.debug("Job {} has no next run time, skipping", job.name());
            return;
        }

        var future = scheduler.schedule(() -> executeAndReschedule(job.id()), delayMs, TimeUnit.MILLISECONDS);
        scheduledJobs.put(job.id(), future);

        // 在任务状态中更新下一次运行时间。
        long nextRunAtMs = System.currentTimeMillis() + delayMs;
        store.updateJob(job.withState(new CronJobState(
                nextRunAtMs,
                job.state().runningAtMs(),
                job.state().lastRunAtMs(),
                job.state().lastRunStatus(),
                job.state().consecutiveErrors(),
                job.state().totalRuns())));

        log.debug("Scheduled job {} to run in {}ms", job.name(), delayMs);
    }

    /**
     * 删除或禁用任务时取消其调度计划。
     *
     * @param jobId 待取消调度的任务标识
     */
    public void unscheduleJob(String jobId) {
        var future = scheduledJobs.remove(jobId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * 立即触发一个任务。
     *
     * @param jobId 待运行的任务标识
     * @return 本次运行记录
     * @throws IllegalArgumentException 指定任务不存在时抛出
     */
    public CronRunRecord triggerJob(String jobId) {
        var jobOpt = store.getJob(jobId);
        if (jobOpt.isEmpty()) {
            throw new IllegalArgumentException("Job not found: " + jobId);
        }
        return executeJob(jobOpt.get());
    }

    private void executeAndReschedule(String jobId) {
        if (!tickLock.tryLock()) {
            log.debug("Tick lock busy, skipping job {}", jobId);
            return;
        }
        try {
            var jobOpt = store.getJob(jobId);
            if (jobOpt.isEmpty() || !jobOpt.get().enabled()) {
                return;
            }

            var job = jobOpt.get();

            // 已经运行中的任务不重复执行。
            if (job.state().runningAtMs() != 0) {
                log.debug("Job {} is already running, skipping", job.name());
                return;
            }

            executeJob(job);

            // 任务仍启用时重新安装下一次调度。
            var updatedJob = store.getJob(jobId);
            if (updatedJob.isPresent() && updatedJob.get().enabled()) {
                scheduleJob(updatedJob.get());
            }
        } finally {
            tickLock.unlock();
        }
    }

    private CronRunRecord executeJob(CronJob job) {
        markJobRunning(job);
        emit(new CronEvent.JobStarted(job.id(), job.name(), ""));
        try {
            CronRunRecord result = executor.execute(job);
            recordJobOutcome(job, result);
            return result;
        } catch (Exception e) {
            log.error("Unexpected error executing job {}", job.name(), e);
            return recordJobException(job, e);
        }
    }

    private void markJobRunning(CronJob job) {
        store.updateJob(job.withState(new CronJobState(
                job.state().nextRunAtMs(),
                System.currentTimeMillis(),
                job.state().lastRunAtMs(),
                job.state().lastRunStatus(),
                job.state().consecutiveErrors(),
                job.state().totalRuns())));
    }

    private void recordJobOutcome(CronJob job, CronRunRecord result) {
        boolean success = result.status() == CronRunRecord.RunStatus.SUCCESS;
        int errors = success ? 0 : job.state().consecutiveErrors() + 1;
        boolean shouldDisable = errors >= MAX_CONSECUTIVE_ERRORS;
        var newState = new CronJobState(
                0,
                0,
                System.currentTimeMillis(),
                success ? "success" : "failed",
                errors,
                job.state().totalRuns() + 1);
        var updatedJob = job.withState(newState);
        if (shouldDisable) {
            updatedJob = updatedJob.withEnabled(false);
            log.warn("Job {} auto-disabled after {} consecutive errors", job.name(), errors);
        }

        // 一次性 At 任务成功后按配置删除。
        if (job.deleteAfterRun() && success) {
            store.removeJob(job.id());
            unscheduleJob(job.id());
        } else {
            store.updateJob(updatedJob);
        }
        if (success) {
            emit(new CronEvent.JobCompleted(job.id(), job.name(), result.runId(), result.output()));
        } else {
            String failedCode = result.errorCode() != null
                    ? result.errorCode()
                    : CronErrorCode.CRON_EXECUTION_FAILED.name();
            emit(new CronEvent.JobFailed(job.id(), job.name(), result.runId(), failedCode));
        }
    }

    private CronRunRecord recordJobException(CronJob job, Exception e) {
        int errors = job.state().consecutiveErrors() + 1;
        var newState = new CronJobState(
                0, 0, System.currentTimeMillis(), "failed", errors, job.state().totalRuns() + 1);
        var updatedJob = job.withState(newState);
        if (errors >= MAX_CONSECUTIVE_ERRORS) {
            updatedJob = updatedJob.withEnabled(false);
        }
        store.updateJob(updatedJob);
        String engineErrorCode = stableCodeOf(e);
        emit(new CronEvent.JobFailed(job.id(), job.name(), "", engineErrorCode));
        return new CronRunRecord(
                "",
                job.id(),
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                CronRunRecord.RunStatus.FAILED,
                engineErrorCode,
                null,
                null,
                0);
    }

    private static String stableCodeOf(Exception exception) {
        if (exception instanceof com.huawei.hicampus.mate.matecampusclaw.agent.error.StableErrorCode coded) {
            return coded.stableErrorCode();
        }
        return CronErrorCode.CRON_EXECUTION_FAILED.name();
    }

    long computeNextDelay(CronJob job) {
        long now = System.currentTimeMillis();
        return switch (job.schedule()) {
            case CronSchedule.At at -> {
                long delay = at.timestampMs() - now;
                yield delay > 0 ? delay : -1;
            }
            case CronSchedule.Every every -> {
                long base = Math.max(job.state().lastRunAtMs(), job.createdAtMs());
                long next = base + every.intervalMs();

                // 连续失败时应用指数退避。
                if (job.state().consecutiveErrors() > 0) {
                    long backoff = Math.min(1000L * (1L << job.state().consecutiveErrors()), 3_600_000L);
                    next = Math.max(next, now + backoff);
                }
                yield Math.max(0, next - now);
            }
            case CronSchedule.CronExpr cron -> {
                try {
                    var expr = CronExpression.parse(cron.expression());
                    ZoneId zone = cron.timezone() != null ? ZoneId.of(cron.timezone()) : ZoneId.systemDefault();
                    var next = expr.next(ZonedDateTime.now(zone));
                    if (next == null) {
                        yield -1L;
                    }
                    yield Math.max(0, next.toInstant().toEpochMilli() - now);
                } catch (Exception e) {
                    log.error("Invalid cron expression for job {}: {}", job.name(), cron.expression(), e);
                    yield -1L;
                }
            }
        };
    }

    private void cleanStaleRunning() {
        long now = System.currentTimeMillis();
        var jobs = new ArrayList<>(store.load());
        for (var job : jobs) {
            if (job.state().runningAtMs() != 0 && (now - job.state().runningAtMs()) > STALE_THRESHOLD_MS) {
                log.warn(
                        "Clearing stale running mark for job {} (running since {})",
                        job.name(),
                        Instant.ofEpochMilli(job.state().runningAtMs()));
                store.updateJob(job.withState(new CronJobState(
                        job.state().nextRunAtMs(),
                        0,
                        job.state().lastRunAtMs(),
                        "stale",
                        job.state().consecutiveErrors(),
                        job.state().totalRuns())));
            }
        }
    }

    private void emit(CronEvent event) {
        for (var listener : listeners) {
            try {
                listener.onCronEvent(event);
            } catch (Exception e) {
                log.debug("Cron event listener error", e);
            }
        }
    }
}
