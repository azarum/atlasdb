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

package com.palantir.atlasdb.config;

import org.immutables.value.Value;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * Client configuration options for how an AtlasDB client connects to remote services (e.g. TimeLock) that are
 * intended to be exposed directly to users for configuration.
 */
@JsonSerialize(as = ImmutableRemotingClientConfig.class)
@JsonDeserialize(as = ImmutableRemotingClientConfig.class)
@JsonIgnoreProperties({
        "maximumConjureRemotingProbability",
        "enableLegacyClientFallback"})
@Value.Immutable
public interface RemotingClientConfig {}
