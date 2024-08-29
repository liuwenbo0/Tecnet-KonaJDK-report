# G1GC 的 LRUCache 测试

测试平台：javatestOS=Linux 5.15.153.1-microsoft-standard-WSL2 (amd64)

测试工具：jtreg 7.5 dev 0

测试对象：

> /home/webliu/TencentKona-17/build/linux-x86_64-server-release/jdk
> openjdk version "17.0.11-internal" 2024-04-16
> OpenJDK Runtime Environment (build 17.0.11-internal+0-adhoc.webliu.TencentKona-17)
> OpenJDK 64-Bit Server VM (build 17.0.11-internal+0-adhoc.webliu.TencentKona-17, mixed mode)

测试配置：核数 CPUs: 16 total, 16 available，总内存 Memory: 7818M，堆大小大约为 1950M

测试用例：经典的 LRUCache 测试。考虑到让更多的对象从 Young 区晋升到老区， Cache 总大小设置为了堆内存的 70%。考虑到大多数情况下，新创建的对象会被分配在堆内存中(除非 JVM 可以判断该对象仅在当前方法内使用，会进行栈上分配)。为了触发频繁的 GC 事件且观测出现堆内存溢出时 GC 的行为，创建对象的总大小设置为了堆内存的 1.1 倍。测试过程中随机向 Cache 中添加键值对，每个键值对占用 1KB 大小，重复若干次。测试用例源代码见仓库测试用例文件夹。

## G1GC 运行与老区存活对象之间的关系

G1GC 除了通过并行线程进行 Young GC 之外，还会在老区空间不足时并发地启动 Concurrent Mark 进程，之后进行 Mixed GC。

接下来分类讨论 G1GC 不同运行阶段对存活对象的影响。

### Young GC

G1GC 对 Young Generation 的回收较为常见，分为四个阶段：

- Pre Evacuate Collection Set (标记出年轻代和老年代中需要清理的对象)
- Merge Heap Roots (更新和合并堆中的根集合，确保在随后的内存回收过程中，所有可达对象都不会被错误地回收)
- Evacuate Collection Set (将活动对象从一个区域复制到另一个区域，并标记原区域为可回收)
- Post Evacuate Collection Set(处理拷贝后的一些清理工作，如更新对象引用等)

在 G1GC 的日志中可以看到这些阶段：

```log
[0.172s][info][gc,phases   ] GC(0)   Pre Evacuate Collection Set: 0.1ms
[0.172s][info][gc,phases   ] GC(0)   Merge Heap Roots: 0.1ms
[0.172s][info][gc,phases   ] GC(0)   Evacuate Collection Set: 5.7ms
[0.172s][info][gc,phases   ] GC(0)   Post Evacuate Collection Set: 0.3ms
[0.172s][info][gc,phases   ] GC(0)   Other: 0.7ms
```

G1GC 对 Young Generation 的回收，通常会增加老区的数量，也会增加老区中存活对象的数量，因为一部分 Young Generation 的对象会被复制到老区中。
例如日志中在没做任何 GC 之前，老区的存活对象数量为 0。

```log
Operations: 10000
Live objects in old region: 0
```

当 GC(1)完成之后，老区中存活对象的数量变为了 17718。

```log
[0.179s][info][gc,cpu      ] GC(1) User=0.02s Sys=0.00s Real=0.01s
Operations: 20000
Live objects in old region: 17718
```

### GC 不运行阶段

当没有进行 GC 时，持续统计老区中存活对象的数量

```log
Operations: 20000
Live objects in old region: 17718
Operations: 30000
Live objects in old region: 17622
```

因为运行的是 LRUCache 测试用例，晋升到老区的对象可能随着程序运行而不再存活。

### Concurent Mark 阶段

为了减少暂停时间，G1GC 并发地启动 Concurrent Mark 进程，该进程的 initial-mark 阶段会共用 Young GC 的暂停。之后分为三个阶段：

1. Concurrent Marking：在应用程序运行的同时，G1 会在后台标记可能需要被回收的对象。
2. Concurrent Cleanup：在标记阶段完成后，G1 会清理已标记的对象，同样是在应用程序运行的同时进行。
3. Concurrent Undo Cycle：在某些情况下，应用程序可能会在标记过程中创建新的对象或者修改现有对象的引用关系，因此需要撤销那些在并发标记过程中由于应用程序的运行而变得不必要的标记操作。

在日志中我们可以看到这些阶段：

```log
[0.214s][info][gc          ] GC(4) Pause Young (Concurrent Start) (G1 Humongous Allocation) 68M->70M(372M) 4.671ms
[0.214s][info][gc,cpu      ] GC(4) User=0.02s Sys=0.00s Real=0.00s
[0.214s][info][gc          ] GC(5) Concurrent Undo Cycle
[0.214s][info][gc,marking  ] GC(5) Concurrent Cleanup for Next Mark
Operations: 50000
[0.216s][info][gc,marking  ] GC(5) Concurrent Cleanup for Next Mark 2.117ms
[0.216s][info][gc          ] GC(5) Concurrent Undo Cycle 2.170ms
```

这一阶段只是对老区对象进行标记操作，收集收益高的若干老年代 Region。并不会实际进行 GC，所以对老区中存活对象的数量没有直接的影响。

### Prepared Mixed & Mixed GC 阶段

当老区中垃圾占比超过某一阈值时，进入 Prepare Mixed 阶段，这意味着除了年轻代之外，还会选择一部分老年代区域，在接下来的 Mixed 阶段进行清理。
`[0.564s][info][gc,start    ] GC(11) Pause Young (Prepare Mixed) (G1 Evacuation Pause)`
在 Prepare Mixed 阶段之后，会进行 Mixed GC ，此阶段会将 Prepare 阶段选择老年代的区域与年轻代一起进行清理。
`[0.680s][info][gc,start    ] GC(12) Pause Young (Mixed) (G1 Evacuation Pause)`

查看日志，可以看出在 GC(11)前后，老区数量增加了 68 个，老区中存活对象数增加了 57720 个。但在 GC(12) 之后，老区数量只增加了 37 个，老区中存活对象数只增加了 31822 个。其中 GC(11)是一次 Young GC，而 GC(12)是 Mixed GC。通常来说，因为堆中的对象会越来越多，越是晚进行的 GC 越会增加更多的老区存活对象数。但 GC(11)和 GC(12)不满足这样的规律。
这是因为 GC(11)是一轮 Young GC，它只回收年轻代并将一些存活的对象晋升到老年代，因此老年代对象数量显著增加。而 GC(12)是一轮 Mixed GC，它在晋升年轻代对象的同时，还选择性地回收老年代中的部分区域，因此老年代区域的增加幅度较小。
至于为什么存活对象的增加幅度也减少了，猜测可能有两点原因：

1. GC(12)并没有只对年轻代进行 GC，导致从年轻代晋升至老区的对象数量减少。
2. GC(12)使得老区中部分不再存活的对象立即被清理，使得统计时老区中存活数量减少。

```log
Operations: 260000
Live objects in old region: 184057
[0.610s][info][gc,heap     ] GC(11) Old regions: 227->295
Operations: 270000
Live objects in old region: 241777
Operations: 290000
Live objects in old region: 239645
[0.692s][info][gc,heap     ] GC(12) Old regions: 295->332
Operations: 300000
Live objects in old region: 271467
```

### Pause Full GC 阶段

当 Mixed GC 不足以解决老区空间不足的问题时，可能会进行 Pause Full GC 全堆 GC。
Pause Full GC 分为标记活对象、准备压缩、调整指针以及压缩堆等四个阶段。

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

`[12.578s][info][gc,heap        ] GC(42) Old regions: 1925->1458`
可以发现，该 GC 完成之后老区反而减少了，这是因为这是一次全堆 GC，用于解决老区空间不足的问题。大量数据从老区中被回收，同时压缩过程会移动老区中的对象减少内存碎片，这就使得老区数量大幅度减少。
