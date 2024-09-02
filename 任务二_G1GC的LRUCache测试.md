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

新增的 Whitebox api：为了统计老区每个 Region 的存活率，我新增了一个 Whitebox api `g1GetOldRegionAddress();`，用于统计老区的存活率。首先在 Whitebox.java 中声明这一 native 方法，之后在 Whitebox.cpp 中注册并实现这一方法。在 WB_G1GetOldRegionAddress 中，我定义了一个 HeapRegionClosure 的子类 OldRegionPrinter()，并重写了 do_heap_region 方法用来遍历所有的 Region。对每个 Old Region，我用((hr->used() - hr->garbage_bytes()) \* 100) / hr->capacity()的方式计算其存活率。

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
[0.191s][info][gc,phases   ] GC(0)   Pre Evacuate Collection Set: 0.1ms
[0.191s][info][gc,phases   ] GC(0)   Merge Heap Roots: 0.2ms
[0.191s][info][gc,phases   ] GC(0)   Evacuate Collection Set: 7.4ms
[0.191s][info][gc,phases   ] GC(0)   Post Evacuate Collection Set: 0.4ms
[0.191s][info][gc,phases   ] GC(0)   Other: 0.7ms
```

G1GC 对 Young Generation 的回收，通常会增加老区的数量，也会增加老区中存活对象的数量，因为一部分 Young Generation 的对象会被复制到老区中。
例如日志中在没做任何 GC 之前，老区的存活对象数量为 0。

```log
Operations: 10000
Live objects in old region: 0
```

当 GC(1)完成之后，老区中存活对象的数量变为了 11763。

```log
[0.191s][info][gc,cpu      ] GC(0) User=0.01s Sys=0.02s Real=0.01s
Operations: 20000
Live objects in old region: 11763
region 1    liveness: 100%   region 2    liveness: 100%   region 3    liveness: 100%
region 4    liveness: 100%   region 5    liveness: 100%   region 6    liveness: 100%
region 7    liveness: 100%   region 8    liveness: 100%   region 9    liveness: 100%
region 10   liveness: 100%   region 11   liveness: 100%   region 12   liveness: 100%
region 13   liveness: 100%   region 14   liveness: 100%   region 15   liveness: 38 %
```

### GC 不运行阶段

当没有进行 GC 时，持续统计老区中存活对象的数量

```log
Operations: 40000
Live objects in old region: 35422
Operations: 50000
Live objects in old region: 35266
```

因为运行的是 LRUCache 测试用例，晋升到老区的对象可能随着程序运行而不再存活。

### Concurent Mark 阶段

为了减少暂停时间，G1GC 并发地启动 Concurrent Mark 进程，该进程的 initial-mark 阶段会共用 Young GC 的暂停。之后分为三个阶段：

1. Concurrent Marking：在应用程序运行的同时，G1 会在后台标记可能需要被回收的对象。
2. Concurrent Cleanup：在标记阶段完成后，G1 会清理已标记的对象，同样是在应用程序运行的同时进行。
3. Concurrent Undo Cycle：在某些情况下，应用程序可能会在标记过程中创建新的对象或者修改现有对象的引用关系，因此需要撤销那些在并发标记过程中由于应用程序的运行而变得不必要的标记操作。

在日志中我们可以看到这些阶段：

```log
[0.255s][info][gc          ] GC(4) Pause Young (Concurrent Start) (G1 Evacuation Pause) 83M->84M(372M) 5.755ms
[0.271s][info][gc,cpu      ] GC(5) User=0.00s Sys=0.00s Real=0.00s
[0.271s][info][gc,marking  ] GC(5) Concurrent Cleanup for Next Mark
[0.272s][info][gc,marking  ] GC(5) Concurrent Cleanup for Next Mark 1.744ms
```

这一阶段只是对老区对象进行标记操作，收集收益高的若干老年代 Region。并不会实际进行 GC，但老区中一些对象这时候会被定义为 garbge_byte。使得老区的存活率下降。
在 GC(5) 完成之前，老区的存活率大多是 100%。

```log
Operations: 60000
Live objects in old region: 48650
region 1    liveness: 100%   region 2    liveness: 100%   region 3    liveness: 100%
region 4    liveness: 100%   region 5    liveness: 100%   region 6    liveness: 100%
region 7    liveness: 100%   region 8    liveness: 100%   region 9    liveness: 100%
region 10   liveness: 100%   region 11   liveness: 100%   region 12   liveness: 100%
region 13   liveness: 100%   region 14   liveness: 100%   region 15   liveness: 100%
region 16   liveness: 100%   region 17   liveness: 100%   region 18   liveness: 100%
region 19   liveness: 100%   region 20   liveness: 100%   region 21   liveness: 100%
region 22   liveness: 100%   region 23   liveness: 100%   region 24   liveness: 100%
region 25   liveness: 100%   region 26   liveness: 100%   region 27   liveness: 100%
region 28   liveness: 100%   region 29   liveness: 100%   region 30   liveness: 100%
region 31   liveness: 100%   region 32   liveness: 100%   region 33   liveness: 100%
region 34   liveness: 100%   region 35   liveness: 100%   region 36   liveness: 100%
region 37   liveness: 100%   region 38   liveness: 100%   region 39   liveness: 100%
region 40   liveness: 100%   region 41   liveness: 100%   region 42   liveness: 100%
region 43   liveness: 100%   region 44   liveness: 100%   region 45   liveness: 100%
region 46   liveness: 100%   region 47   liveness: 100%   region 48   liveness: 100%
region 49   liveness: 100%   region 50   liveness: 100%   region 51   liveness: 100%
region 52   liveness: 100%   region 53   liveness: 100%   region 54   liveness: 100%
region 55   liveness: 100%   region 56   liveness: 100%   region 57   liveness: 100%
region 58   liveness: 100%   region 59   liveness: 100%   region 60   liveness: 100%
region 61   liveness: 50 %
```

而在 GC(5) 完成之后，老区的存活率则大多有所下降。且注意在 GC(5)这个 Concurrent Mark GC 之前的几次 GC，统计老区存活率时没有出现过存活率下降的情况。

```log
Operations: 70000
Live objects in old region: 58439
region 1    liveness: 97 %   region 2    liveness: 97 %   region 3    liveness: 97 %
region 4    liveness: 90 %   region 5    liveness: 96 %   region 6    liveness: 97 %
region 7    liveness: 96 %   region 8    liveness: 96 %   region 9    liveness: 96 %
region 10   liveness: 96 %   region 11   liveness: 97 %   region 12   liveness: 97 %
region 13   liveness: 96 %   region 14   liveness: 86 %   region 15   liveness: 94 %
region 16   liveness: 98 %   region 17   liveness: 99 %   region 18   liveness: 98 %
region 19   liveness: 98 %   region 20   liveness: 98 %   region 21   liveness: 98 %
region 22   liveness: 98 %   region 23   liveness: 98 %   region 24   liveness: 98 %
region 25   liveness: 74 %   region 26   liveness: 61 %   region 27   liveness: 98 %
region 28   liveness: 98 %   region 29   liveness: 63 %   region 30   liveness: 0  %
region 31   liveness: 99 %   region 32   liveness: 98 %   region 33   liveness: 98 %
region 34   liveness: 99 %   region 35   liveness: 98 %   region 36   liveness: 98 %
region 37   liveness: 98 %   region 38   liveness: 99 %   region 39   liveness: 98 %
region 40   liveness: 99 %   region 41   liveness: 99 %   region 42   liveness: 66 %
region 43   liveness: 99 %   region 44   liveness: 11 %   region 45   liveness: 50 %
region 46   liveness: 99 %   region 47   liveness: 99 %   region 48   liveness: 99 %
region 49   liveness: 99 %   region 50   liveness: 99 %   region 51   liveness: 99 %
region 52   liveness: 99 %   region 53   liveness: 99 %   region 54   liveness: 99 %
region 55   liveness: 99 %   region 56   liveness: 99 %   region 57   liveness: 84 %
region 58   liveness: 99 %   region 59   liveness: 62 %   region 60   liveness: 87 %
region 61   liveness: 52 %   region 62   liveness: 100%   region 63   liveness: 100%
region 64   liveness: 100%   region 65   liveness: 100%   region 66   liveness: 100%
region 67   liveness: 100%   region 68   liveness: 100%   region 69   liveness: 100%
region 70   liveness: 100%   region 71   liveness: 100%   region 72   liveness: 100%
region 73   liveness: 50 %
```

### Prepared Mixed & Mixed GC 阶段

当老区中垃圾占比超过某一阈值时，进入 Prepare Mixed 阶段，这意味着除了年轻代之外，还会选择一部分老年代区域，在接下来的 Mixed 阶段进行清理。
`[0.293s][info][gc,start    ] GC(6) Pause Young (Prepare Mixed) (G1 Evacuation Pause)`
在 Prepare Mixed 阶段之后，会进行 Mixed GC ，此阶段会将 Prepare 阶段选择老年代的区域与年轻代一起进行清理。
`[0.316s][info][gc,start    ] GC(7) Pause Young (Mixed) (G1 Evacuation Pause)`

查看日志

```log
Operations: 90000
Live objects in old region: 57899
[0.308s][info][gc,heap     ] GC(6) Old regions: 73->108
Operations: 100000
Live objects in old region: 88325
[0.323s][info][gc,heap     ] GC(7) Old regions: 108->122
Operations: 110000
Live objects in old region: 99834
```

可以看出在 GC(6)前后，老区数量增加了 108 - 73 = 35 个，老区中存活对象数增加了 88325 - 57899 = 30172 个。但在 GC(7) 之后，老区数量只增加了 122 - 108 = 14 个，老区中存活对象数只增加了 99834 - 88325 = 11509 个。其中区别在于 GC(6)是一次 Young GC, 而 GC(7)是 Mixed GC。
通常来说，因为堆中的对象会越来越多，越是晚进行的 GC 越会增加更多的老区存活对象数。但 GC(6)和 GC(7)不满足这样的规律。
这是因为 GC(6)是一轮 Young GC，它只回收年轻代并将一些存活的对象晋升到老年代，因此老年代对象数量显著增加。而 GC(7)是一轮 Mixed GC，它在晋升年轻代对象的同时，还选择性地回收老年代中的部分区域，因此老年代区域的增加幅度较小。
至于为什么存活对象的增加幅度也减少了，猜测可能有两点原因：

1. GC(12)并没有只对年轻代进行 GC，导致从年轻代晋升至老区的对象数量减少。
2. GC(12)使得老区中部分不再存活的对象立即被清理，使得统计时老区中存活数量减少。

查看日志中调用 whitebox api 得到的老区存活率的统计。

```log
Operations: 100000
Live objects in old region: 88325
region 1    liveness: 97 %   region 2    liveness: 97 %   region 3    liveness: 97 %
region 4    liveness: 90 %   region 5    liveness: 96 %   region 6    liveness: 97 %
region 7    liveness: 96 %   region 8    liveness: 96 %   region 9    liveness: 96 %
region 10   liveness: 96 %   region 11   liveness: 97 %   region 12   liveness: 97 %
region 13   liveness: 96 %   region 14   liveness: 86 %   region 15   liveness: 94 %
region 16   liveness: 98 %   region 17   liveness: 99 %   region 18   liveness: 98 %
region 19   liveness: 98 %   region 20   liveness: 98 %   region 21   liveness: 98 %
region 22   liveness: 98 %   region 23   liveness: 98 %   region 24   liveness: 98 %
region 25   liveness: 74 %   region 26   liveness: 61 %   region 27   liveness: 98 %
region 28   liveness: 98 %   region 29   liveness: 63 %   region 30   liveness: 0  %
region 31   liveness: 99 %   region 32   liveness: 98 %   region 33   liveness: 98 %
region 34   liveness: 99 %   region 35   liveness: 98 %   region 36   liveness: 98 %
region 37   liveness: 98 %   region 38   liveness: 99 %   region 39   liveness: 98 %
region 40   liveness: 99 %   region 41   liveness: 99 %   region 42   liveness: 66 %
region 43   liveness: 99 %   region 44   liveness: 11 %   region 45   liveness: 50 %
region 46   liveness: 99 %   region 47   liveness: 99 %   region 48   liveness: 99 %
region 49   liveness: 99 %   region 50   liveness: 99 %   region 51   liveness: 99 %
region 52   liveness: 99 %   region 53   liveness: 99 %   region 54   liveness: 99 %
region 55   liveness: 99 %   region 56   liveness: 99 %   region 57   liveness: 84 %
region 58   liveness: 99 %   region 59   liveness: 62 %   region 60   liveness: 87 %
region 61   liveness: 52 %   region 62   liveness: 100%   region 63   liveness: 100%
region 64   liveness: 100%   region 65   liveness: 100%   region 66   liveness: 100%
region 67   liveness: 100%   region 68   liveness: 100%   region 69   liveness: 100%
region 70   liveness: 100%   region 71   liveness: 100%   region 72   liveness: 100%
region 73   liveness: 50 %   region 74   liveness: 100%   region 75   liveness: 100%
region 76   liveness: 100%   region 77   liveness: 100%   region 78   liveness: 100%
region 79   liveness: 100%   region 80   liveness: 100%   region 81   liveness: 100%
region 82   liveness: 100%   region 83   liveness: 100%   region 84   liveness: 100%
region 85   liveness: 100%   region 86   liveness: 100%   region 87   liveness: 100%
region 88   liveness: 100%   region 89   liveness: 100%   region 90   liveness: 100%
region 91   liveness: 100%   region 92   liveness: 100%   region 93   liveness: 100%
region 94   liveness: 100%   region 95   liveness: 100%   region 96   liveness: 100%
region 97   liveness: 100%   region 98   liveness: 100%   region 99   liveness: 100%
region 100  liveness: 100%   region 101  liveness: 100%   region 102  liveness: 100%
region 103  liveness: 100%   region 104  liveness: 100%   region 105  liveness: 100%
region 106  liveness: 100%   region 107  liveness: 100%   region 108  liveness: 67 %

Operations: 110000
Live objects in old region: 99834
region 1    liveness: 97 %   region 2    liveness: 97 %   region 3    liveness: 97 %
region 4    liveness: 90 %   region 5    liveness: 96 %   region 6    liveness: 97 %
region 7    liveness: 96 %   region 8    liveness: 96 %   region 9    liveness: 96 %
region 10   liveness: 96 %   region 11   liveness: 97 %   region 12   liveness: 97 %
region 13   liveness: 96 %   region 14   liveness: 86 %   region 15   liveness: 94 %
region 16   liveness: 98 %   region 17   liveness: 99 %   region 18   liveness: 98 %
region 19   liveness: 98 %   region 20   liveness: 98 %   region 21   liveness: 98 %
region 22   liveness: 98 %   region 23   liveness: 98 %   region 24   liveness: 98 %
region 25   liveness: 74 %   region 26   liveness: 61 %   region 27   liveness: 98 %
region 28   liveness: 98 %   region 29   liveness: 63 %   region 30   liveness: 99 %
region 31   liveness: 98 %   region 32   liveness: 98 %   region 33   liveness: 99 %
region 34   liveness: 98 %   region 35   liveness: 98 %   region 36   liveness: 98 %
region 37   liveness: 99 %   region 38   liveness: 98 %   region 39   liveness: 99 %
region 40   liveness: 99 %   region 41   liveness: 66 %   region 42   liveness: 99 %
region 43   liveness: 11 %   region 44   liveness: 50 %   region 45   liveness: 99 %
region 46   liveness: 99 %   region 47   liveness: 99 %   region 48   liveness: 99 %
region 49   liveness: 99 %   region 50   liveness: 99 %   region 51   liveness: 99 %
region 52   liveness: 99 %   region 53   liveness: 99 %   region 54   liveness: 99 %
region 55   liveness: 99 %   region 56   liveness: 84 %   region 57   liveness: 99 %
region 58   liveness: 62 %   region 59   liveness: 87 %   region 60   liveness: 52 %
region 61   liveness: 100%   region 62   liveness: 100%   region 63   liveness: 100%
region 64   liveness: 100%   region 65   liveness: 100%   region 66   liveness: 100%
region 67   liveness: 100%   region 68   liveness: 100%   region 69   liveness: 100%
region 70   liveness: 100%   region 71   liveness: 100%   region 72   liveness: 50 %
region 73   liveness: 100%   region 74   liveness: 100%   region 75   liveness: 100%
region 76   liveness: 100%   region 77   liveness: 100%   region 78   liveness: 100%
region 79   liveness: 100%   region 80   liveness: 100%   region 81   liveness: 100%
region 82   liveness: 100%   region 83   liveness: 100%   region 84   liveness: 100%
region 85   liveness: 100%   region 86   liveness: 100%   region 87   liveness: 100%
region 88   liveness: 100%   region 89   liveness: 100%   region 90   liveness: 100%
region 91   liveness: 100%   region 92   liveness: 100%   region 93   liveness: 100%
region 94   liveness: 100%   region 95   liveness: 100%   region 96   liveness: 100%
region 97   liveness: 100%   region 98   liveness: 100%   region 99   liveness: 100%
region 100  liveness: 100%   region 101  liveness: 100%   region 102  liveness: 100%
region 103  liveness: 100%   region 104  liveness: 100%   region 105  liveness: 100%
region 106  liveness: 100%   region 107  liveness: 100%   region 108  liveness: 100%
region 109  liveness: 100%   region 110  liveness: 100%   region 111  liveness: 100%
region 112  liveness: 100%   region 113  liveness: 100%   region 114  liveness: 100%
region 115  liveness: 100%   region 116  liveness: 100%   region 117  liveness: 100%
region 118  liveness: 100%   region 119  liveness: 100%   region 120  liveness: 100%
region 121  liveness: 100%   region 122  liveness: 50 %
```

可以发现，MixedGC 之后，部分老区的存活率出现了显著变化。例如
Region 30、42、45、57 等区域存活率出现显著提升，表明这些老区的剩余空间在 GC(7)的过程中被充分利用了。Region 58、60 等区域的存活率有明显下降，这说明 GC(7)过程中该区域的对象被回收或移动到了其他区域。
这种变化使得老区空间得到释放。

### Pause Full GC 阶段

当 Mixed GC 不足以解决老区空间不足的问题时，可能会进行 Pause Full GC 全堆 GC。
Pause Full GC 分为标记活对象、准备压缩、调整指针以及压缩堆等四个阶段。

下面的日志来自任务一\_g1gc.jtr

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
可以发现，该 GC 完成之后老区反而减少了，这是因为这是一次全堆 GC，用于解决老区空间不足的问题。大量数据从老区中被回收，同时压缩过程会移动老区中的对象减少内存碎片，这就使得老区数量大幅度减少。这也是 G1GC 中的 GC 过程中老区数量第一次出现下降的情况，这说明全堆 GC 相较 Mixed GC 更能够极大地释放老区的空间。
