/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.cron.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronEvent;
import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronJob;
import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronJobState;
import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronPayload;
import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronRunRecord;
import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronRunRecord.RunStatus;
import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronSchedule;
import com.huawei.hicampus.mate.matecampusclaw.cron.store.CronStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * {@link CronEngine} 的调度、失败退避、监听器通知和连续失败自动禁用测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CronEngineTest {

    @Mock
    CronStore store;

    @Mock
    CronJobExecutor executor;

    private CronEngine engine;

    private static CronJob job(String id, boolean enabled, CronSchedule schedule, CronJobState state) {
        return new CronJob(
                id,
                "job-" + id,
                null,
                enabled,
                false,
                schedule,
                new CronPayload.AgentPrompt("agent-test", "p"),
                state,
                0L);
    }

    private static CronJob job(String id, boolean enabled, CronSchedule schedule) {
        return job(id, enabled, schedule, CronJobState.initial());
    }

    @AfterEach
    void cleanup() {
        if (engine != null && engine.isRunning()) {
            engine.stop();
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void startAndStopFlipsRunningFlag() {
            when(store.load()).thenReturn(List.of());
            engine = new CronEngine(store, executor);
            assertThat(engine.isRunning()).isFalse();
            engine.start();
            assertThat(engine.isRunning()).isTrue();
            engine.stop();
            assertThat(engine.isRunning()).isFalse();
        }

        @Test
        void doubleStartIsIdempotent() {
            when(store.load()).thenReturn(List.of());
            engine = new CronEngine(store, executor);
            engine.start();
            engine.start();
            assertThat(engine.isRunning()).isTrue();
        }

        @Test
        void doubleStopIsIdempotent() {
            engine = new CronEngine(store, executor);
            engine.stop();
            assertThat(engine.isRunning()).isFalse();
        }

        @Test
        void startSchedulesEnabledJobs() {
            CronJob enabled = job("j1", true, new CronSchedule.Every(60_000L));
            CronJob disabled = job("j2", false, new CronSchedule.Every(60_000L));
            when(store.load()).thenReturn(List.of(enabled, disabled));
            engine = new CronEngine(store, executor);
            engine.start();

            // 禁用任务不应安装调度，store.updateJob 只处理启用任务。
            // (scheduleJob writes next run time to state)
            verify(store, atLeastOnce()).updateJob(any(CronJob.class));
        }
    }

    @Nested
    class Listeners {

        @Test
        void emitsJobStartedAndCompleted() {
            engine = new CronEngine(store, executor);
            AtomicInteger started = new AtomicInteger();
            AtomicInteger completed = new AtomicInteger();
            engine.addListener(e -> {
                if (e instanceof CronEvent.JobStarted) {
                    started.incrementAndGet();
                } else if (e instanceof CronEvent.JobCompleted) {
                    completed.incrementAndGet();
                }
            });

            CronJob j = job("j1", true, new CronSchedule.Every(60_000L));
            when(store.getJob("j1")).thenReturn(Optional.of(j));
            when(executor.execute(j))
                    .thenReturn(new CronRunRecord("r1", "j1", 0L, 100L, RunStatus.SUCCESS, null, "done", 1));

            engine.triggerJob("j1");
            assertThat(started.get()).isEqualTo(1);
            assertThat(completed.get()).isEqualTo(1);
        }

        @Test
        void emitsJobFailedOnFailure() {
            engine = new CronEngine(store, executor);
            AtomicInteger failed = new AtomicInteger();
            engine.addListener(e -> {
                if (e instanceof CronEvent.JobFailed) {
                    failed.incrementAndGet();
                }
            });

            CronJob j = job("j1", true, new CronSchedule.Every(60_000L));
            when(store.getJob("j1")).thenReturn(Optional.of(j));
            when(executor.execute(j))
                    .thenReturn(new CronRunRecord("r1", "j1", 0L, 100L, RunStatus.FAILED, "boom", null, 0));

            engine.triggerJob("j1");
            assertThat(failed.get()).isEqualTo(1);
        }

        @Test
        void emitsJobFailedOnExceptionFromExecutor() {
            engine = new CronEngine(store, executor);
            AtomicInteger failed = new AtomicInteger();
            engine.addListener(e -> {
                if (e instanceof CronEvent.JobFailed) {
                    failed.incrementAndGet();
                }
            });

            CronJob j = job("j1", true, new CronSchedule.Every(60_000L));
            when(store.getJob("j1")).thenReturn(Optional.of(j));
            when(executor.execute(j)).thenThrow(new RuntimeException("oops"));

            CronRunRecord record = engine.triggerJob("j1");
            assertThat(failed.get()).isEqualTo(1);
            assertThat(record.status()).isEqualTo(RunStatus.FAILED);
            assertThat(record.error()).isEqualTo("oops");
        }

        @Test
        void removeListenerStopsNotifications() {
            engine = new CronEngine(store, executor);
            AtomicInteger count = new AtomicInteger();
            com.huawei.hicampus.mate.matecampusclaw.cron.engine.CronEventListener listener = e -> count.incrementAndGet();
            engine.addListener(listener);
            engine.removeListener(listener);

            CronJob j = job("j1", true, new CronSchedule.Every(60_000L));
            when(store.getJob("j1")).thenReturn(Optional.of(j));
            when(executor.execute(j))
                    .thenReturn(new CronRunRecord("r1", "j1", 0L, 100L, RunStatus.SUCCESS, null, null, 0));
            engine.triggerJob("j1");
            assertThat(count.get()).isZero();
        }

        @Test
        void listenerExceptionDoesNotInterrupt() {
            engine = new CronEngine(store, executor);
            AtomicInteger ok = new AtomicInteger();
            engine.addListener(e -> {
                throw new IllegalStateException("bad listener");
            });
            engine.addListener(e -> ok.incrementAndGet());

            CronJob j = job("j1", true, new CronSchedule.Every(60_000L));
            when(store.getJob("j1")).thenReturn(Optional.of(j));
            when(executor.execute(j))
                    .thenReturn(new CronRunRecord("r1", "j1", 0L, 100L, RunStatus.SUCCESS, null, null, 0));
            engine.triggerJob("j1");

            // 第一个监听器抛出异常后，第二个监听器仍应执行。
            assertThat(ok.get()).isGreaterThanOrEqualTo(1);
        }
    }

    @Nested
    class TriggerJob {

        @Test
        void unknownJobThrows() {
            engine = new CronEngine(store, executor);
            when(store.getJob("ghost")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> engine.triggerJob("ghost"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Job not found");
        }

        @Test
        void successUpdatesStateAndResetsErrors() {
            engine = new CronEngine(store, executor);
            CronJob j = job("j1", true, new CronSchedule.Every(60_000L), new CronJobState(0, 0, 0, "failed", 2, 5));
            when(store.getJob("j1")).thenReturn(Optional.of(j));
            when(executor.execute(j))
                    .thenReturn(new CronRunRecord("r1", "j1", 0L, 100L, RunStatus.SUCCESS, null, "done", 1));

            engine.triggerJob("j1");

            ArgumentCaptor<CronJob> captor = ArgumentCaptor.forClass(CronJob.class);
            verify(store, atLeast(2)).updateJob(captor.capture());
            CronJob lastWritten = captor.getValue();
            assertThat(lastWritten.state().consecutiveErrors()).isZero();
            assertThat(lastWritten.state().totalRuns()).isEqualTo(6);
            assertThat(lastWritten.state().lastRunStatus()).isEqualTo("success");
        }

        @Test
        void failureIncrementsConsecutiveErrors() {
            engine = new CronEngine(store, executor);
            CronJob j = job("j1", true, new CronSchedule.Every(60_000L), new CronJobState(0, 0, 0, null, 0, 0));
            when(store.getJob("j1")).thenReturn(Optional.of(j));
            when(executor.execute(j))
                    .thenReturn(new CronRunRecord("r1", "j1", 0L, 100L, RunStatus.FAILED, "boom", null, 0));

            engine.triggerJob("j1");

            ArgumentCaptor<CronJob> captor = ArgumentCaptor.forClass(CronJob.class);
            verify(store, atLeast(2)).updateJob(captor.capture());
            CronJob lastWritten = captor.getValue();
            assertThat(lastWritten.state().consecutiveErrors()).isEqualTo(1);
            assertThat(lastWritten.enabled()).isTrue();
        }

        @Test
        void threeConsecutiveErrorsAutoDisables() {
            engine = new CronEngine(store, executor);
            CronJob j = job("j1", true, new CronSchedule.Every(60_000L), new CronJobState(0, 0, 0, "failed", 2, 5));
            when(store.getJob("j1")).thenReturn(Optional.of(j));
            when(executor.execute(j))
                    .thenReturn(new CronRunRecord("r1", "j1", 0L, 100L, RunStatus.FAILED, "boom", null, 0));

            engine.triggerJob("j1");

            ArgumentCaptor<CronJob> captor = ArgumentCaptor.forClass(CronJob.class);
            verify(store, atLeast(2)).updateJob(captor.capture());
            CronJob lastWritten = captor.getValue();
            assertThat(lastWritten.enabled()).isFalse();
            assertThat(lastWritten.state().consecutiveErrors()).isEqualTo(3);
        }
    }

    @Nested
    class ComputeNextDelay {

        @Test
        void atScheduleInFutureReturnsPositive() {
            engine = new CronEngine(store, executor);
            long future = System.currentTimeMillis() + 60_000L;
            CronJob j = job("j1", true, new CronSchedule.At(future));
            long delay = engine.computeNextDelay(j);
            assertThat(delay).isPositive();
        }

        @Test
        void atScheduleInPastReturnsMinusOne() {
            engine = new CronEngine(store, executor);
            CronJob j = job("j1", true, new CronSchedule.At(0L));
            assertThat(engine.computeNextDelay(j)).isEqualTo(-1L);
        }

        @Test
        void everyScheduleHonorsBaseTime() {
            engine = new CronEngine(store, executor);
            long lastRun = System.currentTimeMillis();
            CronJob j =
                    job("j1", true, new CronSchedule.Every(60_000L), new CronJobState(0, 0, lastRun, "success", 0, 1));
            long delay = engine.computeNextDelay(j);

            // 下一次运行时间为 lastRun 加 interval，此处延迟约为 60 秒。
            assertThat(delay).isGreaterThan(50_000L);
        }

        @Test
        void everyWithConsecutiveErrorsAppliesBackoff() {
            engine = new CronEngine(store, executor);
            CronJob j = job("j1", true, new CronSchedule.Every(1000L), new CronJobState(0, 0, 0, "failed", 3, 5));
            long delay = engine.computeNextDelay(j);

            // 3 errors → 1000 * 2^3 = 8s backoff at minimum
            assertThat(delay).isGreaterThanOrEqualTo(7_000L);
        }

        @Test
        void cronExpressionValid() {
            engine = new CronEngine(store, executor);
            CronJob j = job("j1", true, new CronSchedule.CronExpr("0 0 * * * *", null));
            long delay = engine.computeNextDelay(j);

            // 到下一个整点的延迟不超过一小时。
            assertThat(delay).isBetween(0L, 3_600_000L);
        }

        @Test
        void cronExpressionInvalidReturnsMinusOne() {
            engine = new CronEngine(store, executor);
            CronJob j = job("j1", true, new CronSchedule.CronExpr("not a cron", null));
            assertThat(engine.computeNextDelay(j)).isEqualTo(-1L);
        }
    }

    @Nested
    class ScheduleJob {

        @Test
        void notRunningIsNoOp() {
            engine = new CronEngine(store, executor);
            CronJob j = job("j1", true, new CronSchedule.Every(60_000L));
            engine.scheduleJob(j);
            verify(store, never()).updateJob(any(CronJob.class));
        }

        @Test
        void disabledJobNotScheduled() {
            when(store.load()).thenReturn(List.of());
            engine = new CronEngine(store, executor);
            engine.start();
            CronJob j = job("j1", false, new CronSchedule.Every(60_000L));
            engine.scheduleJob(j);

            // 禁用任务不应更新状态。
            verify(store, never()).updateJob(j);
        }

        @Test
        void unscheduleMissingJobIsNoOp() {
            engine = new CronEngine(store, executor);

            // unscheduleJob 必须容忍从未调度的任务标识，未命中时直接返回而不是抛出 NPE。
            assertThatNoException().isThrownBy(() -> engine.unscheduleJob("nonexistent"));
        }
    }
}
