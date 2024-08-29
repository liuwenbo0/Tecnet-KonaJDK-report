# 多种 GC 同一测试

测试平台：javatestOS=Linux 5.15.153.1-microsoft-standard-WSL2 (amd64)

测试工具：jtreg 7.5 dev 0

测试对象：

> /home/webliu/TencentKona-17/build/linux-x86_64-server-release/jdk
> openjdk version "17.0.11-internal" 2024-04-16
> OpenJDK Runtime Environment (build 17.0.11-internal+0-adhoc.webliu.TencentKona-17)
> OpenJDK 64-Bit Server VM (build 17.0.11-internal+0-adhoc.webliu.TencentKona-17, mixed mode)

测试配置：核数 CPUs: 16 total, 16 available，总内存 Memory: 7818M，堆大小大约为 1950M

测试用例：经典的 LRUCache 测试。考虑到让更多的对象从 Young 区晋升到老区， Cache 总大小设置为了堆内存的 70%。考虑到大多数情况下，新创建的对象会被分配在堆内存中(除非 JVM 可以判断该对象仅在当前方法内使用，会进行栈上分配)。为了触发频繁的 GC 事件且观测出现堆内存溢出时 GC 的行为，创建对象的总大小设置为了堆内存的 1.1 倍。测试过程中随机向 Cache 中添加键值对，每个键值对占用 1KB 大小，重复若干次。测试用例源代码见仓库测试用例文件夹。

## Serial_gc

观察 serial_gc 的日志

`[0.180s][info][gc,start    ] GC(0) Pause Young (Allocation Failure)`

`[0.198s][info][gc,heap     ] GC(0) DefNew: 33856K(38080K)->4224K(38080K) Eden: 33856K(33856K)->0K(33856K) From: 0K(4224K)->4224K(4224K)`

`[0.198s][info][gc,heap     ] GC(0) Tenured: 0K(84672K)->22432K(84672K)`

`[0.198s][info][gc          ] GC(0) Pause Young (Allocation Failure) 33M->26M(119M) 17.871ms`

可以发现 serial gc 使用分代算法，即新创建的对象通常很快就会变得不可达，而存活时间较长的对象通常会继续存活。并且 serial gc 采用串行方式执行，不额外开启线程，当进行 gc 时会暂停应用程序的进程直至垃圾回收完成。

- 第 0 次 gc 对 Young 区进行垃圾回收，此次垃圾回收的暂停时间为 17.871ms，暂停时间较短。
- Young 区有 Eden 区和两个 Survivor 区(From、to)组成。此次 GC 过程中 Eden 区被清空（0K），一部分对象被移入 Survivor 区(4224K)，一部分对象晋升到老区(22432K)。可以计算出此过程中 Young 区对象的存活率为 26M/33K = 78.788 %。

之后又执行了 2 次对 Young 区的 GC，然后执行了第一次的全堆 GC

```log
[0.337s][info][gc,start    ] GC(3) Pause Full (Allocation Failure）
[0.337s][info][gc,phases,start] GC(3) Phase 1: Mark live objects
[0.355s][info][gc,phases ] GC(3) Phase 1: Mark live objects 18.292ms
[0.355s][info][gc,phases,start] GC(3) Phase 2: Compute new object addresses
[0.365s][info][gc,phases ] GC(3) Phase 2: Compute new object addresses 10.202ms
[0.365s][info][gc,phases,start] GC(3) Phase 3: Adjust pointers
[0.384s][info][gc,phases ] GC(3) Phase 3: Adjust pointers 18.793ms
[0.384s][info][gc,phases,start] GC(3) Phase 4: Move objects
[0.385s][info][gc,phases ] GC(3) Phase 4: Move objects 0.636ms
[0.385s][info][gc,heap ] GC(3) DefNew: 38080K(38080K)->4222K(66816K) Eden: 33856K(33856K)->4222K(59456K) From: 4224K(4224K)->0K(7360K)
[0.385s][info][gc,heap ] GC(3) Tenured: 55491K(84672K)->88991K(148320K)
[0.385s][info][gc,metaspace ] GC(3) Metaspace: 8288K(8448K)->8288K(8448K) NonClass: 7525K(7616K)->7525K(7616K) Class: 763K(832K)->763K(832K)
[0.385s][info][gc ] GC(3) Pause Full (Allocation Failure) 91M->91M(210M) 48.403ms
```

可以发现，全堆 GC 花费 18.292ms 标记活对象，10.202ms 计算新对象地址，18.793 调整指针，0.636ms 移动对象。GC 之后 Young 区的占用从 38080K(38080K)降到 4222K(66816K)，Young 区总的对象几乎全被调整到了老区，这可能与我的 Cache_size 设置的比较大有关，对象的存活时间都比较长。

- 此次垃圾回收的暂停时间为 48.403ms，暂停时间较长。

`[7.988s][info][gc             ] GC(13) Pause Young (Allocation Failure) 1354M->1294M(1890M) 529.409ms`

`[11.744s][info][gc             ] GC(15) Pause Full (Allocation Failure) 1816M->1454M(1890M) 1053.767ms`

当对象占用的总内存超出设置的 Cache_size 之后(70%堆大小，即 1890 \* 0.7 = 1323)：可以发现 GC 的效率大大提高了，这是因为 LRUCache 满了之后大量对象将不再存活。

```log
[13.550s][info][gc,heap,exit   ]  def new generation   total 600896K, used 413946K [0x0000000085c00000, 0x00000000ae800000, 0x00000000ae800000)
[13.550s][info][gc,heap,exit   ]   eden space 534144K,  77% used [0x0000000085c00000, 0x000000009f03e920, 0x00000000a65a0000)
[13.550s][info][gc,heap,exit   ]   from space 66752K,   0% used [0x00000000aa6d0000, 0x00000000aa6d0000, 0x00000000ae800000)
[13.550s][info][gc,heap,exit   ]   to   space 66752K,   0% used [0x00000000a65a0000, 0x00000000a65a0000, 0x00000000aa6d0000)
[13.550s][info][gc,heap,exit   ]  tenured generation   total 1335296K, used 1335295K [0x00000000ae800000, 0x0000000100000000, 0x0000000100000000)
[13.550s][info][gc,heap,exit   ]    the space 1335296K,  99% used
```

最终年轻代的 Eden 区使用了 77% 的空间，两个 Survivor 区未使用。老年代几乎已满（99%）。

## Parallel_gc

`[0.002s][info][gc,init] Parallel Workers: 13`
可以看到 parallel gc 采用了 13 个线程并行进行垃圾回收，但应用程序进程这时候是暂停的。这有利于保证垃圾回收过程中的数据一致性。

Parallel_gc 同样使用分代算法，其垃圾回收过程与 serial_gc 类似，只是通过并行提高垃圾回收的效率。

可以对比一下 serial_gc 和 parallel_gc 的第一次 GC 过程(对 Young 区的 GC)

`[0.198s][info][gc          ] GC(0) Pause Young (Allocation Failure) 33M->26M(119M) 17.871ms`

`[0.208s][info][gc          ] GC(0) Pause Young (Allocation Failure) 31M->24M(119M) 8.988ms`

可以发现，差不多的 GC 工作量，serial_gc 用时 17.871ms，而 parallel_gc 用时 8.988ms,比 serial_gc 快了一倍。

Parallel_gc 对全堆的 GC 分为标记阶段，汇总阶段，调整根节点阶段、压缩阶段和压缩后处理阶段。同样与 serial_gc 对比，考虑到工作量的不同，其效率依旧比 serial_gc 高 1.8 倍。(91/48.403 : 56/16.238 = 1 : 1.8344)

`[0.385s][info][gc             ] GC(3) Pause Full (Allocation Failure) 91M->91M(210M) 48.403ms`

`[0.256s][info][gc             ] GC(2) Pause Full (Ergonomics) 56M->53M(195M) 16.238ms`

```log
[13.666s][info][gc,heap,exit   ]  PSYoungGen      total 445440K, used 203048K [0x00000000d7400000, 0x0000000100000000, 0x0000000100000000)
[13.666s][info][gc,heap,exit   ]   eden space 223232K, 90% used [0x00000000d7400000,0x00000000e3a4a380,0x00000000e4e00000)
[13.666s][info][gc,heap,exit   ]   from space 222208K, 0% used [0x00000000e4e00000,0x00000000e4e00000,0x00000000f2700000)
[13.666s][info][gc,heap,exit   ]   to   space 222208K, 0% used [0x00000000f2700000,0x00000000f2700000,0x0000000100000000)
[13.666s][info][gc,heap,exit   ]  ParOldGen       total 1335296K, used 1322198K [0x0000000085c00000, 0x00000000d7400000, 0x00000000d7400000)
[13.666s][info][gc,heap,exit   ]   object space 1335296K, 99% used [0x0000000085c00000,0x00000000d6735838,0x00000000d7400000)
```

最终年轻代的 Eden 区使用了 90% 的空间，两个 Survivor 区未使用。老年代几乎已满（99%）。

## G1GC

查看 g1gc 的日志

```log
[0.004s][info][gc,init] Parallel Workers: 13
[0.004s][info][gc,init] Concurrent Workers: 3
[0.004s][info][gc,init] Concurrent Refinement Workers: 13
```

可以发现，与 parallel_gc 不同，G1GC 不但使用 13 个线程进行并行垃圾回收，还使用 3 个线程进行并发垃圾回收，使得在垃圾回收的过程中不需要暂停应用程序。同时还有 13 个执行并发细化任务的线程，进一步减少垃圾回收的暂停时间。

```log
[0.165s][info][gc,start    ] GC(0) Pause Young (Normal) (G1 Evacuation Pause)
[0.166s][info][gc,task     ] GC(0) Using 4 workers of 13 for evacuation
[0.172s][info][gc,phases   ] GC(0)   Pre Evacuate Collection Set: 0.1ms
[0.172s][info][gc,phases   ] GC(0)   Merge Heap Roots: 0.1ms
[0.172s][info][gc,phases   ] GC(0)   Evacuate Collection Set: 5.7ms
[0.172s][info][gc,phases   ] GC(0)   Post Evacuate Collection Set: 0.3ms
[0.172s][info][gc,phases   ] GC(0)   Other: 0.7ms
[0.172s][info][gc,heap     ] GC(0) Eden regions: 23->0(6)
[0.172s][info][gc,heap     ] GC(0) Survivor regions: 0->3(3)
[0.172s][info][gc,heap     ] GC(0) Old regions: 0->15
[0.172s][info][gc,heap     ] GC(0) Archive regions: 0->0
[0.172s][info][gc,heap     ] GC(0) Humongous regions: 9->9
```

可以看到，G1GC 将整个堆分为多个固定大小的 Region。其中 Humongous Region 专门用来存放大对象。G1GC 对 Young Generation 的 GC 分为 4 个阶段，且同时使用 4 个线程进行并行垃圾回收(最多可以使用 13 个线程，G1GC 会自动调节线程数)。
`[0.172s][info][gc          ] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 32M->26M(124M) 7.102ms`
G1GC 的暂停时间很短，跟 parallel_gc 相比 `[0.208s][info][gc          ] GC(0) Pause Young (Allocation Failure) 31M->24M(119M) 8.988ms`要快一些，这是因为 G1GC 的设计目标之一就是减少垃圾回收的暂停时间，注重提供可预测的暂停时间。

`[0.210s][info][gc,start    ] GC(4) Pause Young (Concurrent Start) (G1 Humongous Allocation)`
该日志表示在对 Young Generation 进行垃圾回收的过程中，还并发的开始了标记操作。

```log
[0.214s][info][gc          ] GC(5) Concurrent Undo Cycle
[0.214s][info][gc,marking  ] GC(5) Concurrent Cleanup for Next Mark
```

该日志则表示对标记进行了并发撤销和并发清理操作。
G1 垃圾收集器支持并发标记和清理，以减少应用程序的暂停时间。并发周期主要包括以下几个阶段：

1. Concurrent Marking：在应用程序运行的同时，G1 会在后台标记可能需要被回收的对象。
2. Concurrent Cleanup：在标记阶段完成后，G1 会清理已标记的对象，同样是在应用程序运行的同时进行。
3. Concurrent Undo Cycle：在某些情况下，应用程序可能会在标记过程中创建新的对象或者修改现有对象的引用关系，因此需要撤销那些在并发标记过程中由于应用程序的运行而变得不必要的标记操作。

当然还存在许多子阶段，包括 Clear Claimed Marks、Scan Root Regions、Preclean、Rebuild Remembered Sets 等都是并发执行的。

`[0.564s][info][gc,start    ] GC(11) Pause Young (Prepare Mixed) (G1 Evacuation Pause)`
该日志表示，此次对 Young Generation 的垃圾回收过程采用 Prepare Mixed 模式，这意味着除了年轻代之外，还会选择一部分老年代区域，在接下来的 Mixed 阶段进行清理。
`[0.680s][info][gc,start    ] GC(12) Pause Young (Mixed) (G1 Evacuation Pause)`
该日志表示，此次对 Young Generation 的垃圾回收过程采用 Mixed 模式。在 Mixed GC 中，G1 会将 Prepare 阶段选择老年代的区域与年轻代一起进行清理。这样做的目的是为了避免老年代中的对象过多地积累，从而减少未来需要进行全堆 GC 的可能性。

实际上 G1GC 确实很少进行全堆 GC，与 parallel_gc 在程序执行第 0.256s 就进行了第一次全堆 GC 不同，G1GC 在程序执行第 12.010s 才进行了第一次全堆 GC。而程序总共也才执行了 13.937s。

```log
[12.010s][info][gc,start    ] GC(42) Pause Full (G1 Compaction Pause)
[12.010s][info][gc,phases,start] GC(42) Phase 1: Mark live objects
[12.248s][info][gc,phases      ] GC(42) Phase 1: Mark live objects 237.223ms
[12.248s][info][gc,phases,start] GC(42) Phase 2: Prepare for compaction
[12.299s][info][gc,phases      ] GC(42) Phase 2: Prepare for compaction 51.436ms
[12.299s][info][gc,phases,start] GC(42) Phase 3: Adjust pointers
[12.383s][info][gc,phases      ] GC(42) Phase 3: Adjust pointers 83.506ms
[12.383s][info][gc,phases,start] GC(42) Phase 4: Compact heap
[12.575s][info][gc,phases      ] GC(42) Phase 4: Compact heap 192.288ms
[12.578s][info][gc             ] GC(42) Pause Full (G1 Compaction Pause) 1956M->1467M(1956M) 567.322ms
```

G1GC 的全堆 GC 同样包括了标记活对象、准备压缩、调整指针以及压缩堆等四个阶段，其全堆 GC 还是比较费时的，但 G1GC 很少进行全堆 GC。

```log
[13.924s][info][gc,heap,exit   ] Heap
[13.924s][info][gc,heap,exit   ]  garbage-first heap   total 2002944K, used 1691938K [0x0000000085c00000, 0x0000000100000000)
[13.924s][info][gc,heap,exit   ]   region size 1024K, 18 young (18432K), 13 survivors (13312K)
[13.924s][info][gc,heap,exit   ]  Metaspace       used 8337K, committed 8512K, reserved 1114112K
[13.924s][info][gc,heap,exit   ]   class space    used 767K, committed 832K, reserved 1048576K
```

最终，堆内存总计总计约为 1956 MB，已用堆空间约为 1652 MB，已使用了约 84.5%，年轻代和 Survivor 区域分别占用了 18MB 和 13MB。

## ZGC

尽管 ZGC 的日志中出现了很多关于系统负载和引用信息的记录，但涉及到 GC 阶段的日志内容和 G1GC 十分类似。ZGC 与 G1GC 一样为了减少垃圾回收的暂停时间，使用并发线程进行标记和清理，同时使用多个并行线程加快 GC 过程。
区别在于，ZGC 没有所谓的 Young Generation 和 Old Generation 之分，而是将堆作为一个整体进行 GC(ZHeap)。同时 ZGC 具有 Warmup(GC 预热阶段)、Proactive(主动降低暂停时间阶段)、High Usage(高使用率阶段)、Allocation Rate(分配速率阶段)四个不同的 GC 阶段。

具体来说，每个阶段的特征如下：

- Warmup：进行初始化工作的阶段，可能会进行较为频繁的小规模 GC 操作，以获取数据
- Proactive：采取积极措施以降低暂停时间的阶段，主动监控内存分配速率和垃圾回收情况，根据当前的内存状况动态调整 GC 策略
- High Usage：当内存使用率超过一定阈值时，进入此阶段，会进行更频繁的 GC 活动，以释放更多内存
- Allocation Rate：当内存分配速率超过一定阈值时，进入此阶段，在这个阶段，ZGC 的重点是确保年轻代（Eden）的空间能够及时回收并为新对象腾出空间

这些阶段的划分是 G1GC 所没有的

`[27.001s][info][gc,heap,exit]  ZHeap           used 1950M, capacity 1952M, max capacity 1956M`
最终，堆空间总计 1956M，使用了 1950M，占用率接近 100%。

## ShenandoahGC

查看 ShenandoahGC 的日志

`[0.006s][info][gc,init] TLAB Size Max: 512K`
可以发现，ShenandoahGC 的初始化信息中，TLAB 大小为 512KB。ShenandoahGC 使用 TLAB 来避免了多个线程在对象分配时发生竞争。其他现代 GC 也可能使用了 TLAB，但 ZGC 和 G1GC 的日志中并没有输出 TLAB 的大小。

ShenandoahGC 同样使用并发线程进行标记和清理，但其暂停时间更短。这是因为 ShenandoahGC 和 G1GC 不同，前者更专注于提供较小的暂停时间，而后者专注于提供可预测的暂停时间。
且 ShenandoahGC 也没有分代的设计。

根据日志可以发现，ShenandoahGC 在分配对象占用 490M 时才开始第一次 GC，这是其他 GC 方法未曾出现过的现象。这是因为 Shenandoah GC 根据倾向于等到内存几乎耗尽时才开始垃圾回收。这种机制可以避免频繁的 GC，以便能够在需要时迅速分配大量对象而不会因为垃圾回收而阻塞。

GC(0)分为若干个阶段，每个阶段的耗时如下:

```log
Concurrent reset: 1.168ms
Pause Init Mark: 0.668ms
Concurrent marking roots: 0.316ms
Concurrent marking: 105.983ms
Pause Final Mark: 0.644ms
Concurrent thread roots: 0.172ms
Concurrent weak references: 0.198ms
Concurrent weak roots: 0.318ms
Concurrent cleanup: 0.176ms
Concurrent class unloading: 0.659ms
Concurrent strong roots: 0.419ms
Concurrent evacuation: 1.845ms
Pause Init Update Refs: 0.040ms
Concurrent update references: 18.669ms
Concurrent update thread roots: 0.282ms
Pause Final Update Refs: 0.178ms
Concurrent cleanup: 0.217ms
```

GC(0)的总用时为 131.972ms，但其中需要暂停的阶段只有

```log
Pause Init Mark: 0.668ms
Pause Final Mark: 0.644ms
Pause Init Update Refs: 0.040ms
Pause Final Update Refs: 0.178ms
```

总的暂停时间是 1.530ms，这说明对于 ShenandoahGC 来说，暂停时间是非常短的，大部分 GC 都有并发过程来完成。
G1GC 与之相比，存在大量的 G1 Evacuation Pause 阶段，暂停时间要长很多。

查看 ShenandoahGC 的后续日志，需要暂停的阶段仍然只有上述的三种，暂停时间通常不超过 1ms。

## 总结

|      GC 方法      | 耗时(s) | 老区对象存活数 | Cache 大小 | 特点                                       |
| :---------------: | ------: | -------------: | ---------: | ------------------------------------------ |
|     Serial GC     |  13.568 |         999026 |    1355334 | 简单易实现                                 |
| Parallel Scavenge |  13.681 |        1062305 |    1246515 | 多个垃圾回收进程并行                       |
|       G1GC        |  13.937 |        1376223 |    1402060 | 与应用程序并发的 GC 线程，可预测的暂停时间 |
|        ZGC        |  27.195 |        1392071 |    1402060 | 无分代设计，负载感知的多种 GC 阶段         |
|   Shenandoah GC   |  29.725 |        1391450 |    1401344 | 无分代设计，最短的暂停时间                 |

上表中的耗时以 main 函数执行时间为准，Cache 大小是根据 JVM 提供的堆内存容量动态调整的。
