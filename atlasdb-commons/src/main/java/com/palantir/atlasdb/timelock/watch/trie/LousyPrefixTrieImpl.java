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

package com.palantir.atlasdb.timelock.watch.trie;

import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class LousyPrefixTrieImpl<T> implements PrefixTrie<T> {
    private static final Logger log = LoggerFactory.getLogger(LousyPrefixTrieImpl.class);

    private final Set<T> dataPresentHere;
    private final Map<Character, PrefixTrie<T>> children;

    public LousyPrefixTrieImpl() {
        dataPresentHere = Sets.newConcurrentHashSet();
        children = Maps.newConcurrentMap();
    }

    @Override
    public void add(String s, T data) {
        log.info("trie add {}", s);
        if (s.equals("")) {
            dataPresentHere.add(data);
            return;
        }

        PrefixTrie<T> child = children.computeIfAbsent(s.charAt(0), unused -> new LousyPrefixTrieImpl<>());
        child.add(s.substring(1), data);
    }

    @Override
    public Set<T> findDataInTrieWithKeysPrefixesOf(String s) {
        if (s.isEmpty()) {
            return dataPresentHere;
        }

        PrefixTrie<T> child = children.get(s.charAt(0));
        if (child == null) {
            return dataPresentHere;
        }
        Set<T> childData = child.findDataInTrieWithKeysPrefixesOf(s.substring(1));
        if (dataPresentHere.isEmpty()) {
            return childData;
        }
        return Sets.union(dataPresentHere, childData);
    }

    @Override
    public boolean isThereKeyPrefixOf(String s) {
        if (!dataPresentHere.isEmpty()) {
            return true;
        }

        if (s.isEmpty()) {
            // no direct hit, since if there was one it would have been caught above.
            return false;
        }

        PrefixTrie<T> child = children.get(s.charAt(0));
        if (child == null) {
            return false;
        }
        return child.isThereKeyPrefixOf(s.substring(1));
    }

    @Override
    public Set<T> findDataWithLongestMatchingPrefixOf(String s) {
        if (s.isEmpty()) {
            return dataPresentHere;
        }

        PrefixTrie<T> child = children.get(s.charAt(0));
        if (child == null) {
            return dataPresentHere;
        }
        Set<T> childData = child.findDataWithLongestMatchingPrefixOf(s.substring(1));
        if (childData.isEmpty()) {
            return dataPresentHere;
        }
        return childData;
    }
}
