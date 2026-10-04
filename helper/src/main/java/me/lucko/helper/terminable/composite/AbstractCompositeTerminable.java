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

package me.lucko.helper.terminable.composite;

import me.lucko.helper.promise.Promise;
import me.lucko.helper.terminable.Terminable;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;

public class AbstractCompositeTerminable implements CompositeTerminable {
    private final Deque<AutoCloseable> closeables = new ConcurrentLinkedDeque<>();
    private boolean closed = false;

    protected AbstractCompositeTerminable() {

    }

    @Override
    public CompositeTerminable with(AutoCloseable autoCloseable) {
        Objects.requireNonNull(autoCloseable, "autoCloseable");
        // Aesir 04.10.2026: a promise bound here (Schedulers.async().run(...).bindWith(consumer) - the usual way to
        // "cancel it if the module unloads") stayed in this deque forever after it completed: cleanup() only drops
        // closed terminables and a completed promise is not "closed" (closed == cancelled). ASMP heap dump: 1.03M
        // completed promises (~200 MB with their futures/flags) held by the gameplay-asmp and AesirCore module
        // terminables after 20 h. Closing a completed promise is a no-op, so a completed promise is simply not
        // kept: never added when already done, removed as soon as it completes otherwise.
        if (autoCloseable instanceof Promise) {
            final Promise<?> promise = (Promise<?>) autoCloseable;
            if (promise.isDone()) {
                return this;
            }
            this.closeables.push(autoCloseable);
            // pushed first: a promise completing right now still runs this callback and is removed again
            promise.toCompletableFuture().whenComplete((value, error) -> this.closeables.remove(autoCloseable));
            return this;
        }
        this.closeables.push(autoCloseable);
        return this;
    }

    @Override
    public void close() throws CompositeClosingException {
        List<Exception> caught = new ArrayList<>();
        for (AutoCloseable ac; (ac = this.closeables.poll()) != null; ) {
            try {
                ac.close();
            } catch (Exception e) {
                caught.add(e);
            }
        }
        this.closed = true;

        if (!caught.isEmpty()) {
            throw new CompositeClosingException(caught);
        }
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void cleanup() {
        this.closeables.removeIf(ac -> {
            if (!(ac instanceof Terminable)) {
                return false;
            }
            if (ac instanceof CompositeTerminable) {
                ((CompositeTerminable) ac).cleanup();
            }
            return ((Terminable) ac).isClosed();
        });
    }
}
