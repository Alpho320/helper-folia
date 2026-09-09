/*
 * This file is part of helper, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.helper.redis.plugin;

import com.google.common.reflect.TypeToken;

import me.lucko.helper.Schedulers;
import me.lucko.helper.messaging.AbstractMessenger;
import me.lucko.helper.messaging.Channel;
import me.lucko.helper.redis.Redis;
import me.lucko.helper.redis.RedisCredentials;
import me.lucko.helper.terminable.composite.CompositeTerminable;
import me.lucko.helper.utils.Log;

import redis.clients.jedis.BinaryJedisPubSub;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nonnull;

public class HelperRedis implements Redis {

    private final JedisPool jedisPool;
    private final AbstractMessenger messenger;

    // Mutated from the messenger's subscribe/unsubscribe callbacks (any thread) while the resubscribe
    // watchdog below iterates it. A plain HashSet here could throw ConcurrentModificationException,
    // or worse, silently drop a channel registration.
    private final Set<String> channels = ConcurrentHashMap.newKeySet();
    private final CompositeTerminable registry = CompositeTerminable.create();

    /** First reconnect attempt lands inside one tick, matching the old scheduler-based retry. */
    private static final long RECONNECT_MIN_BACKOFF_MS = 50L;
    private static final long RECONNECT_MAX_BACKOFF_MS = 2000L;
    /** A subscription that lived at least this long counts as healthy; the backoff resets. */
    private static final long RECONNECT_BACKOFF_RESET_MS = 30_000L;

    private volatile PubSubListener listener = null;
    private volatile boolean closed = false;
    private final Thread subscriberThread;

    public HelperRedis(@Nonnull RedisCredentials credentials) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(16);

        // setup jedis
        if (credentials.getPassword().trim().isEmpty()) {
            this.jedisPool = new JedisPool(config, credentials.getAddress(), credentials.getPort());
        } else {
            this.jedisPool = new JedisPool(config, credentials.getAddress(), credentials.getPort(), 2000, credentials.getPassword());
        }

        try (Jedis jedis = this.jedisPool.getResource()) {
            jedis.ping();
        }

        // Jedis#subscribe does not return: it parks on a socket read for the lifetime of the
        // connection. Running it on Schedulers.async() therefore did not "schedule a task", it
        // permanently consumed one thread of the shared helper pool - a pool every plugin on the
        // server draws from for its own async work. It gets its own thread instead, so nothing is
        // taken away from the scheduler, and the retry loop lives on that thread rather than
        // re-queueing itself into the pool.
        this.subscriberThread = new Thread(this::runSubscriberLoop, "helper-redis-subscriber");
        this.subscriberThread.setDaemon(true);

        Schedulers.async().runRepeating(() -> {
            // ensure subscribed to all channels
            PubSubListener listener = HelperRedis.this.listener;

            if (listener == null || !listener.isSubscribed()) {
                return;
            }

            // Fast path for the overwhelmingly common case: this watchdog runs every 2 ticks
            // forever, and once everything is subscribed each pass was still taking the listener
            // lock once per channel to discover it had nothing to do.
            if (listener.hasSubscribedTo(this.channels)) {
                return;
            }

            for (String channel : this.channels) {
                listener.subscribe(channel.getBytes(StandardCharsets.UTF_8));
            }

        }, 2L, 2L).bindWith(this.registry);

        this.messenger = new AbstractMessenger(
                (channel, message) -> {
                    try (Jedis jedis = getJedis()) {
                        jedis.publish(channel.getBytes(StandardCharsets.UTF_8), message);
                    }
                },
                channel -> {
                    Log.info("[helper-redis] Subscribing to channel: " + channel);
                    this.channels.add(channel);
                    // The listener is null until the subscriber thread has its connection, and it
                    // goes null again while a broken connection is being retried. The watchdog above
                    // picks the channel up from the set, so a missing listener is not an error here.
                    PubSubListener listener = this.listener;
                    if (listener != null) {
                        listener.subscribe(channel.getBytes(StandardCharsets.UTF_8));
                    }
                },
                channel -> {
                    Log.info("[helper-redis] Unsubscribing from channel: " + channel);
                    this.channels.remove(channel);
                    PubSubListener listener = this.listener;
                    if (listener != null) {
                        listener.unsubscribe(channel.getBytes(StandardCharsets.UTF_8));
                    }
                }
        );

        // Started last, on purpose: onMessage dereferences `messenger`, so the thread must not be
        // able to run before that field is assigned.
        this.subscriberThread.start();
    }

    /**
     * Blocking subscribe loop, owned by {@link #subscriberThread}.
     *
     * <p>Each pass takes a connection and blocks inside {@code jedis.subscribe} until the connection
     * drops or {@link #close()} unsubscribes it. On an unexpected failure it waits a second and
     * reconnects, so a redis restart heals itself.</p>
     */
    private void runSubscriberLoop() {
        boolean retrying = false;
        long backoffMs = RECONNECT_MIN_BACKOFF_MS;

        while (!this.closed) {
            if (retrying) {
                Log.info("[helper-redis] Retrying subscription...");
                retrying = false;
            }

            long subscribedAt = System.currentTimeMillis();
            try (Jedis jedis = getJedis()) {
                PubSubListener listener = new PubSubListener();
                this.listener = listener;
                jedis.subscribe(listener, "helper-redis-dummy".getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                if (this.closed) {
                    break;
                }
                new RuntimeException("Error subscribing to listener", e).printStackTrace();
                PubSubListener listener = this.listener;
                if (listener != null) {
                    try {
                        listener.unsubscribe();
                    } catch (Exception ignored) {
                        // the connection is already gone; nothing to unsubscribe from
                    }
                }
                retrying = true;
            } finally {
                this.listener = null;
            }

            if (this.closed) {
                break;
            }

            // A connection that stayed up is not evidence of a broken redis, so start the next
            // reconnect from the minimum again.
            if (System.currentTimeMillis() - subscribedAt >= RECONNECT_BACKOFF_RESET_MS) {
                backoffMs = RECONNECT_MIN_BACKOFF_MS;
            }

            // Reconnect quickly - the first retry lands within a tick, as it did before - but back
            // off if redis is actually down, so a dead server is not hammered in a tight loop.
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            backoffMs = Math.min(backoffMs * 2, RECONNECT_MAX_BACKOFF_MS);
        }
    }

    @Nonnull
    @Override
    public JedisPool getJedisPool() {
        Objects.requireNonNull(this.jedisPool, "jedisPool");
        return this.jedisPool;
    }

    @Nonnull
    @Override
    public Jedis getJedis() {
        return getJedisPool().getResource();
    }

    @Override
    public void close() throws Exception {
        this.closed = true;

        PubSubListener listener = this.listener;
        if (listener != null) {
            listener.unsubscribe();
            this.listener = null;
        }

        // Unsubscribing releases the blocking read, so the loop sees `closed` and exits on its own.
        // Interrupt as a fallback in case the connection is already wedged.
        if (this.subscriberThread != null) {
            this.subscriberThread.interrupt();
        }

        if (this.jedisPool != null) {
            this.jedisPool.close();
        }

        this.registry.close();
    }

    @Nonnull
    @Override
    public <T> Channel<T> getChannel(@Nonnull String name, @Nonnull TypeToken<T> type) {
        return this.messenger.getChannel(name, type);
    }

    private final class PubSubListener extends BinaryJedisPubSub {
        private final ReentrantLock lock = new ReentrantLock();
        private final Set<String> subscribed = ConcurrentHashMap.newKeySet();

        @Override
        public void subscribe(byte[]... channels) {
            this.lock.lock();
            try {
                for (byte[] channel : channels) {
                    String channelName = new String(channel, StandardCharsets.UTF_8);
                    if (this.subscribed.add(channelName)) {
                        super.subscribe(channel);
                    }
                }
            } finally {
                this.lock.unlock();
            }
        }

        /** True when every given channel is already subscribed - lets the watchdog skip the lock. */
        boolean hasSubscribedTo(Set<String> channels) {
            return this.subscribed.containsAll(channels);
        }

        @Override
        public void unsubscribe(byte[]... channels) {
            this.lock.lock();
            try {
                super.unsubscribe(channels);
            } finally {
                this.lock.unlock();
            }
        }

        @Override
        public void onSubscribe(byte[] channel, int subscribedChannels) {
            Log.info("[helper-redis] Subscribed to channel: " + new String(channel, StandardCharsets.UTF_8));
        }

        @Override
        public void onUnsubscribe(byte[] channel, int subscribedChannels) {
            String channelName = new String(channel, StandardCharsets.UTF_8);
            Log.info("[helper-redis] Unsubscribed from channel: " + channelName);
            this.subscribed.remove(channelName);
        }

        @Override
        public void onMessage(byte[] channel, byte[] message) {
            String channelName = new String(channel, StandardCharsets.UTF_8);
            try {
                HelperRedis.this.messenger.registerIncomingMessage(channelName, message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
