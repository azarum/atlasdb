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

package com.palantir.atlasdb.keyvalue.api;

import org.immutables.value.Value.Immutable;
import org.immutables.value.Value.Lazy;

@Immutable
public interface TimestampRangeDelete {
    long timestamp();
    boolean endInclusive();
    boolean deleteSentinels();

    @Lazy
    default long maxTimestampToDelete() {
        return timestamp() + (endInclusive() ? 0 : -1);
    }

    @Lazy
    default long minTimestampToDelete() {
        return deleteSentinels() ? Value.INVALID_VALUE_TIMESTAMP : Value.INVALID_VALUE_TIMESTAMP + 1;
    }

    class Builder extends ImmutableTimestampRangeDelete.Builder {}
}