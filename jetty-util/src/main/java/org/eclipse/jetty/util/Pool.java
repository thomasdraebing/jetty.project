//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Locker;

/**
 * A fast container of poolable objects, with optional support for
 * multiplexing, max usage count and thread-local caching.
 * <p>
 * The thread-local caching mechanism is about remembering up to N previously
 * used entries into a thread-local single-threaded collection.
 * When that collection is not empty, its entries are removed one by one
 * during acquisition until an entry that can be acquired is found.
 * This can greatly speed up acquisition when both the acquisition and the
 * release of the entries is done on the same thread as this avoids iterating
 * the global, thread-safe collection of entries.
 * </p>
 * <p>
 * When the method {@link #close()} is called, all {@link Closeable}s in the pool
 * are also closed.
 * </p>
 * @param <T>
 */
public class Pool<T> implements AutoCloseable, Dumpable
{
    private static final Logger LOGGER = Log.getLogger(Pool.class);

    private final List<Entry> sharedList = new CopyOnWriteArrayList<>();
    /*
     * The cache is used to avoid hammering on the first index of the entry list.
     * Caches can become poisoned (i.e.: containing entries that are in use) when
     * the release isn't done by the acquiring thread or when the entry pool is
     * undersized compared to the load applied on it.
     * When an entry can't be found in the cache, the global list is iterated
     * normally so the cache has no visible effect besides performance.
     */
    private final ThreadLocal<List<Entry>> cache;
    private final Locker locker = new Locker();
    private final int maxEntries;
    private final int cacheSize;
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean closed;
    private volatile int maxMultiplex = 1;
    private volatile int maxUsageCount = -1;

    /**
     * Construct a Pool with the specified thread-local cache size.
     *
     * @param maxEntries the maximum amount of entries that the pool will accept.
     * @param cacheSize the thread-local cache size. A value less than 1 means the cache is disabled.
     */
    public Pool(int maxEntries, int cacheSize)
    {
        this.maxEntries = maxEntries;
        this.cacheSize = cacheSize;
        if (cacheSize > 0)
            this.cache = ThreadLocal.withInitial(() -> new ArrayList<Entry>(cacheSize));
        else
            this.cache = null;
    }

    public int getPendingConnectionCount()
    {
        return pending.get();
    }

    public int getIdleConnectionCount()
    {
        return (int)sharedList.stream().filter(Entry::isIdle).count();
    }

    public int getInUseConnectionCount()
    {
        return (int)sharedList.stream().filter(entry -> !entry.isIdle()).count();
    }

    public int getMaxEntries()
    {
        return maxEntries;
    }

    public int getMaxMultiplex()
    {
        return maxMultiplex;
    }

    public final void setMaxMultiplex(int maxMultiplex)
    {
        if (maxMultiplex < 1)
            throw new IllegalArgumentException("Max multiplex must be >= 1");
        this.maxMultiplex = maxMultiplex;
    }

    public int getMaxUsageCount()
    {
        return maxUsageCount;
    }

    public final void setMaxUsageCount(int maxUsageCount)
    {
        if (maxUsageCount == 0)
            throw new IllegalArgumentException("Max usage count must be != 0");
        this.maxUsageCount = maxUsageCount;
    }

    /**
     * Create a new disabled slot into the pool.
     * The returned Reservation holds an entry that will not
     * be acquirable until {@link Reservation#enable(Object)} is called.
     * Alternately {@link Reservation#acquire(Object)} can be called to
     * atomically enable and acquire the entry.
     * If a value cannot be created for the slot, then {@link Reservation#remove()}
     * must be called to free the slot.
     *
     * @param maxReservations the max desired number of reserved entries,
     * or a negative number to always trigger the reservation of a new entry.
     * @return a disabled entry that is contained in the pool,
     * or null if the pool is closed or if the pool already contains
     * {@link #getMaxEntries()} entries.
     */
    public Reservation reserve(int maxReservations)
    {
        try (Locker.Lock l = locker.lock())
        {
            if (closed)
                return null;

            int space = maxEntries - sharedList.size();
            if (space <= 0)
                return null;

            // The pending count is an AtomicInteger that is only ever incremented here with
            // the lock held.  Thus the pending count can be reduced immediately after the
            // test below, but never incremented.  Thus the maxReservations limit can be
            // enforced.
            if (maxReservations >= 0 && pending.get() >= maxReservations)
                return null;
            pending.incrementAndGet();

            Reservation reservation = new Reservation();
            sharedList.add(reservation.getEntry());
            return reservation;
        }
    }

    /**
     * Acquire the entry from the pool at the specified index. This method bypasses the thread-local mechanism.
     *
     * @param idx the index of the entry to acquire.
     * @return the specified entry or null if there is none at the specified index or if it is not available.
     */
    public Entry acquireAt(int idx)
    {
        if (closed)
            return null;

        try
        {
            Entry entry = sharedList.get(idx);
            if (entry.tryAcquire())
                return entry;
        }
        catch (IndexOutOfBoundsException e)
        {
            // no entry at that index
        }
        return null;
    }

    /**
     * Acquire an entry from the pool.
     *
     * @return an entry from the pool or null if none is available.
     */
    public Entry acquire()
    {
        if (closed)
            return null;

        // first check the thread-local cache
        if (cache != null)
        {
            List<Entry> cachedList = cache.get();
            while (!cachedList.isEmpty())
            {
                Entry cachedEntry = cachedList.remove(cachedList.size() - 1);
                if (cachedEntry.tryAcquire())
                    return cachedEntry;
            }
        }

        // then iterate the shared list
        for (Entry entry : sharedList)
        {
            if (entry.tryAcquire())
                return entry;
        }
        return null;
    }

    /**
     * This method will return an acquired object to the pool. Objects
     * that are acquired from the pool but never released will result
     * in a memory leak.
     *
     * @param entry the value to return to the pool
     * @return true if the entry was released and could be acquired again,
     * false if the entry should be removed by calling {@link #remove(Pool.Entry)}
     * and the object contained by the entry should be disposed.
     * @throws NullPointerException if value is null
     */
    public boolean release(Entry entry)
    {
        if (closed)
            return false;

        // first mark it as unused
        boolean reusable = entry.tryRelease();

        // then cache the released entry
        if (cache != null && reusable)
        {
            List<Entry> cachedList = cache.get();
            if (cachedList.size() < cacheSize)
                cachedList.add(entry);
        }
        return reusable;
    }

    /**
     * Remove a value from the pool.
     *
     * @param entry the value to remove
     * @return true if the entry was removed, false otherwise
     */
    public boolean remove(Entry entry)
    {
        if (closed)
            return false;

        if (!entry.tryRemove())
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Attempt to remove an object from the pool that is still in use: {}", entry);
            return false;
        }

        boolean removed = sharedList.remove(entry);
        if (!removed)
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Attempt to remove an object from the pool that does not exist: {}", entry);
        }

        return removed;
    }

    public boolean isClosed()
    {
        return closed;
    }

    @Override
    public void close()
    {
        List<Entry> copy;
        try (Locker.Lock l = locker.lock())
        {
            closed = true;
            copy = new ArrayList<>(sharedList);
            sharedList.clear();
        }

        // iterate the copy and close its entries
        for (Entry entry : copy)
        {
            if (entry.tryRemove() && entry.pooled instanceof Closeable)
                IO.close((Closeable)entry.pooled);
        }
    }

    public int size()
    {
        return sharedList.size();
    }

    public Collection<Entry> values()
    {
        return Collections.unmodifiableCollection(sharedList);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this);
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + " size=" + sharedList.size() + " closed=" + closed + " entries=" + sharedList;
    }

    /**
     * A Reservation of a slot in the Pool
     */
    public class Reservation
    {
        private final Entry entry = new Entry();

        /**
         * @return The reserved {@link Entry}, which will be closed until enabled.
         */
        public Pool<T>.Entry getEntry()
        {
            return entry;
        }

        /** Enable the reserved {@link Entry}.
         * Once enabled, the entry is immediately available to be acquired, potentially by
         * another thread.  If the caller wishes to acquire the associated entry, they should
         * use {@link #acquire(Object)} to atomically enable and acquire.
         * @param pooled The pooled item for the entry
         */
        public void enable(T pooled)
        {
            enable(pooled, false);
        }

        /** Enable and acquire the reserved {@link Entry}.
         * The associated entry is atomically enabled and acquired, so that no other thread can acquire it and
         * the {@link #getEntry()} value may be used by the caller.
         * @param pooled The pooled item for the entry
         */
        public Pool<T>.Entry acquire(T pooled)
        {
            enable(pooled, true);
            return entry;
        }

        private void enable(T pooled, boolean acquire)
        {
            Objects.requireNonNull(pooled);
            if (entry.state.getHi() != Integer.MIN_VALUE)
                throw new IllegalStateException("Open entries cannot be enabled : " + this);
            entry.pooled = pooled;
            int usage = acquire ? 1 : 0;
            if (!entry.state.compareAndSet(Integer.MIN_VALUE, usage, 0, usage))
            {
                entry.pooled = null;
                throw new IllegalStateException("Entry cannot be enabled : " + this);
            }
            pending.decrementAndGet();
        }

        /**
         * Remove the reservation without enabling.
         */
        public void remove()
        {
            Pool.this.remove(entry);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{%s}",
                getClass().getSimpleName(),
                hashCode(),
                entry);
        }
    }

    public class Entry
    {
        // hi: positive=open/maxUsage counter; negative=closed; MIN_VALUE pending
        // lo: multiplexing counter
        private final AtomicBiInteger state;

        // The pooled item.  This is not volatile as it is set once and then never changed.
        // Other threads accessing must check the state field above first, so a good before/after
        // relationship exists to make a memory barrier.
        private T pooled;

        Entry()
        {
            this.state = new AtomicBiInteger(Integer.MIN_VALUE, 0);
        }

        public T getPooled()
        {
            return pooled;
        }

        /**
         * Release the entry.
         * This is equivalent to calling {@link Pool#release(Pool.Entry)} passing this entry.
         * @return true if released.
         */
        public boolean release()
        {
            return Pool.this.release(this);
        }

        /**
         * Try to acquire the entry if possible by incrementing both the usage
         * count and the multiplex count.
         * @return true if the usage count is &lt;= maxUsageCount and
         * the multiplex count is maxMultiplex and the entry is not closed,
         * false otherwise.
         */
        boolean tryAcquire()
        {
            while (true)
            {
                long encoded = state.get();
                int usageCount = AtomicBiInteger.getHi(encoded);
                boolean closed = usageCount < 0;
                int multiplexingCount = AtomicBiInteger.getLo(encoded);
                int currentMaxUsageCount = maxUsageCount;
                if (closed || multiplexingCount >= maxMultiplex || (currentMaxUsageCount > 0 && usageCount >= currentMaxUsageCount))
                    return false;

                if (state.compareAndSet(encoded, usageCount + 1, multiplexingCount + 1))
                    return true;
            }
        }

        /**
         * Try to release the entry if possible by decrementing the multiplexing
         * count unless the entity is closed.
         * @return true if the entry was released,
         * false if {@link #tryRemove()} should be called.
         */
        boolean tryRelease()
        {
            int newMultiplexingCount;
            int usageCount;
            while (true)
            {
                long encoded = state.get();
                usageCount = AtomicBiInteger.getHi(encoded);
                boolean closed = usageCount < 0;
                if (closed)
                    return false;

                newMultiplexingCount = AtomicBiInteger.getLo(encoded) - 1;
                if (newMultiplexingCount < 0)
                    throw new IllegalStateException("Cannot release an already released entry");

                if (state.compareAndSet(encoded, usageCount, newMultiplexingCount))
                    break;
            }

            int currentMaxUsageCount = maxUsageCount;
            boolean overUsed = currentMaxUsageCount > 0 && usageCount >= currentMaxUsageCount;
            return !(overUsed && newMultiplexingCount == 0);
        }

        /**
         * Try to mark the entry as removed.
         * @return true if the entry has to be removed from the containing pool, false otherwise.
         */
        boolean tryRemove()
        {
            while (true)
            {
                long encoded = state.get();
                int usageCount = AtomicBiInteger.getHi(encoded);
                int multiplexCount = AtomicBiInteger.getLo(encoded);
                int newMultiplexCount = Math.max(multiplexCount - 1, 0);

                boolean removed = state.compareAndSet(usageCount, -1, multiplexCount, newMultiplexCount);
                if (removed)
                {
                    if (usageCount == Integer.MIN_VALUE)
                        pending.decrementAndGet();
                    return newMultiplexCount == 0;
                }
            }
        }

        public boolean isClosed()
        {
            return state.getHi() < 0;
        }

        public boolean isIdle()
        {
            return state.getLo() <= 0;
        }

        public int getUsageCount()
        {
            return Math.max(state.getHi(), 0);
        }

        @Override
        public String toString()
        {
            long encoded = state.get();
            return String.format("%s@%x{hi=%d,lo=%d.p=%s}",
                getClass().getSimpleName(),
                hashCode(),
                AtomicBiInteger.getHi(encoded),
                AtomicBiInteger.getLo(encoded),
                pooled);
        }
    }
}
