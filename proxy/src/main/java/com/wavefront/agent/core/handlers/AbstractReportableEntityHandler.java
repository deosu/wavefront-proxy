package com.wavefront.agent.core.handlers;

import com.google.common.util.concurrent.RateLimiter;
import com.wavefront.agent.core.queues.QueueInfo;
import com.wavefront.agent.core.senders.SenderTask;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.*;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jetbrains.annotations.NotNull;

/**
 * Base class for all {@link ReportableEntityHandler} implementations.
 *
 * @author vasily@wavefront.com
 * @param <T> the type of input objects handled
 * @param <U> the type of the output object as handled by {@link SenderTask}
 */
abstract class AbstractReportableEntityHandler<T, U> implements ReportableEntityHandler<T, U> {
  protected static final MetricsRegistry LOCAL_REGISTRY = new MetricsRegistry();
  protected static final String MULTICASTING_TENANT_TAG_KEY = "multicastingTenantName";
  private static final Logger logger =
      Logger.getLogger(AbstractReportableEntityHandler.class.getCanonicalName());
  final QueueInfo handlerKey;
  final String handler;

  @SuppressWarnings("UnstableApiUsage")
  final RateLimiter blockedItemsLimiter;

  final Function<T, String> serializer;
  final boolean reportReceivedStats;
  final String rateUnit;
  final BurstRateTrackingCounter receivedStats;
  private final Logger blockedItemsLogger;
  private final Counter receivedCounter;
  private final Counter attemptedCounter;
  private final Counter blockedCounter;
  private final Counter rejectedCounter;
  private final Timer timer;

  @SuppressWarnings("UnstableApiUsage")
  private final RateLimiter noDataStatsRateLimiter = RateLimiter.create(1.0d / 60);

  /**
   * @param handlerKey metrics pipeline key (entity type + port number)
   * @param blockedItemsPerBatch controls sample rate of how many blocked points are written into
   *     the main log file.
   * @param serializer helper function to convert objects to string. Used when writing blocked
   *     points to logs.
   * @param reportReceivedStats Whether we should report a .received counter metric.
   * @param blockedItemsLogger a {@link Logger} instance for blocked items
   */
  AbstractReportableEntityHandler(
      String handler,
      @NotNull QueueInfo handlerKey,
      final int blockedItemsPerBatch,
      final Function<T, String> serializer,
      boolean reportReceivedStats,
      @Nullable final Logger blockedItemsLogger) {
    this.handler = handler;
    this.handlerKey = handlerKey;
    //noinspection UnstableApiUsage
    this.blockedItemsLimiter =
        blockedItemsPerBatch == 0 ? null : RateLimiter.create(blockedItemsPerBatch / 10d);
    this.serializer = serializer;
    this.reportReceivedStats = reportReceivedStats;
    this.rateUnit = handlerKey.getEntityType().getRateUnit();
    this.blockedItemsLogger = blockedItemsLogger;

    MetricsRegistry registry = reportReceivedStats ? Metrics.defaultRegistry() : LOCAL_REGISTRY;
    String metricPrefix = this.handler + "." + handlerKey.getName();
    MetricName receivedMetricName = new MetricName(metricPrefix, "", "received");
    this.receivedCounter = registry.newCounter(receivedMetricName);
    this.attemptedCounter = Metrics.newCounter(new MetricName(metricPrefix, "", "sent"));
    this.blockedCounter = registry.newCounter(new MetricName(metricPrefix, "", "blocked"));
    this.rejectedCounter = registry.newCounter(new MetricName(metricPrefix, "", "rejected"));
    this.receivedStats = new BurstRateTrackingCounter(receivedMetricName, registry, 1000);

    // TODO: bring back this metric
    //    this.deliveredStats = new BurstRateTrackingCounter(deliveredMetricName, registry, 1000);

    registry.newGauge(
        new MetricName(metricPrefix + ".received", "", "max-burst-rate"),
        new Gauge<Double>() {
          @Override
          public Double value() {
            return receivedStats.getMaxBurstRateAndClear();
          }
        });

    timer = new Timer("stats-output-" + handlerKey.getName() + "." + this.handler);
    timer.scheduleAtFixedRate(
        new TimerTask() {
          @Override
          public void run() {
            printStats();
          }
        },
        10_000,
        10_000);
    if (reportReceivedStats) {
      timer.scheduleAtFixedRate(
          new TimerTask() {
            @Override
            public void run() {
              printTotal();
            }
          },
          60_000,
          60_000);
    }
  }

  @Override
  public void reject(@Nullable T item, @Nullable String message) {
    blockedCounter.inc();
    rejectedCounter.inc();
    if (item != null && blockedItemsLogger != null) {
      blockedItemsLogger.warning(serializer.apply(item));
    }
    //noinspection UnstableApiUsage
    if (message != null && blockedItemsLimiter != null && blockedItemsLimiter.tryAcquire()) {
      logger.info("[" + this.handler + "] blocked input: [" + message + "]");
    }
  }

  @Override
  public void reject(@Nonnull String line, @Nullable String message) {
    blockedCounter.inc();
    rejectedCounter.inc();
    if (blockedItemsLogger != null) blockedItemsLogger.warning(line);
    //noinspection UnstableApiUsage
    if (message != null && blockedItemsLimiter != null && blockedItemsLimiter.tryAcquire()) {
      logger.info("[" + this.handler + "] blocked input: [" + message + "]");
    }
  }

  @Override
  public void block(T item) {
    blockedCounter.inc();
    if (blockedItemsLogger != null) {
      blockedItemsLogger.info(serializer.apply(item));
    }
  }

  @Override
  public void block(@Nullable T item, @Nullable String message) {
    blockedCounter.inc();
    if (item != null && blockedItemsLogger != null) {
      blockedItemsLogger.info(serializer.apply(item));
    }
    if (message != null && blockedItemsLogger != null) {
      blockedItemsLogger.info(message);
    }
  }

  @Override
  public void report(T item) {
    try {
      reportInternal(item);
    } catch (IllegalArgumentException e) {
      this.reject(item, e.getMessage() + " (" + serializer.apply(item) + ")");
    } catch (Exception ex) {
      logger.log(
          Level.SEVERE,
          "WF-500 Uncaught exception when handling input (" + serializer.apply(item) + ")",
          ex);
    }
  }

  @Override
  public void shutdown() {
    if (this.timer != null) timer.cancel();
  }

  abstract void reportInternal(T item);

  protected Counter getReceivedCounter() {
    return receivedCounter;
  }

  protected void printStats() {
    // if we received no data over the last 5 minutes, only print stats once a minute
    //noinspection UnstableApiUsage
    if (receivedStats.getFiveMinuteCount() == 0 && !noDataStatsRateLimiter.tryAcquire()) return;
    if (reportReceivedStats) {
      logger.info(
          "["
              + this.handler
              + "] "
              + handlerKey.getEntityType().toCapitalizedString()
              + " received rate: "
              + receivedStats.getOneMinutePrintableRate()
              + " "
              + rateUnit
              + " (1 min), "
              + receivedStats.getFiveMinutePrintableRate()
              + " "
              + rateUnit
              + " (5 min), "
              + receivedStats.getCurrentRate()
              + " "
              + rateUnit
              + " (current).");
    }
    //    if (deliveredStats.getFiveMinuteCount() == 0) return;
    //    logger.info(
    //        "["
    //            + this.port
    //            + "] "
    //            + handlerKey.getEntityType().toCapitalizedString()
    //            + " delivered rate: "
    //            + deliveredStats.getOneMinutePrintableRate()
    //            + " "
    //            + rateUnit
    //            + " (1 min), "
    //            + deliveredStats.getFiveMinutePrintableRate()
    //            + " "
    //            + rateUnit
    //            + " (5 min)");
    // we are not going to display current delivered rate because it _will_ be misinterpreted.
  }

  // TODO: review
  protected void printTotal() {
    logger.info(
        "["
            + this.handler
            + "] "
            + handlerKey.getEntityType().toCapitalizedString()
            + " processed since start: "
            + this.attemptedCounter.count()
            + "; blocked: "
            + this.blockedCounter.count());
  }
}
