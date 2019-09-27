/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.http;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.palantir.common.concurrent.NamedThreadFactory;
import com.palantir.common.concurrent.PTExecutors;
import com.palantir.conjure.java.ext.refresh.Refreshable;
import com.palantir.logsafe.UnsafeArg;

/**
 * A PollingRefreshable serves as a bridge between a {@link Supplier} and {@link Refreshable}, polling for changes
 * in the value of the Supplier and, if detecting a change, pushing it to the linked Refreshable, after possibly
 * applying a transform to the value.
 *
 * @param <T> type of the value supplied
 * @param <U> type of the Refreshable
 */
public final class PollingRefreshable<T, U> implements AutoCloseable {
    @VisibleForTesting
    static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(5L);

    private static final Logger log = LoggerFactory.getLogger(PollingRefreshable.class);

    private final Supplier<T> supplier;
    private final Duration refreshInterval;
    private final ScheduledExecutorService poller;

    private final Function<T, U> transform;
    private final Refreshable<U> refreshable = Refreshable.empty();

    private T lastSeenValue = null;

    private PollingRefreshable(Supplier<T> supplier,
            Duration refreshInterval,
            Function<T, U> transform,
            ScheduledExecutorService poller) {
        Preconditions.checkArgument(!refreshInterval.isNegative() && !refreshInterval.isZero(),
                "Refresh interval must be positive, but found %s", refreshInterval);

        this.supplier = supplier;
        this.refreshInterval = refreshInterval;
        this.transform = transform;
        this.poller = poller;

        try {
            lastSeenValue = supplier.get();
            refreshable.set(transform.apply(lastSeenValue));
        } catch (Exception e) {
            log.info("Exception occurred in supplier when trying to populate the initial value.", e);
        }
    }

    public static <T> PollingRefreshable<T, T> create(Supplier<T> supplier) {
        return create(supplier, DEFAULT_REFRESH_INTERVAL);
    }

    public static <T> PollingRefreshable<T, T> create(Supplier<T> supplier, Duration refreshInterval) {
        return createComposed(supplier, refreshInterval, Function.identity());
    }

    public static <T, U> PollingRefreshable<T, U> createComposed(Supplier<T> supplier, Duration refreshInterval,
            Function<T, U> transform) {
        return createWithPoller(supplier, refreshInterval, transform,
                PTExecutors.newSingleThreadScheduledExecutor(new NamedThreadFactory("polling-refreshable", true)));
    }

    @VisibleForTesting
    static <T, U> PollingRefreshable<T, U> createWithPoller(
            Supplier<T> supplier,
            Duration refreshInterval,
            Function<T, U> transform,
            ScheduledExecutorService poller) {
        PollingRefreshable<T, U> pollingRefreshable = new PollingRefreshable<>(supplier, refreshInterval, transform,
                poller);
        pollingRefreshable.scheduleUpdates();
        return pollingRefreshable;
    }

    public Refreshable<U> getRefreshable() {
        return refreshable;
    }

    private void scheduleUpdates() {
        poller.scheduleAtFixedRate(() -> {
            try {
                T value = supplier.get();
                if (!value.equals(lastSeenValue)) {
                    lastSeenValue = value;
                    refreshable.set(transform.apply(lastSeenValue));
                }
            } catch (Exception e) {
                log.info("Exception occurred in supplier when trying to update our PollingRefreshable."
                                + " The last value we saw was {}.",
                        UnsafeArg.of("currentValue", lastSeenValue),
                        e);
            }
        }, refreshInterval.getSeconds(), refreshInterval.getSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        poller.shutdown();
    }
}
