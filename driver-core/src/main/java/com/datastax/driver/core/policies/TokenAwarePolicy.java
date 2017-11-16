/*
 * Copyright (C) 2012-2017 DataStax Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.driver.core.policies;

import com.datastax.driver.core.*;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * A wrapper load balancing policy that add token awareness to a child policy.
 * <p/>
 * This policy encapsulates another policy. The resulting policy works in
 * the following way:
 * <ul>
 * <li>the {@code distance} method is inherited from the child policy.</li>
 * <li>the iterator return by the {@code newQueryPlan} method will first
 * return the {@code LOCAL} replicas for the query (based on {@link Statement#getRoutingKey})
 * <i>if possible</i> (i.e. if the query {@code getRoutingKey} method
 * doesn't return {@code null} and if {@link Metadata#getReplicas}
 * returns a non empty set of replicas for that partition key). If no
 * local replica can be either found or successfully contacted, the rest
 * of the query plan will fallback to one of the child policy.</li>
 * </ul>
 * <p/>
 * Do note that only replica for which the child policy {@code distance}
 * method returns {@code HostDistance.LOCAL} will be considered having
 * priority. For example, if you wrap {@link DCAwareRoundRobinPolicy} with this
 * token aware policy, replicas from remote data centers may only be
 * returned after all the host of the local data center.
 */
public class TokenAwarePolicy implements ChainableLoadBalancingPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(TokenAwarePolicy.class);
    private static final boolean POWER_OF_TWO_CHOICES = Boolean.getBoolean("com.datastax.driver.POWER_OF_TWO_CHOICES");
    private static final int IGNORE_NEW_HOST_PERIOD_MILLIS = Integer.valueOf(System.getProperty("com.datastax.driver.IGNORE_NEW_HOST_PERIOD_MILLIS", "5000"));
    private static final int IGNORE_NEW_HOST_PROBA = Integer.valueOf(System.getProperty("com.datastax.driver.IGNORE_NEW_HOST_PROBA", "50"));

    private final Random ignoreHostRandom = new Random();

    static {
        if (POWER_OF_TWO_CHOICES) {
            LOG.info("Activating power of two choices");
        }
        if (IGNORE_NEW_HOST_PERIOD_MILLIS != 0) {
            LOG.info(String.format("Token-aware policy will lower load to hosts recently " +
                    "UP or ADDED for %d ms after they are detected UP or ADDED. " +
                    "To disable this start JVM with 'com.datastax.driver.IGNORE_NEW_HOST_PERIOD_MILLIS' set to 0.",
                    IGNORE_NEW_HOST_PERIOD_MILLIS)
            );
        }
    }

    private final LoadBalancingPolicy childPolicy;
    private final boolean shuffleReplicas;
    private volatile Cluster cluster;
    private volatile Metadata clusterMetadata;
    private volatile ProtocolVersion protocolVersion;
    private volatile CodecRegistry codecRegistry;

    private final Cache<Host, Long> newHostsEvents = CacheBuilder
            .newBuilder()
            .expireAfterWrite(IGNORE_NEW_HOST_PERIOD_MILLIS, TimeUnit.MILLISECONDS)
            .build();

    /**
     * Creates a new {@code TokenAware} policy.
     *
     * @param childPolicy     the load balancing policy to wrap with token awareness.
     * @param shuffleReplicas whether to shuffle the replicas returned by {@code getRoutingKey}.
     *                        Note that setting this parameter to {@code true} might decrease the
     *                        effectiveness of caching (especially at consistency level ONE), since
     *                        the same row will be retrieved from any replica (instead of only the
     *                        "primary" replica without shuffling).
     *                        On the other hand, shuffling will better distribute writes, and can
     *                        alleviate hotspots caused by "fat" partitions.
     */
    public TokenAwarePolicy(LoadBalancingPolicy childPolicy, boolean shuffleReplicas) {
        this.childPolicy = childPolicy;
        this.shuffleReplicas = shuffleReplicas;
    }

    /**
     * Creates a new {@code TokenAware} policy with shuffling of replicas.
     *
     * @param childPolicy the load balancing policy to wrap with token
     *                    awareness.
     * @see #TokenAwarePolicy(LoadBalancingPolicy, boolean)
     */
    public TokenAwarePolicy(LoadBalancingPolicy childPolicy) {
        this(childPolicy, true);
    }

    @Override
    public LoadBalancingPolicy getChildPolicy() {
        return childPolicy;
    }

    @Override
    public void init(Cluster cluster, Collection<Host> hosts) {
        this.cluster = cluster;
        clusterMetadata = cluster.getMetadata();
        protocolVersion = cluster.getConfiguration().getProtocolOptions().getProtocolVersion();
        codecRegistry = cluster.getConfiguration().getCodecRegistry();
        childPolicy.init(cluster, hosts);
    }

    /**
     * Return the HostDistance for the provided host.
     *
     * @param host the host of which to return the distance of.
     * @return the HostDistance to {@code host} as returned by the wrapped policy.
     */
    @Override
    public HostDistance distance(Host host) {
        return childPolicy.distance(host);
    }

    /**
     * Returns the hosts to use for a new query.
     * <p/>
     * The returned plan will first return replicas (whose {@code HostDistance}
     * for the child policy is {@code LOCAL}) for the query if it can determine
     * them (i.e. mainly if {@code statement.getRoutingKey()} is not {@code null}).
     * Following what it will return the plan of the child policy.
     *
     * @param statement the query for which to build the plan.
     * @return the new query plan.
     */
    @Override
    public Iterator<Host> newQueryPlan(final String loggedKeyspace, final Statement statement) {

        ByteBuffer partitionKey = statement.getRoutingKey(protocolVersion, codecRegistry);
        String keyspace = statement.getKeyspace();
        if (keyspace == null)
            keyspace = loggedKeyspace;

        if (partitionKey == null || keyspace == null)
            return childPolicy.newQueryPlan(keyspace, statement);

        final Set<Host> replicas = clusterMetadata.getReplicas(Metadata.quote(keyspace), partitionKey);
        if (replicas.isEmpty())
            return childPolicy.newQueryPlan(loggedKeyspace, statement);

        final Iterator<Host> iter = shuffle(replicas);

        return new AbstractIterator<Host>() {

            private Iterator<Host> childIterator;
            private List<Host> ignoredReplicas = Lists.newArrayList();

            @Override
            protected Host computeNext() {
                while (iter.hasNext()) {
                    Host host = iter.next();

                    if (IGNORE_NEW_HOST_PERIOD_MILLIS != 0) {
                        Long hostTimestamp = newHostsEvents.getIfPresent(host);
                        // should be evicted from the cache if present for more than the desired time
                        if (hostTimestamp != null) {
                            if (ignoreHostRandom.nextDouble() < (IGNORE_NEW_HOST_PROBA / 100.0)) {
                                if (LOG.isTraceEnabled()) {
                                    LOG.trace(String.format("Host [%s] was chosen for query " +
                                            "but will be put at the end of the query plan as it is still in a warm-up phase", host));
                                }
                                ignoredReplicas.add(host);
                                continue;
                            }
                        }
                    }

                    if (host.isUp() && childPolicy.distance(host) == HostDistance.LOCAL)
                        return host;
                }

                Iterator<Host> ignoredReplicasIterator = ignoredReplicas.iterator();
                while (ignoredReplicasIterator.hasNext()) {
                    Host host = ignoredReplicasIterator.next();

                    if (host.isUp() && childPolicy.distance(host) == HostDistance.LOCAL)
                        return host;
                }

                if (childIterator == null)
                    childIterator = childPolicy.newQueryPlan(loggedKeyspace, statement);

                while (childIterator.hasNext()) {
                    Host host = childIterator.next();
                    // Skip it if it was already a local replica
                    if (!replicas.contains(host) || childPolicy.distance(host) != HostDistance.LOCAL)
                        return host;
                }
                return endOfData();
            }
        };
    }

    private Iterator<Host> shuffle(Set<Host> replicas) {
        if (shuffleReplicas) {
            List<Host> l = Lists.newArrayList(replicas);
            Collections.shuffle(l);
            if (POWER_OF_TWO_CHOICES && l.size() > 2) {
                // Order the first two hosts by increasing load
                Host host0 = l.get(0);
                Host host1 = l.get(1);
                if (cluster.inFlight(host0) > cluster.inFlight(host1)) {
                    l.set(0, host1);
                    l.set(1, host0);
                }
            }
            return l.iterator();
        } else {
            return replicas.iterator();
        }
    }

    @Override
    public void onUp(Host host) {
        if (IGNORE_NEW_HOST_PERIOD_MILLIS != 0) {
            LOG.debug(String.format("Host [%s] has just been detected up, will be " +
                            "considered 'warming-up' for %d ms.",
                    host,
                    IGNORE_NEW_HOST_PERIOD_MILLIS)
            );
            newHostsEvents.put(host, System.currentTimeMillis());
        }
        childPolicy.onUp(host);
    }

    @Override
    public void onDown(Host host) {
        if (IGNORE_NEW_HOST_PERIOD_MILLIS != 0) {
            newHostsEvents.invalidate(host);
        }
        childPolicy.onDown(host);
    }

    @Override
    public void onAdd(Host host) {
        if (IGNORE_NEW_HOST_PERIOD_MILLIS != 0) {
            LOG.debug(String.format("Host [%s] has just been detected up, will be " +
                    "considered 'warming-up' for %d ms.",
                    host,
                    IGNORE_NEW_HOST_PERIOD_MILLIS)
            );
            newHostsEvents.put(host, System.currentTimeMillis());
        }
        childPolicy.onAdd(host);
    }

    @Override
    public void onRemove(Host host) {
        if (IGNORE_NEW_HOST_PERIOD_MILLIS != 0) {
            newHostsEvents.invalidate(host);
        }
        childPolicy.onRemove(host);
    }

    @Override
    public void close() {
        childPolicy.close();
    }
}
