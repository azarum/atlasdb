/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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
package com.palantir.atlasdb.timelock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Ints;
import com.palantir.atlasdb.http.v2.ClientOptions;
import com.palantir.atlasdb.timelock.api.ConjureLockRequest;
import com.palantir.atlasdb.timelock.api.ConjureLockResponse;
import com.palantir.atlasdb.timelock.api.ConjureLockToken;
import com.palantir.atlasdb.timelock.api.ConjureUnlockRequest;
import com.palantir.atlasdb.timelock.api.SuccessfulLockResponse;
import com.palantir.atlasdb.timelock.api.UnsuccessfulLockResponse;
import com.palantir.atlasdb.timelock.suite.MultiLeaderPaxosSuite;
import com.palantir.atlasdb.timelock.util.ExceptionMatchers;
import com.palantir.atlasdb.timelock.util.ParameterInjector;
import com.palantir.lock.LockDescriptor;
import com.palantir.lock.StringLockDescriptor;
import com.palantir.lock.client.ConjureLockRequests;
import com.palantir.lock.v2.LeaderTime;
import com.palantir.lock.v2.LockRequest;
import com.palantir.lock.v2.LockResponse;
import com.palantir.lock.v2.LockToken;
import com.palantir.lock.v2.WaitForLocksRequest;
import com.palantir.lock.v2.WaitForLocksResponse;

@RunWith(Parameterized.class)
public class MultiNodePaxosTimeLockServerIntegrationTest {

    @ClassRule
    public static ParameterInjector<TestableTimelockCluster> injector =
            ParameterInjector.withFallBackConfiguration(() -> MultiLeaderPaxosSuite.MULTI_LEADER_PAXOS);

    @Parameterized.Parameter
    public TestableTimelockCluster cluster;

    @Parameterized.Parameters(name = "{0}")
    public static Iterable<TestableTimelockCluster> params() {
        return injector.getParameter();
    }

    private static final LockDescriptor LOCK = StringLockDescriptor.of("foo");
    private static final Set<LockDescriptor> LOCKS = ImmutableSet.of(LOCK);

    private static final int DEFAULT_LOCK_TIMEOUT_MS = 10_000;
    private static final int LONG_LOCK_TIMEOUT_MS =
            Ints.saturatedCast(ClientOptions.NON_BLOCKING_READ_TIMEOUT.plus(Duration.ofSeconds(1)).toMillis());

    private NamespacedClients client;

    @Before
    public void bringAllNodesOnline() {
        client = cluster.clientForRandomNamespace();
        cluster.waitUntilAllServersOnlineAndReadyToServeNamespaces(ImmutableList.of(client.namespace()));
    }

    @Test
    public void nonLeadersReturn503() {
        cluster.nonLeaders(client.namespace()).forEach((namespace, server) -> {
            assertThatThrownBy(() -> server.client(namespace).getFreshTimestamp())
                    .satisfies(ExceptionMatchers::isRetryableExceptionWhereLeaderCannotBeFound);
            assertThatThrownBy(() ->
                    server.client(namespace).lock(LockRequest.of(LOCKS, DEFAULT_LOCK_TIMEOUT_MS)))
                    .satisfies(ExceptionMatchers::isRetryableExceptionWhereLeaderCannotBeFound);
        });
    }


    @Test
    public void nonLeadersReturn503_conjure() {
        cluster.nonLeaders(client.namespace()).forEach((namespace, server) -> {
            assertThatThrownBy(() -> server.client(namespace).namespacedConjureTimelockService().leaderTime())
                    .satisfies(ExceptionMatchers::isRetryableExceptionWhereLeaderCannotBeFound);
        });
    }

    @Test
    public void leaderRespondsToRequests() {
        NamespacedClients currentLeader = cluster.currentLeaderFor(client.namespace())
                .client(client.namespace());
        currentLeader.getFreshTimestamp();

        LockToken token = currentLeader.lock(LockRequest.of(LOCKS, DEFAULT_LOCK_TIMEOUT_MS)).getToken();
        currentLeader.unlock(token);
    }

    @Test
    public void newLeaderTakesOverIfCurrentLeaderDies() {
        cluster.currentLeaderFor(client.namespace()).killSync();

        assertThatCode(client::getFreshTimestamp)
                .doesNotThrowAnyException();
    }

    @Test
    public void canUseNamespaceStartingWithTlOnLegacyEndpoints() {
        cluster.client("tl" + "suffix").getFreshTimestamp();
    }

    @Test
    public void leaderLosesLeadershipIfQuorumIsNotAlive() throws ExecutionException {
        NamespacedClients leader = cluster.currentLeaderFor(client.namespace())
                .client(client.namespace());
        cluster.killAndAwaitTermination(cluster.nonLeaders(client.namespace()).values());

        assertThatThrownBy(leader::getFreshTimestamp)
                .satisfies(ExceptionMatchers::isRetryableExceptionWhereLeaderCannotBeFound);
    }

    @Test
    public void someoneBecomesLeaderAgainAfterQuorumIsRestored() throws ExecutionException {
        Set<TestableTimelockServer> nonLeaders = ImmutableSet.copyOf(cluster.nonLeaders(client.namespace()).values());
        cluster.killAndAwaitTermination(nonLeaders);

        nonLeaders.forEach(TestableTimelockServer::start);
        client.getFreshTimestamp();
    }

    @Test
    public void canHostilelyTakeOverNamespace() {
        TestableTimelockServer currentLeader = cluster.currentLeaderFor(client.namespace());
        TestableTimelockServer nonLeader = Iterables.get(
                cluster.nonLeaders(client.namespace()).get(client.namespace()), 0);

        assertThatThrownBy(nonLeader.client(client.namespace())::getFreshTimestamp)
                .as("non leader is not the leader before the takeover - sanity check")
                .satisfies(ExceptionMatchers::isRetryableExceptionWhereLeaderCannotBeFound);

        assertThat(nonLeader.takeOverLeadershipForNamespace(client.namespace()))
                .as("successfully took over namespace")
                .isTrue();

        assertThatThrownBy(currentLeader.client(client.namespace())::getFreshTimestamp)
                .as("previous leader is no longer the leader after the takeover")
                .satisfies(ExceptionMatchers::isRetryableExceptionWhereLeaderCannotBeFound);

        assertThat(cluster.currentLeaderFor(client.namespace()))
                .as("new leader is the previous non leader (hostile takeover)")
                .isEqualTo(nonLeader);
    }

    @Test
    public void canPerformRollingRestart() {
        bringAllNodesOnline();
        for (TestableTimelockServer server : cluster.servers()) {
            server.killSync();
            cluster.waitUntilAllServersOnlineAndReadyToServeNamespaces(ImmutableList.of(client.namespace()));
            client.getFreshTimestamp();
            server.start();
        }
    }

    @Test
    public void timestampsAreIncreasingAcrossFailovers() {
        long lastTimestamp = client.getFreshTimestamp();

        for (int i = 0; i < 3; i++) {
            cluster.failoverToNewLeader(client.namespace());

            long timestamp = client.getFreshTimestamp();
            assertThat(timestamp).isGreaterThan(lastTimestamp);
            lastTimestamp = timestamp;
        }
    }

    @Test
    public void leaderIdChangesAcrossFailovers() {
        Set<LeaderTime> leaderTimes = new HashSet<>();
        leaderTimes.add(client.namespacedConjureTimelockService().leaderTime());

        for (int i = 0; i < 3; i++) {
            cluster.failoverToNewLeader(client.namespace());

            LeaderTime leaderTime = client.namespacedConjureTimelockService().leaderTime();

            leaderTimes.forEach(previousLeaderTime ->
                    assertThat(previousLeaderTime.isComparableWith(leaderTime)).isFalse());
            leaderTimes.add(leaderTime);
        }
    }

    @Test
    public void locksAreInvalidatedAcrossFailovers() {
        LockToken token = client.lock(LockRequest.of(LOCKS, DEFAULT_LOCK_TIMEOUT_MS)).getToken();

        for (int i = 0; i < 3; i++) {
            cluster.failoverToNewLeader(client.namespace());

            assertThat(client.unlock(token)).isFalse();
            token = client.lock(LockRequest.of(LOCKS, DEFAULT_LOCK_TIMEOUT_MS)).getToken();
        }
    }

    @Test
    public void canCreateNewClientsDynamically() {
        for (int i = 0; i < 5; i++) {
            NamespacedClients randomNamespace = cluster.clientForRandomNamespace();

            randomNamespace.getFreshTimestamp();
            LockToken token = randomNamespace.lock(LockRequest.of(LOCKS, DEFAULT_LOCK_TIMEOUT_MS)).getToken();
            randomNamespace.unlock(token);
        }
    }

    @Test
    public void lockRequestCanBlockForTheFullTimeout() {
        LockToken token = client.lock(LockRequest.of(LOCKS, DEFAULT_LOCK_TIMEOUT_MS)).getToken();

        try {
            LockResponse response = client.lock(LockRequest.of(LOCKS, LONG_LOCK_TIMEOUT_MS));
            assertThat(response.wasSuccessful()).isFalse();
        } finally {
            client.unlock(token);
        }
    }

    @Test
    public void waitForLocksRequestCanBlockForTheFullTimeout() {
        LockToken token = client.lock(LockRequest.of(LOCKS, DEFAULT_LOCK_TIMEOUT_MS)).getToken();

        try {
            WaitForLocksResponse response = client.waitForLocks(WaitForLocksRequest.of(LOCKS, LONG_LOCK_TIMEOUT_MS));
            assertThat(response.wasSuccessful()).isFalse();
        } finally {
            client.unlock(token);
        }
    }

    @Test
    public void multipleLockRequestsWithTheSameIdAreGranted() {
        ConjureLockRequest conjureLockRequest = ConjureLockRequests.toConjure(
                LockRequest.of(LOCKS, DEFAULT_LOCK_TIMEOUT_MS));

        Optional<ConjureLockToken> token1 = client.namespacedConjureTimelockService().lock(conjureLockRequest)
                .accept(ToConjureLockTokenVisitor.INSTANCE);
        Optional<ConjureLockToken> token2 = Optional.empty();
        try {
            token2 = client.namespacedConjureTimelockService().lock(conjureLockRequest)
                    .accept(ToConjureLockTokenVisitor.INSTANCE);

            assertThat(token1).isPresent();
            assertThat(token1).isEqualTo(token2);
        } finally {
            Set<ConjureLockToken> tokens = Stream.of(token1, token2)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toSet());
            client.namespacedConjureTimelockService().unlock(ConjureUnlockRequest.of(tokens));
        }
    }

    private enum ToConjureLockTokenVisitor implements ConjureLockResponse.Visitor<Optional<ConjureLockToken>> {
        INSTANCE;

        @Override
        public Optional<ConjureLockToken> visitSuccessful(SuccessfulLockResponse value) {
            return Optional.of(value.getLockToken());
        }

        @Override
        public Optional<ConjureLockToken> visitUnsuccessful(UnsuccessfulLockResponse value) {
            return Optional.empty();
        }

        @Override
        public Optional<ConjureLockToken> visitUnknown(String unknownType) {
            throw new RuntimeException("Unexpected type " + unknownType);
        }
    }
}
