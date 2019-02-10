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

package com.palantir.lock.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.palantir.common.time.NanoTime;

public class LeasableLockResponseTest {
    private static final LockToken LOCK_TOKEN = LockToken.of(UUID.randomUUID());
    private static final Lease LEASE = Lease.of(LeadershipId.random(), NanoTime.createForTests(0), Duration.ofSeconds(1));
    private static final RuntimeException EXCEPTION = new RuntimeException("failure!");
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    public void visitsSuccessfulResponse() {
        LeasableLockResponse response = LeasableLockResponse.successful(LOCK_TOKEN, LEASE);
        LockToken token = response.accept(LeasableLockResponse.Visitor.of(
                successful -> successful.getToken(),
                unsuccessful -> {
                    throw EXCEPTION;
                }));

        assertThat(token).isEqualTo(LOCK_TOKEN);
    }

    @Test
    public void visitsUnsuccessfulResponse() {
        LeasableLockResponse response = LeasableLockResponse.timedOut();

        assertThatThrownBy(() -> response.accept(LeasableLockResponse.Visitor.of(
                successful -> successful.getToken(),
                unsuccessful -> {
                    throw EXCEPTION;
                }
        ))).isEqualTo(EXCEPTION);
    }

    @Test
    public void serializeDeserialize_Successful() throws Exception {
        LeasableLockResponse response = LeasableLockResponse.successful(LOCK_TOKEN, LEASE);
        String serialized = objectMapper.writeValueAsString(response);
        LeasableLockResponse deserialized = objectMapper.readValue(serialized, LeasableLockResponse.class);
        assertThat(deserialized).isEqualTo(response);
    }

    @Test
    public void serializeDeserialize_Unsuccessful() throws Exception {
        LeasableLockResponse response = LeasableLockResponse.timedOut();
        String serialized = objectMapper.writeValueAsString(response);
        LeasableLockResponse deserialized = objectMapper.readValue(serialized, LeasableLockResponse.class);
        assertThat(deserialized).isEqualTo(response);
    }
}
