/*
 * @test
 * @bug 1234567
 * @summary Test LRU Cache with Whitebox API using different GC methods and collect GC logs
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 *          java.management
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -XX:+UseG1GC -Xlog:gc* -Xlog:gc:g1gc.log  LRUCacheTest
 */

import jdk.test.whitebox.WhiteBox;

import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.*;

public class LRUCacheTest {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();

    public static void main(String[] args) {
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapMemoryUsage = memoryMXBean.getHeapMemoryUsage();
        double maxHeapSize = heapMemoryUsage.getMax() / (1024.0 * 1024.0);
        System.out.printf("Max heap size: %.2f MB\n", maxHeapSize);
        int objectSize = 2048;
        // 计算 CACHE_SIZE
        int cacheSize = (int) (heapMemoryUsage.getMax() * 0.7 / objectSize);
        // 计算 TOTAL_MEMORY 的值
        long totalMemory = (long) Math.round(heapMemoryUsage.getMax() * 1.1);
        // 可能的 object 大小
        int[] possibleSizes = {1024, 2048, 4096};
        Random random = new Random();
        System.out.println("Cache size: " + cacheSize);
        System.out.println("Total memory: " + totalMemory);
        LRUCache<Integer, byte[]> cache = new LRUCache<>(cacheSize);

        long currentMemoryUsage = 0;
        int i = 0;

        while (currentMemoryUsage < totalMemory) {
            int key = i ++;
            int size = possibleSizes[random.nextInt(possibleSizes.length)];
            byte[] value = new byte[size]; // 1KB、2KB、4KB objects
            cache.add(key, value);
            currentMemoryUsage += size;

            if (i  % 10000 == 0) {
                System.out.println("Operations: " + i);
                int liveObjects = 0;

                // 检查缓存中的对象是否在老年代中
                for (Map.Entry<Integer, byte[]> entry : cache.cache.entrySet()) {
                    if (WB.isObjectInOldGen(entry.getValue())) {
                        liveObjects++;
                    }
                }

                System.out.println("Live objects in old region: " + liveObjects);
                System.out.flush(); // 刷新 System.out 缓冲区
                WB.g1GetOldRegionAddress(); // 获取各个Region的存活率
            }
        }

        // 最终检查
        System.out.println("Final check:");
        int finalLiveObjects = 0;
        for (Map.Entry<Integer, byte[]> entry : cache.cache.entrySet()) {
            if (WB.isObjectInOldGen(entry.getValue())) {
                finalLiveObjects++;
            }
        }
        System.out.println("Live objects in old region: " + finalLiveObjects);
    }

    // LRUCache implementation as a static nested class
    private static class LRUCache<K, V> {
        private final int capacity;
        private final LinkedHashMap<K, V> cache;

        public LRUCache(int capacity) {
            this.capacity = capacity;
            this.cache = new LinkedHashMap<K, V>(capacity, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    return size() > LRUCache.this.capacity;
                }
            };
        }

        public V get(K key) {
            return cache.get(key);
        }

        public void add(K key, V value) {
            cache.put(key, value);
        }

        public void printCache() {
            System.out.println("Cache contents: " + cache);
        }
    }
}