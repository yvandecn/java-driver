/*
 *      Copyright (C) 2012-2015 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.core;

/**
 * Hack to count the latencies of cancelled speculative executions in the latency-aware policy, but not in the
 * percentile tracker (this exception is excluded from measurement in the latter).
 */
class CancelledSpeculativeExecutionException extends Exception {

    static CancelledSpeculativeExecutionException INSTANCE = new CancelledSpeculativeExecutionException();

    private CancelledSpeculativeExecutionException() {
        super();
    }
}
