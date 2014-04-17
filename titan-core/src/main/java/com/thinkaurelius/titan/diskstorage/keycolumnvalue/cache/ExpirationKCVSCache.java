package com.thinkaurelius.titan.diskstorage.keycolumnvalue.cache;

import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.Weigher;
import com.thinkaurelius.titan.core.TitanException;
import com.thinkaurelius.titan.diskstorage.*;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.*;
import com.thinkaurelius.titan.diskstorage.time.Timestamps;
import com.thinkaurelius.titan.diskstorage.util.CacheMetricsAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.thinkaurelius.titan.util.datastructures.ByteSize.*;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public class ExpirationKCVSCache extends KCVSCache {

    private static final Logger log =
            LoggerFactory.getLogger(ExpirationKCVSCache.class);

    //Weight estimation
    private static final int STATICARRAYBUFFER_SIZE = STATICARRAYBUFFER_RAW_SIZE + 10; // 10 = last number is average length
    private static final int KEY_QUERY_SIZE = OBJECT_HEADER + 4 + 1 + 3 * (OBJECT_REFERENCE + STATICARRAYBUFFER_SIZE); // object_size + int + boolean + 3 static buffers

    private static final int INVALIDATE_KEY_FRACTION_PENALTY = 1000;
    private static final int PENALTY_THRESHOLD = 5;

    private volatile CountDownLatch penaltyCountdown;

    private final Cache<KeySliceQuery,EntryList> cache;
    private final ConcurrentHashMap<StaticBuffer,Long> expiredKeys;

    private final long cacheTime;
    private final long expirationGracePeriod;
    private final CleanupThread cleanupThread;


    public ExpirationKCVSCache(final KeyColumnValueStore store, String metricsName, final long cacheTime, final long expirationGracePeriod, final long maximumByteSize) {
        super(store, metricsName);
        Preconditions.checkArgument(cacheTime > 0, "Cache expiration must be positive: %s", cacheTime);
        Preconditions.checkArgument(Timestamps.SYSTEM().getTime()+1000l*3600*24*365*100+cacheTime>0,"Cache expiration time too large, overflow may occur: %s",cacheTime);
        this.cacheTime = cacheTime;
        int concurrencyLevel = Runtime.getRuntime().availableProcessors();
        Preconditions.checkArgument(expirationGracePeriod>=0,"Invalid expiration grace peiod: %s",expirationGracePeriod);
        this.expirationGracePeriod = expirationGracePeriod;
        CacheBuilder<KeySliceQuery,EntryList> cachebuilder = CacheBuilder.newBuilder()
                .maximumWeight(maximumByteSize)
                .concurrencyLevel(concurrencyLevel)
                .initialCapacity(1000)
                .expireAfterWrite(cacheTime, Timestamps.SYSTEM().getUnit())
                .weigher(new Weigher<KeySliceQuery, EntryList>() {
                    @Override
                    public int weigh(KeySliceQuery keySliceQuery, EntryList entries) {
                        return GUAVA_CACHE_ENTRY_SIZE + KEY_QUERY_SIZE + entries.getByteSize();
                    }
                });

        cache = cachebuilder.build();
        expiredKeys = new ConcurrentHashMap<StaticBuffer, Long>(50,0.75f,concurrencyLevel);
        penaltyCountdown = new CountDownLatch(PENALTY_THRESHOLD);

        cleanupThread = new CleanupThread();
        cleanupThread.start();
    }

    @Override
    public EntryList getSlice(final KeySliceQuery query, final StoreTransaction txh) throws StorageException {
        incActionBy(1, CacheMetricsAction.RETRIEVAL,txh);
        if (isExpired(query)) {
            incActionBy(1, CacheMetricsAction.MISS,txh);
            return store.getSlice(query, getTx(txh));
        }

        try {
            return cache.get(query,new Callable<EntryList>() {
                @Override
                public EntryList call() throws Exception {
                    incActionBy(1, CacheMetricsAction.MISS,txh);
                    return store.getSlice(query, getTx(txh));
                }
            });
        } catch (Exception e) {
            if (e instanceof TitanException) throw (TitanException)e;
            else if (e.getCause() instanceof TitanException) throw (TitanException)e.getCause();
            else throw new TitanException(e);
        }
    }

    @Override
    public Map<StaticBuffer,EntryList> getSlice(final List<StaticBuffer> keys, final SliceQuery query, final StoreTransaction txh) throws StorageException {
        Map<StaticBuffer,EntryList> results = new HashMap<StaticBuffer, EntryList>(keys.size());
        List<StaticBuffer> remainingKeys = new ArrayList<StaticBuffer>(keys.size());
        KeySliceQuery[] ksqs = new KeySliceQuery[keys.size()];
        incActionBy(keys.size(), CacheMetricsAction.RETRIEVAL,txh);
        //Find all cached queries
        for (int i=0;i<keys.size();i++) {
            StaticBuffer key = keys.get(i);
            ksqs[i] = new KeySliceQuery(key,query);
            EntryList result = null;
            if (!isExpired(ksqs[i])) result = cache.getIfPresent(ksqs[i]);
            else ksqs[i]=null;
            if (result!=null) results.put(key,result);
            else remainingKeys.add(key);
        }
        //Request remaining ones from backend
        if (!remainingKeys.isEmpty()) {
            incActionBy(remainingKeys.size(), CacheMetricsAction.MISS,txh);
            Map<StaticBuffer,EntryList> subresults = store.getSlice(remainingKeys, query, getTx(txh));
            for (int i=0;i<keys.size();i++) {
                StaticBuffer key = keys.get(i);
                EntryList subresult = subresults.get(key);
                if (subresult!=null) {
                    results.put(key,subresult);
                    if (ksqs[i]!=null) cache.put(ksqs[i],subresult);
                }
            }
        }
        return results;
    }

    @Override
    public void clearCache() {
        cache.invalidateAll();
        expiredKeys.clear();
        penaltyCountdown = new CountDownLatch(PENALTY_THRESHOLD);
    }

    @Override
    public void invalidate(StaticBuffer key, List<CachableStaticBuffer> entries) {
        Preconditions.checkArgument(!hasValidateKeysOnly() || entries.isEmpty());
        expiredKeys.put(key,getExpirationTime());
        if (Math.random()<1.0/INVALIDATE_KEY_FRACTION_PENALTY) penaltyCountdown.countDown();
    }

    @Override
    public void close() throws StorageException {
        cleanupThread.stopThread();
        super.close();
    }

    private boolean isExpired(final KeySliceQuery query) {
        Long until = expiredKeys.get(query.getKey());
        if (until==null) return false;
        if (isBeyondExpirationTime(until)) {
            expiredKeys.remove(query.getKey(),until);
            return false;
        }
        //We suffer
        penaltyCountdown.countDown();
        return true;
    }

    private final long getExpirationTime() {
        return Timestamps.SYSTEM().getTime()+cacheTime;
    }

    private final boolean isBeyondExpirationTime(long until) {
        return until<Timestamps.SYSTEM().getTime();
    }

    private final long getAge(long until) {
        long age = Timestamps.SYSTEM().getTime() - (until-cacheTime);
        assert age>=0;
        return age;
    }

    private class CleanupThread extends Thread {

        private boolean stop = false;

        public CleanupThread() {
            this.setDaemon(true);
            this.setName("ExpirationStoreCache-" + getId());
        }

        @Override
        public void run() {
            while (true) {
                if (stop) return;
                try {

                    penaltyCountdown.await();
                } catch (InterruptedException e) {
                    if (stop) return;
                    else throw new RuntimeException("Cleanup thread got interrupted",e);
                }
                //Do clean up work by invalidating all entries for expired keys
                HashMap<StaticBuffer,Long> expiredKeysCopy = new HashMap<StaticBuffer,Long>(expiredKeys.size());
                for (Map.Entry<StaticBuffer,Long> expKey : expiredKeys.entrySet()) {
                    if (isBeyondExpirationTime(expKey.getValue()))
                        expiredKeys.remove(expKey.getKey(), expKey.getValue());
                    else if (getAge(expKey.getValue())>=expirationGracePeriod)
                        expiredKeysCopy.put(expKey.getKey(),expKey.getValue());
                }
                for (KeySliceQuery ksq : cache.asMap().keySet()) {
                    if (expiredKeysCopy.containsKey(ksq.getKey())) cache.invalidate(ksq);
                }
                penaltyCountdown = new CountDownLatch(PENALTY_THRESHOLD);
                for (Map.Entry<StaticBuffer,Long> expKey : expiredKeysCopy.entrySet()) {
                    expiredKeys.remove(expKey.getKey(),expKey.getValue());
                }
            }
        }

        void stopThread() {
            stop = true;
            this.interrupt();
        }
    }




}
