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
[0.182s][info][gc,phases   ] GC(0)   Pre Evacuate Collection Set: 0.1ms
[0.182s][info][gc,phases   ] GC(0)   Merge Heap Roots: 0.2ms
[0.182s][info][gc,phases   ] GC(0)   Evacuate Collection Set: 7.2ms
[0.182s][info][gc,phases   ] GC(0)   Post Evacuate Collection Set: 0.5ms
[0.182s][info][gc,phases   ] GC(0)   Other: 0.6ms
```

G1GC 对 Young Generation 的回收，通常会增加老区的数量，也会增加老区中存活对象的数量，因为一部分 Young Generation 的对象会被复制到老区中。
例如日志中在没做任何 GC 之前，老区的存活对象数量为 0。

```log
Operations: 10000
Live objects in old region: 0
```

当 GC(1)完成之后，老区中存活对象的数量变为了 11763。

```log
[0.188s][info][gc,cpu      ] GC(1) User=0.01s Sys=0.00s Real=0.00s
Operations: 20000
Live objects in old region: 17766
region 1    liveness: 100%   region 2    liveness: 100%   region 3    liveness: 100%
region 4    liveness: 100%   region 5    liveness: 100%   region 6    liveness: 100%
region 7    liveness: 100%   region 8    liveness: 100%   region 9    liveness: 100%
region 10   liveness: 100%   region 11   liveness: 100%   region 12   liveness: 100%
region 13   liveness: 100%   region 14   liveness: 100%   region 15   liveness: 100%
region 16   liveness: 100%   region 17   liveness: 100%   region 18   liveness: 100%
region 19   liveness: 100%   region 20   liveness: 100%   region 21   liveness: 100%
region 22   liveness: 100%   region 23   liveness: 50 %
```

### GC 不运行阶段

当没有进行 GC 时，持续统计老区中存活对象的数量

```log
Operations: 20000
Live objects in old region: 17766
Operations: 30000
Live objects in old region: 17685
[0.207s][info][gc,cpu      ] GC(2) User=0.00s Sys=0.02s Real=0.01s
Operations: 40000
Live objects in old region: 30778
Operations: 50000
Live objects in old region: 30635
```

因为运行的是 LRUCache 测试用例，晋升到老区的对象可能随着程序运行而不再存活。

### Concurent Mark 阶段

为了减少暂停时间，G1GC 并发地启动 Concurrent Mark 进程，该进程的 initial-mark 阶段会共用 Young GC 的暂停。之后分为三个阶段：

1. Concurrent Marking：在应用程序运行的同时，G1 会在后台标记可能需要被回收的对象。
2. Concurrent Cleanup：在标记阶段完成后，G1 会清理已标记的对象，同样是在应用程序运行的同时进行。
3. Concurrent Undo Cycle：在某些情况下，应用程序可能会在标记过程中创建新的对象或者修改现有对象的引用关系，因此需要撤销那些在并发标记过程中由于应用程序的运行而变得不必要的标记操作。

在日志中我们可以看到这些阶段：

```log
[0.246s][info][gc          ] GC(4) Pause Young (Concurrent Start) (G1 Evacuation Pause) 82M->82M(372M) 7.098ms
[0.246s][info][gc,cpu      ] GC(4) User=0.04s Sys=0.00s Real=0.01s
[0.246s][info][gc          ] GC(5) Concurrent Mark Cycle
[0.246s][info][gc,marking  ] GC(5) Concurrent Clear Claimed Marks
```

这一阶段只是对老区对象进行标记操作，收集收益高的若干老年代 Region。并不会实际进行 GC，但老区中一些对象这时候会被定义为 garbge_byte。使得老区的存活率下降。
在 GC(5) 完成之前，老区的存活率大多是 100%。

```log
Operations: 60000
Live objects in old region: 46782
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
```

而在 GC(5) 完成之后，老区的存活率则大多有所下降。且注意在 GC(5)这个 Concurrent Mark GC 之前的几次 GC，统计老区存活率时没有出现过存活率下降的情况。

```log
Operations: 70000
Live objects in old region: 58368
region 1    liveness: 96 %   region 2    liveness: 97 %   region 3    liveness: 96 %
region 4    liveness: 97 %   region 5    liveness: 96 %   region 6    liveness: 97 %
region 7    liveness: 96 %   region 8    liveness: 97 %   region 9    liveness: 96 %
region 10   liveness: 97 %   region 11   liveness: 97 %   region 12   liveness: 96 %
region 13   liveness: 96 %   region 14   liveness: 86 %   region 15   liveness: 92 %
region 16   liveness: 98 %   region 17   liveness: 98 %   region 18   liveness: 98 %
region 19   liveness: 97 %   region 20   liveness: 97 %   region 21   liveness: 82 %
region 22   liveness: 37 %   region 23   liveness: 55 %   region 24   liveness: 99 %
region 25   liveness: 98 %   region 26   liveness: 98 %   region 27   liveness: 98 %
region 28   liveness: 98 %   region 29   liveness: 98 %   region 30   liveness: 98 %
region 31   liveness: 99 %   region 32   liveness: 98 %   region 33   liveness: 98 %
region 34   liveness: 98 %   region 35   liveness: 98 %   region 36   liveness: 74 %
region 37   liveness: 94 %   region 38   liveness: 30 %   region 39   liveness: 99 %
region 40   liveness: 99 %   region 41   liveness: 99 %   region 42   liveness: 99 %
region 43   liveness: 99 %   region 44   liveness: 99 %   region 45   liveness: 99 %
region 46   liveness: 99 %   region 47   liveness: 99 %   region 48   liveness: 99 %
region 49   liveness: 98 %   region 50   liveness: 99 %   region 51   liveness: 99 %
region 52   liveness: 98 %   region 53   liveness: 99 %   region 54   liveness: 99 %
region 55   liveness: 84 %   region 56   liveness: 83 %   region 57   liveness: 16 %
region 58   liveness: 100%   region 59   liveness: 100%   region 60   liveness: 100%
region 61   liveness: 100%   region 62   liveness: 100%   region 63   liveness: 100%
region 64   liveness: 100%   region 65   liveness: 100%   region 66   liveness: 100%
region 67   liveness: 100%   region 68   liveness: 100%   region 69   liveness: 100%
region 70   liveness: 100%   region 71   liveness: 50 %
```

### Prepared Mixed & Mixed GC 阶段

当老区中垃圾占比超过某一阈值时，进入 Prepare Mixed 阶段，这意味着除了年轻代之外，还会选择一部分老年代区域，在接下来的 Mixed 阶段进行清理。
`[0.278s][info][gc,start    ] GC(6) Pause Young (Prepare Mixed) (G1 Evacuation Pause)`
在 Prepare Mixed 阶段之后，会进行 Mixed GC ，此阶段会将 Prepare 阶段选择老年代的区域与年轻代一起进行清理。
`[0.299s][info][gc,start    ] GC(7) Pause Young (Mixed) (G1 Evacuation Pause)`

查看日志

```log
Operations: 90000
Live objects in old region: 57833
[0.291s][info][gc,heap     ] GC(6) Old regions: 71->105
Operations: 100000
Live objects in old region: 87348
[0.305s][info][gc,heap     ] GC(7) Old regions: 105->119
Operations: 110000
Live objects in old region: 98837
```

可以看出在 GC(6)前后，老区数量增加了 105 - 71 = 34 个，老区中存活对象数增加了 87348 - 57833 = 29515 个。但在 GC(7) 之后，老区数量只增加了 119 - 105 = 14 个，老区中存活对象数只增加了 98837 - 87348 = 11489 个。其中区别在于 GC(6)是一次 Young GC, 而 GC(7)是 Mixed GC。
通常来说，因为堆中的对象会越来越多，越是晚进行的 GC 越会增加更多的老区存活对象数。但 GC(6)和 GC(7)不满足这样的规律。
这是因为 GC(6)是一轮 Young GC，它只回收年轻代并将一些存活的对象晋升到老年代，因此老年代对象数量显著增加。而 GC(7)是一轮 Mixed GC，它在晋升年轻代对象的同时，还选择性地回收老年代中的部分区域，因此老年代区域的增加幅度较小。
至于为什么存活对象的增加幅度也减少了，猜测可能有两点原因：

1. GC(7)并没有只对年轻代进行 GC，导致从年轻代晋升至老区的对象数量减少。
2. GC(7)使得老区中部分不再存活的对象立即被清理，使得统计时老区中存活数量减少。

查看日志中调用 whitebox api 得到的老区存活率的统计。

```log
Operations: 100000
Live objects in old region: 87348
region 1    liveness: 96 %   region 2    liveness: 97 %   region 3    liveness: 96 %
region 4    liveness: 97 %   region 5    liveness: 96 %   region 6    liveness: 97 %
region 7    liveness: 96 %   region 8    liveness: 97 %   region 9    liveness: 96 %
region 10   liveness: 97 %   region 11   liveness: 97 %   region 12   liveness: 96 %
region 13   liveness: 96 %   region 14   liveness: 86 %   region 15   liveness: 92 %
region 16   liveness: 98 %   region 17   liveness: 98 %   region 18   liveness: 98 %
region 19   liveness: 97 %   region 20   liveness: 97 %   region 21   liveness: 82 %
region 22   liveness: 37 %   region 23   liveness: 55 %   region 24   liveness: 99 %
region 25   liveness: 98 %   region 26   liveness: 98 %   region 27   liveness: 98 %
region 28   liveness: 98 %   region 29   liveness: 98 %   region 30   liveness: 98 %
region 31   liveness: 99 %   region 32   liveness: 98 %   region 33   liveness: 98 %
region 34   liveness: 98 %   region 35   liveness: 98 %   region 36   liveness: 74 %
region 37   liveness: 94 %   region 38   liveness: 30 %   region 39   liveness: 99 %
region 40   liveness: 99 %   region 41   liveness: 99 %   region 42   liveness: 99 %
region 43   liveness: 99 %   region 44   liveness: 99 %   region 45   liveness: 99 %
region 46   liveness: 99 %   region 47   liveness: 99 %   region 48   liveness: 99 %
region 49   liveness: 98 %   region 50   liveness: 99 %   region 51   liveness: 99 %
region 52   liveness: 98 %   region 53   liveness: 99 %   region 54   liveness: 99 %
region 55   liveness: 84 %   region 56   liveness: 83 %   region 57   liveness: 16 %
region 58   liveness: 100%   region 59   liveness: 100%   region 60   liveness: 100%
region 61   liveness: 100%   region 62   liveness: 100%   region 63   liveness: 100%
region 64   liveness: 100%   region 65   liveness: 100%   region 66   liveness: 100%
region 67   liveness: 100%   region 68   liveness: 100%   region 69   liveness: 100%
region 70   liveness: 100%   region 71   liveness: 50 %   region 72   liveness: 100%
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

Operations: 110000
Live objects in old region: 98837
region 1    liveness: 96 %   region 2    liveness: 97 %   region 3    liveness: 96 %
region 4    liveness: 97 %   region 5    liveness: 96 %   region 6    liveness: 97 %
region 7    liveness: 96 %   region 8    liveness: 97 %   region 9    liveness: 96 %
region 10   liveness: 97 %   region 11   liveness: 97 %   region 12   liveness: 96 %
region 13   liveness: 96 %   region 14   liveness: 86 %   region 15   liveness: 92 %
region 16   liveness: 98 %   region 17   liveness: 98 %   region 18   liveness: 98 %
region 19   liveness: 97 %   region 20   liveness: 97 %   region 21   liveness: 82 %
region 22   liveness: 37 %   region 23   liveness: 55 %   region 24   liveness: 99 %
region 25   liveness: 98 %   region 26   liveness: 98 %   region 27   liveness: 98 %
region 28   liveness: 98 %   region 29   liveness: 98 %   region 30   liveness: 98 %
region 31   liveness: 99 %   region 32   liveness: 98 %   region 33   liveness: 98 %
region 34   liveness: 98 %   region 35   liveness: 98 %   region 36   liveness: 74 %
region 37   liveness: 94 %   region 38   liveness: 30 %   region 39   liveness: 99 %
region 40   liveness: 99 %   region 41   liveness: 99 %   region 42   liveness: 99 %
region 43   liveness: 99 %   region 44   liveness: 99 %   region 45   liveness: 99 %
region 46   liveness: 99 %   region 47   liveness: 99 %   region 48   liveness: 99 %
region 49   liveness: 98 %   region 50   liveness: 99 %   region 51   liveness: 99 %
region 52   liveness: 98 %   region 53   liveness: 99 %   region 54   liveness: 99 %
region 55   liveness: 84 %   region 56   liveness: 83 %   region 57   liveness: 100%
region 58   liveness: 100%   region 59   liveness: 100%   region 60   liveness: 100%
region 61   liveness: 100%   region 62   liveness: 100%   region 63   liveness: 100%
region 64   liveness: 100%   region 65   liveness: 100%   region 66   liveness: 100%
region 67   liveness: 100%   region 68   liveness: 100%   region 69   liveness: 100%
region 70   liveness: 50 %   region 71   liveness: 100%   region 72   liveness: 100%
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
region 118  liveness: 100%   region 119  liveness: 100%
```

可以发现，MixedGC 之后，部分老区的存活率出现了显著变化。例如
Region 57、71 等区域存活率出现显著提升，表明这些老区的剩余空间在 GC(7)的过程中被充分利用了。Region 70 等区域的存活率有明显下降，这说明 GC(7)过程中该区域的对象被回收或移动到了其他区域。
这种变化使得老区空间得到释放。

### Pause Full GC 阶段

当 Mixed GC 不足以解决老区空间不足的问题时，可能会进行 Pause Full GC 全堆 GC。
Pause Full GC 分为标记活对象、准备压缩、调整指针以及压缩堆等四个阶段。

```log
[10.180s][info][gc,start    ] GC(40) Pause Full (G1 Compaction Pause)
[10.182s][info][gc,phases,start] GC(40) Phase 1: Mark live objects
[10.250s][info][gc,phases      ] GC(40) Phase 1: Mark live objects 68.353ms
[10.250s][info][gc,phases,start] GC(40) Phase 2: Prepare for compaction
[10.289s][info][gc,phases      ] GC(40) Phase 2: Prepare for compaction 38.396ms
[10.289s][info][gc,phases,start] GC(40) Phase 3: Adjust pointers
[10.356s][info][gc,phases      ] GC(40) Phase 3: Adjust pointers 67.557ms
[10.356s][info][gc,phases,start] GC(40) Phase 4: Compact heap
[10.504s][info][gc,phases      ] GC(40) Phase 4: Compact heap 148.127ms
[10.507s][info][gc             ] GC(40) Pause Full (G1 Compaction Pause) 1955M->1394M(1956M) 327.224ms
```

`[10.507s][info][gc,heap        ] GC(40) Old regions: 1937->1383`
可以发现，该 GC 完成之后老区反而减少了，这是因为这是一次全堆 GC，用于解决老区空间不足的问题。大量数据从老区中被回收，同时压缩过程会移动老区中的对象减少内存碎片，这就使得老区数量大幅度减少。这也是 G1GC 中的 GC 过程中老区数量第一次出现下降的情况，这说明全堆 GC 相较 Mixed GC 更能够极大地释放老区的空间。

查看日志发现，Pause Full GC 前一次统计时(Operations: 1850000)老区的存活率还参差不齐，有部分老区的存活率甚至低于 50%，但在 Pause Full GC 之后紧接着的一次统计(Operations: 1860000)，所有老区的存活率基本上都是 99%或 100%，老区的数量也减少了。这说明 Pause Full GC 非常有效地释放了老区空间。

```log
Operations: 1850000
Live objects in old region: 1165225
region 1    liveness: 76 %   region 2    liveness: 94 %   region 3    liveness: 92 %
region 4    liveness: 77 %   region 5    liveness: 78 %   region 6    liveness: 77 %
region 7    liveness: 76 %   region 8    liveness: 93 %   region 9    liveness: 76 %
region 10   liveness: 92 %   region 11   liveness: 78 %   region 12   liveness: 78 %
region 13   liveness: 95 %   region 14   liveness: 64 %   region 15   liveness: 68 %
region 16   liveness: 92 %   region 17   liveness: 96 %   region 18   liveness: 77 %
region 19   liveness: 76 %   region 20   liveness: 78 %   region 21   liveness: 78 %
region 22   liveness: 71 %   region 23   liveness: 78 %   region 24   liveness: 77 %
region 25   liveness: 78 %   region 26   liveness: 92 %   region 27   liveness: 92 %
region 28   liveness: 93 %   region 29   liveness: 94 %   region 30   liveness: 75 %
region 31   liveness: 78 %   region 32   liveness: 78 %   region 33   liveness: 76 %
region 34   liveness: 93 %   region 35   liveness: 78 %   region 36   liveness: 61 %
region 37   liveness: 72 %   region 38   liveness: 92 %   region 39   liveness: 78 %
region 40   liveness: 79 %   region 41   liveness: 79 %   region 42   liveness: 76 %
region 43   liveness: 75 %   region 44   liveness: 93 %   region 45   liveness: 76 %
region 46   liveness: 76 %   region 47   liveness: 97 %   region 48   liveness: 93 %
region 49   liveness: 94 %   region 50   liveness: 92 %   region 51   liveness: 96 %
region 52   liveness: 92 %   region 53   liveness: 93 %   region 54   liveness: 92 %
region 55   liveness: 57 %   region 56   liveness: 97 %   region 57   liveness: 92 %
region 58   liveness: 92 %   region 59   liveness: 93 %   region 60   liveness: 93 %
region 61   liveness: 93 %   region 62   liveness: 74 %   region 63   liveness: 93 %
region 64   liveness: 93 %   region 65   liveness: 92 %   region 66   liveness: 91 %
region 67   liveness: 94 %   region 68   liveness: 92 %   region 69   liveness: 93 %
region 70   liveness: 55 %   region 71   liveness: 96 %   region 72   liveness: 93 %
region 73   liveness: 76 %   region 74   liveness: 79 %   region 75   liveness: 94 %
region 76   liveness: 92 %   region 77   liveness: 75 %   region 78   liveness: 93 %
region 79   liveness: 93 %   region 80   liveness: 80 %   region 81   liveness: 93 %
region 82   liveness: 94 %   region 83   liveness: 91 %   region 84   liveness: 92 %
region 85   liveness: 92 %   region 86   liveness: 92 %   region 87   liveness: 94 %
region 88   liveness: 91 %   region 89   liveness: 93 %   region 90   liveness: 93 %
region 91   liveness: 94 %   region 92   liveness: 94 %   region 93   liveness: 91 %
region 94   liveness: 93 %   region 95   liveness: 96 %   region 96   liveness: 93 %
region 97   liveness: 92 %   region 98   liveness: 93 %   region 99   liveness: 94 %
region 100  liveness: 62 %   region 101  liveness: 67 %   region 102  liveness: 96 %
region 103  liveness: 95 %   region 104  liveness: 55 %   region 105  liveness: 78 %
region 106  liveness: 70 %   region 107  liveness: 92 %   region 108  liveness: 92 %
region 109  liveness: 94 %   region 110  liveness: 93 %   region 111  liveness: 93 %
region 112  liveness: 93 %   region 113  liveness: 94 %   region 114  liveness: 93 %
region 115  liveness: 96 %   region 116  liveness: 92 %   region 117  liveness: 92 %
region 118  liveness: 78 %   region 119  liveness: 78 %   region 120  liveness: 67 %
region 121  liveness: 96 %   region 122  liveness: 78 %   region 123  liveness: 93 %
region 124  liveness: 91 %   region 125  liveness: 92 %   region 126  liveness: 93 %
region 127  liveness: 93 %   region 128  liveness: 93 %   region 129  liveness: 92 %
region 130  liveness: 93 %   region 131  liveness: 93 %   region 132  liveness: 93 %
region 133  liveness: 92 %   region 134  liveness: 93 %   region 135  liveness: 94 %
region 136  liveness: 93 %   region 137  liveness: 93 %   region 138  liveness: 92 %
region 139  liveness: 93 %   region 140  liveness: 93 %   region 141  liveness: 93 %
region 142  liveness: 92 %   region 143  liveness: 93 %   region 144  liveness: 93 %
region 145  liveness: 94 %   region 146  liveness: 92 %   region 147  liveness: 92 %
region 148  liveness: 95 %   region 149  liveness: 93 %   region 150  liveness: 94 %
region 151  liveness: 95 %   region 152  liveness: 93 %   region 153  liveness: 92 %
region 154  liveness: 96 %   region 155  liveness: 96 %   region 156  liveness: 93 %
region 157  liveness: 94 %   region 158  liveness: 63 %   region 159  liveness: 60 %
region 160  liveness: 58 %   region 161  liveness: 78 %   region 162  liveness: 71 %
region 163  liveness: 93 %   region 164  liveness: 92 %   region 165  liveness: 93 %
region 166  liveness: 93 %   region 167  liveness: 93 %   region 168  liveness: 93 %
region 169  liveness: 92 %   region 170  liveness: 93 %   region 171  liveness: 92 %
region 172  liveness: 96 %   region 173  liveness: 91 %   region 174  liveness: 92 %
region 175  liveness: 93 %   region 176  liveness: 93 %   region 177  liveness: 92 %
region 178  liveness: 92 %   region 179  liveness: 92 %   region 180  liveness: 93 %
region 181  liveness: 94 %   region 182  liveness: 93 %   region 183  liveness: 94 %
region 184  liveness: 96 %   region 185  liveness: 95 %   region 186  liveness: 96 %
region 187  liveness: 94 %   region 188  liveness: 57 %   region 189  liveness: 57 %
region 190  liveness: 96 %   region 191  liveness: 61 %   region 192  liveness: 69 %
region 193  liveness: 69 %   region 194  liveness: 71 %   region 195  liveness: 93 %
region 196  liveness: 94 %   region 197  liveness: 93 %   region 198  liveness: 91 %
region 199  liveness: 92 %   region 200  liveness: 94 %   region 201  liveness: 94 %
region 202  liveness: 96 %   region 203  liveness: 93 %   region 204  liveness: 93 %
region 205  liveness: 96 %   region 206  liveness: 96 %   region 207  liveness: 96 %
region 208  liveness: 95 %   region 209  liveness: 61 %   region 210  liveness: 92 %
region 211  liveness: 92 %   region 212  liveness: 96 %   region 213  liveness: 72 %
region 214  liveness: 70 %   region 215  liveness: 70 %   region 216  liveness: 70 %
region 217  liveness: 68 %   region 218  liveness: 68 %   region 219  liveness: 93 %
region 220  liveness: 94 %   region 221  liveness: 92 %   region 222  liveness: 93 %
region 223  liveness: 93 %   region 224  liveness: 95 %   region 225  liveness: 92 %
region 226  liveness: 96 %   region 227  liveness: 93 %   region 228  liveness: 96 %
region 229  liveness: 96 %   region 230  liveness: 93 %   region 231  liveness: 94 %
region 232  liveness: 93 %   region 233  liveness: 93 %   region 234  liveness: 94 %
region 235  liveness: 96 %   region 236  liveness: 93 %   region 237  liveness: 96 %
region 238  liveness: 94 %   region 239  liveness: 95 %   region 240  liveness: 93 %
region 241  liveness: 93 %   region 242  liveness: 96 %   region 243  liveness: 93 %
region 244  liveness: 96 %   region 245  liveness: 94 %   region 246  liveness: 96 %
region 247  liveness: 96 %   region 248  liveness: 92 %   region 249  liveness: 93 %
region 250  liveness: 97 %   region 251  liveness: 94 %   region 252  liveness: 92 %
region 253  liveness: 96 %   region 254  liveness: 94 %   region 255  liveness: 96 %
region 256  liveness: 97 %   region 257  liveness: 93 %   region 258  liveness: 96 %
region 259  liveness: 94 %   region 260  liveness: 95 %   region 261  liveness: 96 %
region 262  liveness: 95 %   region 263  liveness: 95 %   region 264  liveness: 96 %
region 265  liveness: 95 %   region 266  liveness: 96 %   region 267  liveness: 96 %
region 268  liveness: 96 %   region 269  liveness: 96 %   region 270  liveness: 95 %
region 271  liveness: 96 %   region 272  liveness: 96 %   region 273  liveness: 97 %
region 274  liveness: 95 %   region 275  liveness: 97 %   region 276  liveness: 96 %
region 277  liveness: 97 %   region 278  liveness: 96 %   region 279  liveness: 96 %
region 280  liveness: 95 %   region 281  liveness: 96 %   region 282  liveness: 96 %
region 283  liveness: 96 %   region 284  liveness: 95 %   region 285  liveness: 96 %
region 286  liveness: 61 %   region 287  liveness: 96 %   region 288  liveness: 73 %
region 289  liveness: 76 %   region 290  liveness: 71 %   region 291  liveness: 76 %
region 292  liveness: 69 %   region 293  liveness: 71 %   region 294  liveness: 69 %
region 295  liveness: 93 %   region 296  liveness: 92 %   region 297  liveness: 96 %
region 298  liveness: 96 %   region 299  liveness: 93 %   region 300  liveness: 91 %
region 301  liveness: 93 %   region 302  liveness: 92 %   region 303  liveness: 92 %
region 304  liveness: 96 %   region 305  liveness: 96 %   region 306  liveness: 93 %
region 307  liveness: 94 %   region 308  liveness: 93 %   region 309  liveness: 95 %
region 310  liveness: 93 %   region 311  liveness: 96 %   region 312  liveness: 100%
region 313  liveness: 96 %   region 314  liveness: 95 %   region 315  liveness: 96 %
region 316  liveness: 96 %   region 317  liveness: 97 %   region 318  liveness: 92 %
region 319  liveness: 96 %   region 320  liveness: 93 %   region 321  liveness: 96 %
region 322  liveness: 97 %   region 323  liveness: 97 %   region 324  liveness: 80 %
region 325  liveness: 78 %   region 326  liveness: 92 %   region 327  liveness: 80 %
region 328  liveness: 62 %   region 329  liveness: 100%   region 330  liveness: 100%
region 331  liveness: 58 %   region 332  liveness: 77 %   region 333  liveness: 69 %
region 334  liveness: 68 %   region 335  liveness: 69 %   region 336  liveness: 92 %
region 337  liveness: 100%   region 338  liveness: 100%   region 339  liveness: 100%
region 340  liveness: 100%   region 341  liveness: 100%   region 342  liveness: 100%
region 343  liveness: 100%   region 344  liveness: 100%   region 345  liveness: 100%
region 346  liveness: 100%   region 347  liveness: 100%   region 348  liveness: 100%
region 349  liveness: 100%   region 350  liveness: 100%   region 351  liveness: 100%
region 352  liveness: 93 %   region 353  liveness: 100%   region 354  liveness: 100%
region 355  liveness: 100%   region 356  liveness: 100%   region 357  liveness: 100%
region 358  liveness: 100%   region 359  liveness: 100%   region 360  liveness: 100%
region 361  liveness: 100%   region 362  liveness: 100%   region 363  liveness: 100%
region 364  liveness: 100%   region 365  liveness: 100%   region 366  liveness: 100%
region 367  liveness: 100%   region 368  liveness: 100%   region 369  liveness: 100%
region 370  liveness: 93 %   region 371  liveness: 100%   region 372  liveness: 100%
region 373  liveness: 100%   region 374  liveness: 100%   region 375  liveness: 100%
region 376  liveness: 100%   region 377  liveness: 100%   region 378  liveness: 100%
region 379  liveness: 100%   region 380  liveness: 100%   region 381  liveness: 93 %
region 382  liveness: 100%   region 383  liveness: 100%   region 384  liveness: 100%
region 385  liveness: 100%   region 386  liveness: 100%   region 387  liveness: 100%
region 388  liveness: 100%   region 389  liveness: 100%   region 390  liveness: 100%
region 391  liveness: 100%   region 392  liveness: 100%   region 393  liveness: 100%
region 394  liveness: 100%   region 395  liveness: 100%   region 396  liveness: 100%
region 397  liveness: 100%   region 398  liveness: 100%   region 399  liveness: 100%
region 400  liveness: 100%   region 401  liveness: 100%   region 402  liveness: 100%
region 403  liveness: 100%   region 404  liveness: 60 %   region 405  liveness: 64 %
region 406  liveness: 67 %   region 407  liveness: 76 %   region 408  liveness: 67 %
region 409  liveness: 61 %   region 410  liveness: 76 %   region 411  liveness: 69 %
region 412  liveness: 70 %   region 413  liveness: 68 %   region 414  liveness: 68 %
region 415  liveness: 87 %   region 416  liveness: 86 %   region 417  liveness: 87 %
region 418  liveness: 86 %   region 419  liveness: 86 %   region 420  liveness: 95 %
region 421  liveness: 100%   region 422  liveness: 100%   region 423  liveness: 100%
region 424  liveness: 100%   region 425  liveness: 100%   region 426  liveness: 100%
region 427  liveness: 100%   region 428  liveness: 100%   region 429  liveness: 100%
region 430  liveness: 100%   region 431  liveness: 100%   region 432  liveness: 100%
region 433  liveness: 100%   region 434  liveness: 100%   region 435  liveness: 100%
region 436  liveness: 100%   region 437  liveness: 100%   region 438  liveness: 100%
region 439  liveness: 100%   region 440  liveness: 100%   region 441  liveness: 100%
region 442  liveness: 100%   region 443  liveness: 100%   region 444  liveness: 100%
region 445  liveness: 100%   region 446  liveness: 100%   region 447  liveness: 100%
region 448  liveness: 100%   region 449  liveness: 100%   region 450  liveness: 100%
region 451  liveness: 100%   region 452  liveness: 100%   region 453  liveness: 100%
region 454  liveness: 100%   region 455  liveness: 100%   region 456  liveness: 100%
region 457  liveness: 64 %   region 458  liveness: 67 %   region 459  liveness: 63 %
region 460  liveness: 63 %   region 461  liveness: 68 %   region 462  liveness: 68 %
region 463  liveness: 68 %   region 464  liveness: 76 %   region 465  liveness: 66 %
region 466  liveness: 78 %   region 467  liveness: 76 %   region 468  liveness: 70 %
region 469  liveness: 70 %   region 470  liveness: 100%   region 471  liveness: 67 %
region 472  liveness: 100%   region 473  liveness: 100%   region 474  liveness: 100%
region 475  liveness: 100%   region 476  liveness: 100%   region 477  liveness: 100%
region 478  liveness: 100%   region 479  liveness: 66 %   region 480  liveness: 100%
region 481  liveness: 100%   region 482  liveness: 66 %   region 483  liveness: 66 %
region 484  liveness: 66 %   region 485  liveness: 65 %   region 486  liveness: 64 %
region 487  liveness: 66 %   region 488  liveness: 65 %   region 489  liveness: 64 %
region 490  liveness: 63 %   region 491  liveness: 66 %   region 492  liveness: 70 %
region 493  liveness: 71 %   region 494  liveness: 63 %   region 495  liveness: 65 %
region 496  liveness: 75 %   region 497  liveness: 64 %   region 498  liveness: 77 %
region 499  liveness: 76 %   region 500  liveness: 75 %   region 501  liveness: 75 %
region 502  liveness: 76 %   region 503  liveness: 68 %   region 504  liveness: 68 %
region 505  liveness: 67 %   region 506  liveness: 67 %   region 507  liveness: 68 %
region 508  liveness: 68 %   region 509  liveness: 68 %   region 510  liveness: 68 %
region 511  liveness: 66 %   region 512  liveness: 68 %   region 513  liveness: 69 %
region 514  liveness: 67 %   region 515  liveness: 68 %   region 516  liveness: 66 %
region 517  liveness: 66 %   region 518  liveness: 66 %   region 519  liveness: 66 %
region 520  liveness: 70 %   region 521  liveness: 66 %   region 522  liveness: 68 %
region 523  liveness: 67 %   region 524  liveness: 68 %   region 525  liveness: 67 %
region 526  liveness: 68 %   region 527  liveness: 67 %   region 528  liveness: 66 %
region 529  liveness: 64 %   region 530  liveness: 68 %   region 531  liveness: 65 %
region 532  liveness: 66 %   region 533  liveness: 66 %   region 534  liveness: 67 %
region 535  liveness: 69 %   region 536  liveness: 68 %   region 537  liveness: 67 %
region 538  liveness: 69 %   region 539  liveness: 68 %   region 540  liveness: 66 %
region 541  liveness: 65 %   region 542  liveness: 65 %   region 543  liveness: 65 %
region 544  liveness: 66 %   region 545  liveness: 66 %   region 546  liveness: 69 %
region 547  liveness: 69 %   region 548  liveness: 72 %   region 549  liveness: 74 %
region 550  liveness: 69 %   region 551  liveness: 66 %   region 552  liveness: 69 %
region 553  liveness: 68 %   region 554  liveness: 67 %   region 555  liveness: 65 %
region 556  liveness: 70 %   region 557  liveness: 68 %   region 558  liveness: 79 %
region 559  liveness: 75 %   region 560  liveness: 74 %   region 561  liveness: 71 %
region 562  liveness: 71 %   region 563  liveness: 75 %   region 564  liveness: 77 %
region 565  liveness: 76 %   region 566  liveness: 76 %   region 567  liveness: 76 %
region 568  liveness: 75 %   region 569  liveness: 65 %   region 570  liveness: 68 %
region 571  liveness: 72 %   region 572  liveness: 69 %   region 573  liveness: 70 %
region 574  liveness: 69 %   region 575  liveness: 70 %   region 576  liveness: 67 %
region 577  liveness: 66 %   region 578  liveness: 68 %   region 579  liveness: 67 %
region 580  liveness: 67 %   region 581  liveness: 71 %   region 582  liveness: 66 %
region 583  liveness: 68 %   region 584  liveness: 70 %   region 585  liveness: 67 %
region 586  liveness: 68 %   region 587  liveness: 69 %   region 588  liveness: 65 %
region 589  liveness: 68 %   region 590  liveness: 72 %   region 591  liveness: 67 %
region 592  liveness: 68 %   region 593  liveness: 67 %   region 594  liveness: 68 %
region 595  liveness: 71 %   region 596  liveness: 66 %   region 597  liveness: 67 %
region 598  liveness: 66 %   region 599  liveness: 69 %   region 600  liveness: 66 %
region 601  liveness: 66 %   region 602  liveness: 65 %   region 603  liveness: 65 %
region 604  liveness: 77 %   region 605  liveness: 72 %   region 606  liveness: 67 %
region 607  liveness: 69 %   region 608  liveness: 74 %   region 609  liveness: 74 %
region 610  liveness: 71 %   region 611  liveness: 80 %   region 612  liveness: 91 %
region 613  liveness: 80 %   region 614  liveness: 65 %   region 615  liveness: 78 %
region 616  liveness: 75 %   region 617  liveness: 79 %   region 618  liveness: 75 %
region 619  liveness: 79 %   region 620  liveness: 70 %   region 621  liveness: 73 %
region 622  liveness: 69 %   region 623  liveness: 69 %   region 624  liveness: 70 %
region 625  liveness: 70 %   region 626  liveness: 68 %   region 627  liveness: 70 %
region 628  liveness: 70 %   region 629  liveness: 69 %   region 630  liveness: 71 %
region 631  liveness: 68 %   region 632  liveness: 70 %   region 633  liveness: 71 %
region 634  liveness: 70 %   region 635  liveness: 72 %   region 636  liveness: 69 %
region 637  liveness: 72 %   region 638  liveness: 72 %   region 639  liveness: 67 %
region 640  liveness: 70 %   region 641  liveness: 68 %   region 642  liveness: 70 %
region 643  liveness: 69 %   region 644  liveness: 71 %   region 645  liveness: 69 %
region 646  liveness: 70 %   region 647  liveness: 68 %   region 648  liveness: 71 %
region 649  liveness: 68 %   region 650  liveness: 68 %   region 651  liveness: 66 %
region 652  liveness: 69 %   region 653  liveness: 69 %   region 654  liveness: 69 %
region 655  liveness: 70 %   region 656  liveness: 68 %   region 657  liveness: 70 %
region 658  liveness: 71 %   region 659  liveness: 69 %   region 660  liveness: 70 %
region 661  liveness: 67 %   region 662  liveness: 71 %   region 663  liveness: 70 %
region 664  liveness: 70 %   region 665  liveness: 70 %   region 666  liveness: 69 %
region 667  liveness: 68 %   region 668  liveness: 69 %   region 669  liveness: 74 %
region 670  liveness: 72 %   region 671  liveness: 70 %   region 672  liveness: 70 %
region 673  liveness: 73 %   region 674  liveness: 74 %   region 675  liveness: 76 %
region 676  liveness: 74 %   region 677  liveness: 76 %   region 678  liveness: 75 %
region 679  liveness: 63 %   region 680  liveness: 94 %   region 681  liveness: 92 %
region 682  liveness: 93 %   region 683  liveness: 93 %   region 684  liveness: 73 %
region 685  liveness: 72 %   region 686  liveness: 70 %   region 687  liveness: 71 %
region 688  liveness: 73 %   region 689  liveness: 71 %   region 690  liveness: 72 %
region 691  liveness: 71 %   region 692  liveness: 71 %   region 693  liveness: 73 %
region 694  liveness: 70 %   region 695  liveness: 71 %   region 696  liveness: 71 %
region 697  liveness: 72 %   region 698  liveness: 72 %   region 699  liveness: 72 %
region 700  liveness: 72 %   region 701  liveness: 70 %   region 702  liveness: 74 %
region 703  liveness: 71 %   region 704  liveness: 74 %   region 705  liveness: 69 %
region 706  liveness: 71 %   region 707  liveness: 72 %   region 708  liveness: 70 %
region 709  liveness: 71 %   region 710  liveness: 73 %   region 711  liveness: 73 %
region 712  liveness: 74 %   region 713  liveness: 72 %   region 714  liveness: 70 %
region 715  liveness: 71 %   region 716  liveness: 72 %   region 717  liveness: 70 %
region 718  liveness: 70 %   region 719  liveness: 73 %   region 720  liveness: 71 %
region 721  liveness: 75 %   region 722  liveness: 71 %   region 723  liveness: 71 %
region 724  liveness: 72 %   region 725  liveness: 74 %   region 726  liveness: 70 %
region 727  liveness: 71 %   region 728  liveness: 71 %   region 729  liveness: 72 %
region 730  liveness: 72 %   region 731  liveness: 73 %   region 732  liveness: 71 %
region 733  liveness: 75 %   region 734  liveness: 73 %   region 735  liveness: 74 %
region 736  liveness: 69 %   region 737  liveness: 70 %   region 738  liveness: 72 %
region 739  liveness: 73 %   region 740  liveness: 72 %   region 741  liveness: 72 %
region 742  liveness: 71 %   region 743  liveness: 72 %   region 744  liveness: 70 %
region 745  liveness: 70 %   region 746  liveness: 72 %   region 747  liveness: 100%
region 748  liveness: 93 %   region 749  liveness: 92 %   region 750  liveness: 92 %
region 751  liveness: 91 %   region 752  liveness: 94 %   region 753  liveness: 76 %
region 754  liveness: 75 %   region 755  liveness: 74 %   region 756  liveness: 75 %
region 757  liveness: 74 %   region 758  liveness: 73 %   region 759  liveness: 74 %
region 760  liveness: 71 %   region 761  liveness: 74 %   region 762  liveness: 76 %
region 763  liveness: 74 %   region 764  liveness: 73 %   region 765  liveness: 75 %
region 766  liveness: 73 %   region 767  liveness: 76 %   region 768  liveness: 72 %
region 769  liveness: 72 %   region 770  liveness: 73 %   region 771  liveness: 71 %
region 772  liveness: 73 %   region 773  liveness: 72 %   region 774  liveness: 73 %
region 775  liveness: 72 %   region 776  liveness: 74 %   region 777  liveness: 74 %
region 778  liveness: 73 %   region 779  liveness: 72 %   region 780  liveness: 72 %
region 781  liveness: 76 %   region 782  liveness: 74 %   region 783  liveness: 75 %
region 784  liveness: 72 %   region 785  liveness: 70 %   region 786  liveness: 72 %
region 787  liveness: 73 %   region 788  liveness: 75 %   region 789  liveness: 74 %
region 790  liveness: 78 %   region 791  liveness: 74 %   region 792  liveness: 74 %
region 793  liveness: 73 %   region 794  liveness: 74 %   region 795  liveness: 72 %
region 796  liveness: 72 %   region 797  liveness: 75 %   region 798  liveness: 77 %
region 799  liveness: 72 %   region 800  liveness: 74 %   region 801  liveness: 74 %
region 802  liveness: 74 %   region 803  liveness: 75 %   region 804  liveness: 73 %
region 805  liveness: 78 %   region 806  liveness: 73 %   region 807  liveness: 75 %
region 808  liveness: 74 %   region 809  liveness: 74 %   region 810  liveness: 77 %
region 811  liveness: 74 %   region 812  liveness: 77 %   region 813  liveness: 74 %
region 814  liveness: 74 %   region 815  liveness: 73 %   region 816  liveness: 74 %
region 817  liveness: 77 %   region 818  liveness: 75 %   region 819  liveness: 74 %
region 820  liveness: 73 %   region 821  liveness: 78 %   region 822  liveness: 72 %
region 823  liveness: 76 %   region 824  liveness: 73 %   region 825  liveness: 80 %
region 826  liveness: 73 %   region 827  liveness: 74 %   region 828  liveness: 79 %
region 829  liveness: 76 %   region 830  liveness: 77 %   region 831  liveness: 82 %
region 832  liveness: 87 %   region 833  liveness: 75 %   region 834  liveness: 83 %
region 835  liveness: 76 %   region 836  liveness: 82 %   region 837  liveness: 79 %
region 838  liveness: 74 %   region 839  liveness: 90 %   region 840  liveness: 84 %
region 841  liveness: 85 %   region 842  liveness: 85 %   region 843  liveness: 82 %
region 844  liveness: 86 %   region 845  liveness: 78 %   region 846  liveness: 100%
region 847  liveness: 94 %   region 848  liveness: 93 %   region 849  liveness: 92 %
region 850  liveness: 93 %   region 851  liveness: 92 %   region 852  liveness: 94 %
region 853  liveness: 77 %   region 854  liveness: 75 %   region 855  liveness: 78 %
region 856  liveness: 76 %   region 857  liveness: 76 %   region 858  liveness: 77 %
region 859  liveness: 75 %   region 860  liveness: 77 %   region 861  liveness: 76 %
region 862  liveness: 77 %   region 863  liveness: 75 %   region 864  liveness: 77 %
region 865  liveness: 77 %   region 866  liveness: 77 %   region 867  liveness: 78 %
region 868  liveness: 78 %   region 869  liveness: 78 %   region 870  liveness: 74 %
region 871  liveness: 75 %   region 872  liveness: 76 %   region 873  liveness: 78 %
region 874  liveness: 78 %   region 875  liveness: 77 %   region 876  liveness: 77 %
region 877  liveness: 74 %   region 878  liveness: 76 %   region 879  liveness: 76 %
region 880  liveness: 77 %   region 881  liveness: 74 %   region 882  liveness: 74 %
region 883  liveness: 77 %   region 884  liveness: 77 %   region 885  liveness: 78 %
region 886  liveness: 77 %   region 887  liveness: 83 %   region 888  liveness: 86 %
region 889  liveness: 85 %   region 890  liveness: 79 %   region 891  liveness: 64 %
region 892  liveness: 93 %   region 893  liveness: 92 %   region 894  liveness: 95 %
region 895  liveness: 92 %   region 896  liveness: 92 %   region 897  liveness: 80 %
region 898  liveness: 82 %   region 899  liveness: 80 %   region 900  liveness: 81 %
region 901  liveness: 80 %   region 902  liveness: 81 %   region 903  liveness: 80 %
region 904  liveness: 80 %   region 905  liveness: 80 %   region 906  liveness: 78 %
region 907  liveness: 79 %   region 908  liveness: 80 %   region 909  liveness: 79 %
region 910  liveness: 80 %   region 911  liveness: 81 %   region 912  liveness: 79 %
region 913  liveness: 79 %   region 914  liveness: 80 %   region 915  liveness: 77 %
region 916  liveness: 81 %   region 917  liveness: 82 %   region 918  liveness: 82 %
region 919  liveness: 81 %   region 920  liveness: 76 %   region 921  liveness: 79 %
region 922  liveness: 80 %   region 923  liveness: 79 %   region 924  liveness: 80 %
region 925  liveness: 78 %   region 926  liveness: 81 %   region 927  liveness: 80 %
region 928  liveness: 80 %   region 929  liveness: 79 %   region 930  liveness: 81 %
region 931  liveness: 81 %   region 932  liveness: 82 %   region 933  liveness: 82 %
region 934  liveness: 81 %   region 935  liveness: 81 %   region 936  liveness: 79 %
region 937  liveness: 79 %   region 938  liveness: 80 %   region 939  liveness: 81 %
region 940  liveness: 81 %   region 941  liveness: 79 %   region 942  liveness: 80 %
region 943  liveness: 79 %   region 944  liveness: 78 %   region 945  liveness: 80 %
region 946  liveness: 82 %   region 947  liveness: 78 %   region 948  liveness: 77 %
region 949  liveness: 79 %   region 950  liveness: 80 %   region 951  liveness: 80 %
region 952  liveness: 79 %   region 953  liveness: 80 %   region 954  liveness: 79 %
region 955  liveness: 80 %   region 956  liveness: 81 %   region 957  liveness: 81 %
region 958  liveness: 80 %   region 959  liveness: 78 %   region 960  liveness: 82 %
region 961  liveness: 79 %   region 962  liveness: 79 %   region 963  liveness: 79 %
region 964  liveness: 76 %   region 965  liveness: 81 %   region 966  liveness: 81 %
region 967  liveness: 81 %   region 968  liveness: 81 %   region 969  liveness: 81 %
region 970  liveness: 81 %   region 971  liveness: 80 %   region 972  liveness: 80 %
region 973  liveness: 81 %   region 974  liveness: 81 %   region 975  liveness: 82 %
region 976  liveness: 80 %   region 977  liveness: 80 %   region 978  liveness: 81 %
region 979  liveness: 82 %   region 980  liveness: 78 %   region 981  liveness: 78 %
region 982  liveness: 82 %   region 983  liveness: 82 %   region 984  liveness: 74 %
region 985  liveness: 93 %   region 986  liveness: 73 %   region 987  liveness: 72 %
region 988  liveness: 92 %   region 989  liveness: 93 %   region 990  liveness: 81 %
region 991  liveness: 83 %   region 992  liveness: 81 %   region 993  liveness: 83 %
region 994  liveness: 84 %   region 995  liveness: 84 %   region 996  liveness: 81 %
region 997  liveness: 82 %   region 998  liveness: 84 %   region 999  liveness: 79 %
region 1000 liveness: 82 %   region 1001 liveness: 82 %   region 1002 liveness: 81 %
region 1003 liveness: 80 %   region 1004 liveness: 83 %   region 1005 liveness: 82 %
region 1006 liveness: 82 %   region 1007 liveness: 82 %   region 1008 liveness: 83 %
region 1009 liveness: 83 %   region 1010 liveness: 79 %   region 1011 liveness: 81 %
region 1012 liveness: 81 %   region 1013 liveness: 81 %   region 1014 liveness: 81 %
region 1015 liveness: 81 %   region 1016 liveness: 81 %   region 1017 liveness: 81 %
region 1018 liveness: 83 %   region 1019 liveness: 82 %   region 1020 liveness: 83 %
region 1021 liveness: 80 %   region 1022 liveness: 82 %   region 1023 liveness: 81 %
region 1024 liveness: 80 %   region 1025 liveness: 83 %   region 1026 liveness: 83 %
region 1027 liveness: 81 %   region 1028 liveness: 84 %   region 1029 liveness: 83 %
region 1030 liveness: 82 %   region 1031 liveness: 81 %   region 1032 liveness: 81 %
region 1033 liveness: 82 %   region 1034 liveness: 83 %   region 1035 liveness: 83 %
region 1036 liveness: 80 %   region 1037 liveness: 84 %   region 1038 liveness: 83 %
region 1039 liveness: 81 %   region 1040 liveness: 82 %   region 1041 liveness: 80 %
region 1042 liveness: 80 %   region 1043 liveness: 81 %   region 1044 liveness: 84 %
region 1045 liveness: 81 %   region 1046 liveness: 83 %   region 1047 liveness: 82 %
region 1048 liveness: 81 %   region 1049 liveness: 81 %   region 1050 liveness: 81 %
region 1051 liveness: 89 %   region 1052 liveness: 61 %   region 1053 liveness: 78 %
region 1054 liveness: 64 %   region 1055 liveness: 94 %   region 1056 liveness: 94 %
region 1057 liveness: 95 %   region 1058 liveness: 93 %   region 1059 liveness: 84 %
region 1060 liveness: 85 %   region 1061 liveness: 83 %   region 1062 liveness: 85 %
region 1063 liveness: 87 %   region 1064 liveness: 84 %   region 1065 liveness: 83 %
region 1066 liveness: 84 %   region 1067 liveness: 83 %   region 1068 liveness: 84 %
region 1069 liveness: 84 %   region 1070 liveness: 83 %   region 1071 liveness: 83 %
region 1072 liveness: 85 %   region 1073 liveness: 85 %   region 1074 liveness: 83 %
region 1075 liveness: 83 %   region 1076 liveness: 84 %   region 1077 liveness: 82 %
region 1078 liveness: 85 %   region 1079 liveness: 83 %   region 1080 liveness: 84 %
region 1081 liveness: 83 %   region 1082 liveness: 86 %   region 1083 liveness: 82 %
region 1084 liveness: 86 %   region 1085 liveness: 84 %   region 1086 liveness: 85 %
region 1087 liveness: 86 %   region 1088 liveness: 86 %   region 1089 liveness: 84 %
region 1090 liveness: 84 %   region 1091 liveness: 81 %   region 1092 liveness: 85 %
region 1093 liveness: 83 %   region 1094 liveness: 84 %   region 1095 liveness: 83 %
region 1096 liveness: 86 %   region 1097 liveness: 84 %   region 1098 liveness: 84 %
region 1099 liveness: 85 %   region 1100 liveness: 85 %   region 1101 liveness: 83 %
region 1102 liveness: 83 %   region 1103 liveness: 85 %   region 1104 liveness: 83 %
region 1105 liveness: 84 %   region 1106 liveness: 86 %   region 1107 liveness: 84 %
region 1108 liveness: 85 %   region 1109 liveness: 83 %   region 1110 liveness: 83 %
region 1111 liveness: 82 %   region 1112 liveness: 85 %   region 1113 liveness: 85 %
region 1114 liveness: 86 %   region 1115 liveness: 85 %   region 1116 liveness: 85 %
region 1117 liveness: 85 %   region 1118 liveness: 83 %   region 1119 liveness: 84 %
region 1120 liveness: 76 %   region 1121 liveness: 65 %   region 1122 liveness: 78 %
region 1123 liveness: 84 %   region 1124 liveness: 86 %   region 1125 liveness: 85 %
region 1126 liveness: 87 %   region 1127 liveness: 88 %   region 1128 liveness: 86 %
region 1129 liveness: 87 %   region 1130 liveness: 88 %   region 1131 liveness: 85 %
region 1132 liveness: 87 %   region 1133 liveness: 85 %   region 1134 liveness: 85 %
region 1135 liveness: 89 %   region 1136 liveness: 86 %   region 1137 liveness: 88 %
region 1138 liveness: 85 %   region 1139 liveness: 85 %   region 1140 liveness: 85 %
region 1141 liveness: 87 %   region 1142 liveness: 87 %   region 1143 liveness: 85 %
region 1144 liveness: 85 %   region 1145 liveness: 86 %   region 1146 liveness: 86 %
region 1147 liveness: 86 %   region 1148 liveness: 85 %   region 1149 liveness: 86 %
region 1150 liveness: 88 %   region 1151 liveness: 84 %   region 1152 liveness: 88 %
region 1153 liveness: 86 %   region 1154 liveness: 87 %   region 1155 liveness: 84 %
region 1156 liveness: 86 %   region 1157 liveness: 89 %   region 1158 liveness: 86 %
region 1159 liveness: 86 %   region 1160 liveness: 88 %   region 1161 liveness: 87 %
region 1162 liveness: 87 %   region 1163 liveness: 86 %   region 1164 liveness: 86 %
region 1165 liveness: 88 %   region 1166 liveness: 86 %   region 1167 liveness: 88 %
region 1168 liveness: 87 %   region 1169 liveness: 86 %   region 1170 liveness: 88 %
region 1171 liveness: 85 %   region 1172 liveness: 87 %   region 1173 liveness: 87 %
region 1174 liveness: 83 %   region 1175 liveness: 90 %   region 1176 liveness: 86 %
region 1177 liveness: 85 %   region 1178 liveness: 87 %   region 1179 liveness: 84 %
region 1180 liveness: 96 %   region 1181 liveness: 92 %   region 1182 liveness: 91 %
region 1183 liveness: 91 %   region 1184 liveness: 90 %   region 1185 liveness: 90 %
region 1186 liveness: 90 %   region 1187 liveness: 90 %   region 1188 liveness: 91 %
region 1189 liveness: 91 %   region 1190 liveness: 92 %   region 1191 liveness: 90 %
region 1192 liveness: 90 %   region 1193 liveness: 89 %   region 1194 liveness: 89 %
region 1195 liveness: 90 %   region 1196 liveness: 89 %   region 1197 liveness: 89 %
region 1198 liveness: 90 %   region 1199 liveness: 88 %   region 1200 liveness: 91 %
region 1201 liveness: 90 %   region 1202 liveness: 88 %   region 1203 liveness: 88 %
region 1204 liveness: 90 %   region 1205 liveness: 90 %   region 1206 liveness: 88 %
region 1207 liveness: 89 %   region 1208 liveness: 89 %   region 1209 liveness: 88 %
region 1210 liveness: 89 %   region 1211 liveness: 89 %   region 1212 liveness: 89 %
region 1213 liveness: 89 %   region 1214 liveness: 91 %   region 1215 liveness: 90 %
region 1216 liveness: 89 %   region 1217 liveness: 89 %   region 1218 liveness: 89 %
region 1219 liveness: 90 %   region 1220 liveness: 90 %   region 1221 liveness: 90 %
region 1222 liveness: 90 %   region 1223 liveness: 91 %   region 1224 liveness: 88 %
region 1225 liveness: 91 %   region 1226 liveness: 90 %   region 1227 liveness: 90 %
region 1228 liveness: 90 %   region 1229 liveness: 87 %   region 1230 liveness: 90 %
region 1231 liveness: 88 %   region 1232 liveness: 92 %   region 1233 liveness: 90 %
region 1234 liveness: 90 %   region 1235 liveness: 90 %   region 1236 liveness: 90 %
region 1237 liveness: 91 %   region 1238 liveness: 89 %   region 1239 liveness: 91 %
region 1240 liveness: 90 %   region 1241 liveness: 91 %   region 1242 liveness: 88 %
region 1243 liveness: 89 %   region 1244 liveness: 90 %   region 1245 liveness: 91 %
region 1246 liveness: 90 %   region 1247 liveness: 89 %   region 1248 liveness: 91 %
region 1249 liveness: 89 %   region 1250 liveness: 90 %   region 1251 liveness: 89 %
region 1252 liveness: 88 %   region 1253 liveness: 89 %   region 1254 liveness: 92 %
region 1255 liveness: 90 %   region 1256 liveness: 87 %   region 1257 liveness: 89 %
region 1258 liveness: 91 %   region 1259 liveness: 89 %   region 1260 liveness: 90 %
region 1261 liveness: 89 %   region 1262 liveness: 91 %   region 1263 liveness: 91 %
region 1264 liveness: 89 %   region 1265 liveness: 89 %   region 1266 liveness: 90 %
region 1267 liveness: 89 %   region 1268 liveness: 89 %   region 1269 liveness: 92 %
region 1270 liveness: 91 %   region 1271 liveness: 91 %   region 1272 liveness: 89 %
region 1273 liveness: 89 %   region 1274 liveness: 91 %   region 1275 liveness: 87 %
region 1276 liveness: 89 %   region 1277 liveness: 91 %   region 1278 liveness: 91 %
region 1279 liveness: 93 %   region 1280 liveness: 90 %   region 1281 liveness: 90 %
region 1282 liveness: 90 %   region 1283 liveness: 89 %   region 1284 liveness: 90 %
region 1285 liveness: 90 %   region 1286 liveness: 93 %   region 1287 liveness: 90 %
region 1288 liveness: 89 %   region 1289 liveness: 90 %   region 1290 liveness: 91 %
region 1291 liveness: 88 %   region 1292 liveness: 91 %   region 1293 liveness: 90 %
region 1294 liveness: 92 %   region 1295 liveness: 89 %   region 1296 liveness: 92 %
region 1297 liveness: 91 %   region 1298 liveness: 91 %   region 1299 liveness: 91 %
region 1300 liveness: 90 %   region 1301 liveness: 88 %   region 1302 liveness: 92 %
region 1303 liveness: 88 %   region 1304 liveness: 87 %   region 1305 liveness: 91 %
region 1306 liveness: 90 %   region 1307 liveness: 90 %   region 1308 liveness: 90 %
region 1309 liveness: 89 %   region 1310 liveness: 91 %   region 1311 liveness: 90 %
region 1312 liveness: 89 %   region 1313 liveness: 90 %   region 1314 liveness: 90 %
region 1315 liveness: 89 %   region 1316 liveness: 88 %   region 1317 liveness: 90 %
region 1318 liveness: 89 %   region 1319 liveness: 89 %   region 1320 liveness: 91 %
region 1321 liveness: 89 %   region 1322 liveness: 89 %   region 1323 liveness: 90 %
region 1324 liveness: 90 %   region 1325 liveness: 91 %   region 1326 liveness: 91 %
region 1327 liveness: 91 %   region 1328 liveness: 91 %   region 1329 liveness: 89 %
region 1330 liveness: 91 %   region 1331 liveness: 90 %   region 1332 liveness: 91 %
region 1333 liveness: 90 %   region 1334 liveness: 89 %   region 1335 liveness: 89 %
region 1336 liveness: 89 %   region 1337 liveness: 88 %   region 1338 liveness: 90 %
region 1339 liveness: 90 %   region 1340 liveness: 90 %   region 1341 liveness: 91 %
region 1342 liveness: 88 %   region 1343 liveness: 91 %   region 1344 liveness: 91 %
region 1345 liveness: 88 %   region 1346 liveness: 91 %   region 1347 liveness: 90 %
region 1348 liveness: 90 %   region 1349 liveness: 92 %   region 1350 liveness: 92 %
region 1351 liveness: 88 %   region 1352 liveness: 87 %   region 1353 liveness: 90 %
region 1354 liveness: 89 %   region 1355 liveness: 90 %   region 1356 liveness: 93 %
region 1357 liveness: 90 %   region 1358 liveness: 91 %   region 1359 liveness: 92 %
region 1360 liveness: 93 %   region 1361 liveness: 92 %   region 1362 liveness: 94 %
region 1363 liveness: 95 %   region 1364 liveness: 96 %   region 1365 liveness: 96 %
region 1366 liveness: 95 %   region 1367 liveness: 94 %   region 1368 liveness: 94 %
region 1369 liveness: 93 %   region 1370 liveness: 79 %   region 1371 liveness: 94 %
region 1372 liveness: 94 %   region 1373 liveness: 92 %   region 1374 liveness: 98 %
region 1375 liveness: 80 %   region 1376 liveness: 96 %   region 1377 liveness: 90 %
region 1378 liveness: 100%   region 1379 liveness: 100%   region 1380 liveness: 100%
region 1381 liveness: 100%   region 1382 liveness: 100%   region 1383 liveness: 100%
region 1384 liveness: 100%   region 1385 liveness: 100%   region 1386 liveness: 100%
region 1387 liveness: 100%   region 1388 liveness: 100%   region 1389 liveness: 100%
region 1390 liveness: 100%   region 1391 liveness: 100%   region 1392 liveness: 100%
region 1393 liveness: 100%   region 1394 liveness: 100%   region 1395 liveness: 100%
region 1396 liveness: 100%   region 1397 liveness: 100%   region 1398 liveness: 100%
region 1399 liveness: 100%   region 1400 liveness: 100%   region 1401 liveness: 100%
region 1402 liveness: 100%   region 1403 liveness: 100%   region 1404 liveness: 100%
region 1405 liveness: 100%   region 1406 liveness: 100%   region 1407 liveness: 100%
region 1408 liveness: 100%   region 1409 liveness: 100%   region 1410 liveness: 100%
region 1411 liveness: 100%   region 1412 liveness: 100%   region 1413 liveness: 100%
region 1414 liveness: 100%   region 1415 liveness: 100%   region 1416 liveness: 100%
region 1417 liveness: 100%   region 1418 liveness: 100%   region 1419 liveness: 100%
region 1420 liveness: 100%   region 1421 liveness: 100%   region 1422 liveness: 100%
region 1423 liveness: 100%   region 1424 liveness: 100%   region 1425 liveness: 100%
region 1426 liveness: 100%   region 1427 liveness: 100%   region 1428 liveness: 100%
region 1429 liveness: 100%   region 1430 liveness: 100%   region 1431 liveness: 100%
region 1432 liveness: 100%   region 1433 liveness: 100%   region 1434 liveness: 100%
region 1435 liveness: 100%   region 1436 liveness: 100%   region 1437 liveness: 100%
region 1438 liveness: 100%   region 1439 liveness: 100%   region 1440 liveness: 100%
region 1441 liveness: 100%   region 1442 liveness: 100%   region 1443 liveness: 100%
region 1444 liveness: 100%   region 1445 liveness: 100%   region 1446 liveness: 100%
region 1447 liveness: 100%   region 1448 liveness: 100%   region 1449 liveness: 100%
region 1450 liveness: 100%   region 1451 liveness: 100%   region 1452 liveness: 100%
region 1453 liveness: 100%   region 1454 liveness: 100%   region 1455 liveness: 100%
region 1456 liveness: 100%   region 1457 liveness: 100%   region 1458 liveness: 100%
region 1459 liveness: 100%   region 1460 liveness: 100%   region 1461 liveness: 100%
region 1462 liveness: 100%   region 1463 liveness: 100%   region 1464 liveness: 100%
region 1465 liveness: 100%   region 1466 liveness: 100%   region 1467 liveness: 100%
region 1468 liveness: 100%   region 1469 liveness: 100%   region 1470 liveness: 100%
region 1471 liveness: 100%   region 1472 liveness: 100%   region 1473 liveness: 100%
region 1474 liveness: 100%   region 1475 liveness: 100%   region 1476 liveness: 100%
region 1477 liveness: 100%   region 1478 liveness: 100%   region 1479 liveness: 100%
region 1480 liveness: 100%   region 1481 liveness: 100%   region 1482 liveness: 100%
region 1483 liveness: 100%   region 1484 liveness: 100%   region 1485 liveness: 100%
region 1486 liveness: 100%   region 1487 liveness: 100%   region 1488 liveness: 100%
region 1489 liveness: 100%   region 1490 liveness: 100%   region 1491 liveness: 100%
region 1492 liveness: 100%   region 1493 liveness: 100%   region 1494 liveness: 100%
region 1495 liveness: 100%   region 1496 liveness: 100%   region 1497 liveness: 100%
region 1498 liveness: 100%   region 1499 liveness: 100%   region 1500 liveness: 100%
region 1501 liveness: 100%   region 1502 liveness: 100%   region 1503 liveness: 100%
region 1504 liveness: 100%   region 1505 liveness: 100%   region 1506 liveness: 100%
region 1507 liveness: 100%   region 1508 liveness: 100%   region 1509 liveness: 100%
region 1510 liveness: 100%   region 1511 liveness: 100%   region 1512 liveness: 100%
region 1513 liveness: 100%   region 1514 liveness: 100%   region 1515 liveness: 100%
region 1516 liveness: 100%   region 1517 liveness: 100%   region 1518 liveness: 100%
region 1519 liveness: 100%   region 1520 liveness: 100%   region 1521 liveness: 100%
region 1522 liveness: 100%   region 1523 liveness: 100%   region 1524 liveness: 100%
region 1525 liveness: 100%   region 1526 liveness: 100%   region 1527 liveness: 100%
region 1528 liveness: 100%   region 1529 liveness: 100%   region 1530 liveness: 100%
region 1531 liveness: 100%   region 1532 liveness: 100%   region 1533 liveness: 100%
region 1534 liveness: 100%   region 1535 liveness: 100%   region 1536 liveness: 100%
region 1537 liveness: 100%   region 1538 liveness: 100%   region 1539 liveness: 100%
region 1540 liveness: 100%   region 1541 liveness: 100%   region 1542 liveness: 100%
region 1543 liveness: 100%   region 1544 liveness: 100%   region 1545 liveness: 100%
region 1546 liveness: 100%   region 1547 liveness: 100%   region 1548 liveness: 100%
region 1549 liveness: 100%   region 1550 liveness: 100%   region 1551 liveness: 100%
region 1552 liveness: 100%   region 1553 liveness: 100%   region 1554 liveness: 100%
region 1555 liveness: 100%   region 1556 liveness: 100%   region 1557 liveness: 100%
region 1558 liveness: 100%   region 1559 liveness: 100%   region 1560 liveness: 100%
region 1561 liveness: 100%   region 1562 liveness: 100%   region 1563 liveness: 100%
region 1564 liveness: 100%   region 1565 liveness: 100%   region 1566 liveness: 100%
region 1567 liveness: 100%   region 1568 liveness: 100%   region 1569 liveness: 100%
region 1570 liveness: 100%   region 1571 liveness: 100%   region 1572 liveness: 100%
region 1573 liveness: 100%   region 1574 liveness: 100%   region 1575 liveness: 100%
region 1576 liveness: 100%   region 1577 liveness: 100%   region 1578 liveness: 100%
region 1579 liveness: 100%   region 1580 liveness: 100%   region 1581 liveness: 100%
region 1582 liveness: 100%   region 1583 liveness: 100%   region 1584 liveness: 100%
region 1585 liveness: 100%   region 1586 liveness: 100%   region 1587 liveness: 100%
region 1588 liveness: 100%   region 1589 liveness: 100%   region 1590 liveness: 100%
region 1591 liveness: 100%   region 1592 liveness: 100%   region 1593 liveness: 100%
region 1594 liveness: 100%   region 1595 liveness: 100%   region 1596 liveness: 100%
region 1597 liveness: 100%   region 1598 liveness: 100%   region 1599 liveness: 100%
region 1600 liveness: 100%   region 1601 liveness: 100%   region 1602 liveness: 100%
region 1603 liveness: 100%   region 1604 liveness: 100%   region 1605 liveness: 100%
region 1606 liveness: 100%   region 1607 liveness: 100%   region 1608 liveness: 100%
region 1609 liveness: 100%   region 1610 liveness: 100%   region 1611 liveness: 100%
region 1612 liveness: 100%   region 1613 liveness: 100%   region 1614 liveness: 100%
region 1615 liveness: 100%   region 1616 liveness: 100%   region 1617 liveness: 100%
region 1618 liveness: 100%   region 1619 liveness: 100%   region 1620 liveness: 100%
region 1621 liveness: 100%   region 1622 liveness: 100%   region 1623 liveness: 100%
region 1624 liveness: 100%   region 1625 liveness: 100%
```

```log
Operations: 1860000
Live objects in old region: 1251999
region 1    liveness: 99 %   region 2    liveness: 99 %   region 3    liveness: 99 %
region 4    liveness: 99 %   region 5    liveness: 99 %   region 6    liveness: 99 %
region 7    liveness: 99 %   region 8    liveness: 99 %   region 9    liveness: 99 %
region 10   liveness: 99 %   region 11   liveness: 99 %   region 12   liveness: 99 %
region 13   liveness: 99 %   region 14   liveness: 99 %   region 15   liveness: 99 %
region 16   liveness: 99 %   region 17   liveness: 99 %   region 18   liveness: 99 %
region 19   liveness: 99 %   region 20   liveness: 99 %   region 21   liveness: 99 %
region 22   liveness: 100%   region 23   liveness: 100%   region 24   liveness: 99 %
region 25   liveness: 99 %   region 26   liveness: 99 %   region 27   liveness: 99 %
region 28   liveness: 99 %   region 29   liveness: 99 %   region 30   liveness: 99 %
region 31   liveness: 99 %   region 32   liveness: 99 %   region 33   liveness: 99 %
region 34   liveness: 99 %   region 35   liveness: 99 %   region 36   liveness: 99 %
region 37   liveness: 99 %   region 38   liveness: 99 %   region 39   liveness: 99 %
region 40   liveness: 99 %   region 41   liveness: 99 %   region 42   liveness: 99 %
region 43   liveness: 99 %   region 44   liveness: 99 %   region 45   liveness: 99 %
region 46   liveness: 99 %   region 47   liveness: 99 %   region 48   liveness: 99 %
region 49   liveness: 99 %   region 50   liveness: 99 %   region 51   liveness: 99 %
region 52   liveness: 99 %   region 53   liveness: 99 %   region 54   liveness: 99 %
region 55   liveness: 99 %   region 56   liveness: 99 %   region 57   liveness: 99 %
region 58   liveness: 99 %   region 59   liveness: 99 %   region 60   liveness: 99 %
region 61   liveness: 99 %   region 62   liveness: 99 %   region 63   liveness: 99 %
region 64   liveness: 99 %   region 65   liveness: 99 %   region 66   liveness: 99 %
region 67   liveness: 99 %   region 68   liveness: 99 %   region 69   liveness: 99 %
region 70   liveness: 99 %   region 71   liveness: 99 %   region 72   liveness: 99 %
region 73   liveness: 99 %   region 74   liveness: 99 %   region 75   liveness: 99 %
region 76   liveness: 99 %   region 77   liveness: 99 %   region 78   liveness: 99 %
region 79   liveness: 99 %   region 80   liveness: 99 %   region 81   liveness: 99 %
region 82   liveness: 99 %   region 83   liveness: 99 %   region 84   liveness: 99 %
region 85   liveness: 99 %   region 86   liveness: 99 %   region 87   liveness: 99 %
region 88   liveness: 99 %   region 89   liveness: 99 %   region 90   liveness: 99 %
region 91   liveness: 99 %   region 92   liveness: 99 %   region 93   liveness: 99 %
region 94   liveness: 99 %   region 95   liveness: 99 %   region 96   liveness: 99 %
region 97   liveness: 99 %   region 98   liveness: 99 %   region 99   liveness: 99 %
region 100  liveness: 99 %   region 101  liveness: 99 %   region 102  liveness: 100%
region 103  liveness: 100%   region 104  liveness: 99 %   region 105  liveness: 99 %
region 106  liveness: 99 %   region 107  liveness: 99 %   region 108  liveness: 99 %
region 109  liveness: 99 %   region 110  liveness: 99 %   region 111  liveness: 99 %
region 112  liveness: 99 %   region 113  liveness: 99 %   region 114  liveness: 99 %
region 115  liveness: 99 %   region 116  liveness: 99 %   region 117  liveness: 99 %
region 118  liveness: 99 %   region 119  liveness: 100%   region 120  liveness: 99 %
region 121  liveness: 99 %   region 122  liveness: 100%   region 123  liveness: 99 %
region 124  liveness: 99 %   region 125  liveness: 99 %   region 126  liveness: 99 %
region 127  liveness: 100%   region 128  liveness: 99 %   region 129  liveness: 99 %
region 130  liveness: 99 %   region 131  liveness: 99 %   region 132  liveness: 99 %
region 133  liveness: 99 %   region 134  liveness: 99 %   region 135  liveness: 99 %
region 136  liveness: 99 %   region 137  liveness: 99 %   region 138  liveness: 99 %
region 139  liveness: 99 %   region 140  liveness: 99 %   region 141  liveness: 99 %
region 142  liveness: 99 %   region 143  liveness: 99 %   region 144  liveness: 99 %
region 145  liveness: 99 %   region 146  liveness: 99 %   region 147  liveness: 99 %
region 148  liveness: 99 %   region 149  liveness: 99 %   region 150  liveness: 99 %
region 151  liveness: 99 %   region 152  liveness: 99 %   region 153  liveness: 99 %
region 154  liveness: 99 %   region 155  liveness: 99 %   region 156  liveness: 99 %
region 157  liveness: 99 %   region 158  liveness: 99 %   region 159  liveness: 100%
region 160  liveness: 99 %   region 161  liveness: 99 %   region 162  liveness: 99 %
region 163  liveness: 99 %   region 164  liveness: 99 %   region 165  liveness: 99 %
region 166  liveness: 99 %   region 167  liveness: 99 %   region 168  liveness: 100%
region 169  liveness: 99 %   region 170  liveness: 99 %   region 171  liveness: 99 %
region 172  liveness: 99 %   region 173  liveness: 99 %   region 174  liveness: 99 %
region 175  liveness: 99 %   region 176  liveness: 100%   region 177  liveness: 99 %
region 178  liveness: 99 %   region 179  liveness: 99 %   region 180  liveness: 99 %
region 181  liveness: 99 %   region 182  liveness: 99 %   region 183  liveness: 99 %
region 184  liveness: 99 %   region 185  liveness: 99 %   region 186  liveness: 99 %
region 187  liveness: 99 %   region 188  liveness: 99 %   region 189  liveness: 100%
region 190  liveness: 99 %   region 191  liveness: 100%   region 192  liveness: 99 %
region 193  liveness: 99 %   region 194  liveness: 99 %   region 195  liveness: 100%
region 196  liveness: 100%   region 197  liveness: 99 %   region 198  liveness: 99 %
region 199  liveness: 99 %   region 200  liveness: 99 %   region 201  liveness: 99 %
region 202  liveness: 99 %   region 203  liveness: 99 %   region 204  liveness: 99 %
region 205  liveness: 99 %   region 206  liveness: 99 %   region 207  liveness: 99 %
region 208  liveness: 99 %   region 209  liveness: 99 %   region 210  liveness: 99 %
region 211  liveness: 99 %   region 212  liveness: 99 %   region 213  liveness: 99 %
region 214  liveness: 99 %   region 215  liveness: 99 %   region 216  liveness: 99 %
region 217  liveness: 100%   region 218  liveness: 99 %   region 219  liveness: 99 %
region 220  liveness: 100%   region 221  liveness: 100%   region 222  liveness: 100%
region 223  liveness: 99 %   region 224  liveness: 99 %   region 225  liveness: 99 %
region 226  liveness: 99 %   region 227  liveness: 99 %   region 228  liveness: 100%
region 229  liveness: 99 %   region 230  liveness: 99 %   region 231  liveness: 99 %
region 232  liveness: 99 %   region 233  liveness: 99 %   region 234  liveness: 99 %
region 235  liveness: 99 %   region 236  liveness: 99 %   region 237  liveness: 99 %
region 238  liveness: 99 %   region 239  liveness: 99 %   region 240  liveness: 99 %
region 241  liveness: 99 %   region 242  liveness: 99 %   region 243  liveness: 99 %
region 244  liveness: 99 %   region 245  liveness: 99 %   region 246  liveness: 99 %
region 247  liveness: 99 %   region 248  liveness: 99 %   region 249  liveness: 99 %
region 250  liveness: 99 %   region 251  liveness: 99 %   region 252  liveness: 99 %
region 253  liveness: 99 %   region 254  liveness: 99 %   region 255  liveness: 99 %
region 256  liveness: 99 %   region 257  liveness: 99 %   region 258  liveness: 99 %
region 259  liveness: 99 %   region 260  liveness: 99 %   region 261  liveness: 99 %
region 262  liveness: 99 %   region 263  liveness: 99 %   region 264  liveness: 99 %
region 265  liveness: 99 %   region 266  liveness: 99 %   region 267  liveness: 99 %
region 268  liveness: 99 %   region 269  liveness: 99 %   region 270  liveness: 99 %
region 271  liveness: 99 %   region 272  liveness: 99 %   region 273  liveness: 99 %
region 274  liveness: 99 %   region 275  liveness: 99 %   region 276  liveness: 99 %
region 277  liveness: 99 %   region 278  liveness: 99 %   region 279  liveness: 99 %
region 280  liveness: 99 %   region 281  liveness: 99 %   region 282  liveness: 99 %
region 283  liveness: 99 %   region 284  liveness: 99 %   region 285  liveness: 99 %
region 286  liveness: 99 %   region 287  liveness: 99 %   region 288  liveness: 99 %
region 289  liveness: 99 %   region 290  liveness: 100%   region 291  liveness: 99 %
region 292  liveness: 99 %   region 293  liveness: 99 %   region 294  liveness: 99 %
region 295  liveness: 99 %   region 296  liveness: 99 %   region 297  liveness: 99 %
region 298  liveness: 100%   region 299  liveness: 100%   region 300  liveness: 99 %
region 301  liveness: 99 %   region 302  liveness: 99 %   region 303  liveness: 99 %
region 304  liveness: 99 %   region 305  liveness: 99 %   region 306  liveness: 99 %
region 307  liveness: 99 %   region 308  liveness: 99 %   region 309  liveness: 99 %
region 310  liveness: 99 %   region 311  liveness: 99 %   region 312  liveness: 99 %
region 313  liveness: 99 %   region 314  liveness: 100%   region 315  liveness: 99 %
region 316  liveness: 99 %   region 317  liveness: 99 %   region 318  liveness: 99 %
region 319  liveness: 99 %   region 320  liveness: 99 %   region 321  liveness: 99 %
region 322  liveness: 99 %   region 323  liveness: 99 %   region 324  liveness: 99 %
region 325  liveness: 99 %   region 326  liveness: 99 %   region 327  liveness: 99 %
region 328  liveness: 99 %   region 329  liveness: 99 %   region 330  liveness: 99 %
region 331  liveness: 99 %   region 332  liveness: 100%   region 333  liveness: 99 %
region 334  liveness: 100%   region 335  liveness: 99 %   region 336  liveness: 99 %
region 337  liveness: 99 %   region 338  liveness: 100%   region 339  liveness: 99 %
region 340  liveness: 99 %   region 341  liveness: 100%   region 342  liveness: 100%
region 343  liveness: 100%   region 344  liveness: 99 %   region 345  liveness: 100%
region 346  liveness: 99 %   region 347  liveness: 99 %   region 348  liveness: 99 %
region 349  liveness: 99 %   region 350  liveness: 99 %   region 351  liveness: 99 %
region 352  liveness: 99 %   region 353  liveness: 99 %   region 354  liveness: 99 %
region 355  liveness: 99 %   region 356  liveness: 99 %   region 357  liveness: 100%
region 358  liveness: 99 %   region 359  liveness: 99 %   region 360  liveness: 99 %
region 361  liveness: 100%   region 362  liveness: 99 %   region 363  liveness: 99 %
region 364  liveness: 99 %   region 365  liveness: 99 %   region 366  liveness: 99 %
region 367  liveness: 99 %   region 368  liveness: 99 %   region 369  liveness: 99 %
region 370  liveness: 99 %   region 371  liveness: 99 %   region 372  liveness: 99 %
region 373  liveness: 99 %   region 374  liveness: 99 %   region 375  liveness: 99 %
region 376  liveness: 99 %   region 377  liveness: 100%   region 378  liveness: 99 %
region 379  liveness: 99 %   region 380  liveness: 99 %   region 381  liveness: 99 %
region 382  liveness: 100%   region 383  liveness: 99 %   region 384  liveness: 99 %
region 385  liveness: 99 %   region 386  liveness: 99 %   region 387  liveness: 99 %
region 388  liveness: 100%   region 389  liveness: 99 %   region 390  liveness: 99 %
region 391  liveness: 100%   region 392  liveness: 100%   region 393  liveness: 100%
region 394  liveness: 99 %   region 395  liveness: 99 %   region 396  liveness: 99 %
region 397  liveness: 99 %   region 398  liveness: 99 %   region 399  liveness: 99 %
region 400  liveness: 99 %   region 401  liveness: 99 %   region 402  liveness: 99 %
region 403  liveness: 99 %   region 404  liveness: 99 %   region 405  liveness: 99 %
region 406  liveness: 99 %   region 407  liveness: 99 %   region 408  liveness: 99 %
region 409  liveness: 99 %   region 410  liveness: 99 %   region 411  liveness: 100%
region 412  liveness: 100%   region 413  liveness: 99 %   region 414  liveness: 100%
region 415  liveness: 99 %   region 416  liveness: 99 %   region 417  liveness: 100%
region 418  liveness: 100%   region 419  liveness: 99 %   region 420  liveness: 99 %
region 421  liveness: 99 %   region 422  liveness: 100%   region 423  liveness: 99 %
region 424  liveness: 100%   region 425  liveness: 99 %   region 426  liveness: 99 %
region 427  liveness: 99 %   region 428  liveness: 99 %   region 429  liveness: 99 %
region 430  liveness: 99 %   region 431  liveness: 99 %   region 432  liveness: 100%
region 433  liveness: 100%   region 434  liveness: 100%   region 435  liveness: 99 %
region 436  liveness: 99 %   region 437  liveness: 99 %   region 438  liveness: 99 %
region 439  liveness: 99 %   region 440  liveness: 99 %   region 441  liveness: 99 %
region 442  liveness: 100%   region 443  liveness: 99 %   region 444  liveness: 99 %
region 445  liveness: 99 %   region 446  liveness: 99 %   region 447  liveness: 100%
region 448  liveness: 100%   region 449  liveness: 100%   region 450  liveness: 99 %
region 451  liveness: 99 %   region 452  liveness: 99 %   region 453  liveness: 99 %
region 454  liveness: 100%   region 455  liveness: 100%   region 456  liveness: 99 %
region 457  liveness: 100%   region 458  liveness: 99 %   region 459  liveness: 99 %
region 460  liveness: 99 %   region 461  liveness: 100%   region 462  liveness: 100%
region 463  liveness: 100%   region 464  liveness: 100%   region 465  liveness: 99 %
region 466  liveness: 99 %   region 467  liveness: 100%   region 468  liveness: 99 %
region 469  liveness: 99 %   region 470  liveness: 99 %   region 471  liveness: 99 %
region 472  liveness: 99 %   region 473  liveness: 100%   region 474  liveness: 99 %
region 475  liveness: 100%   region 476  liveness: 99 %   region 477  liveness: 99 %
region 478  liveness: 99 %   region 479  liveness: 100%   region 480  liveness: 100%
region 481  liveness: 99 %   region 482  liveness: 100%   region 483  liveness: 100%
region 484  liveness: 99 %   region 485  liveness: 99 %   region 486  liveness: 99 %
region 487  liveness: 99 %   region 488  liveness: 100%   region 489  liveness: 99 %
region 490  liveness: 100%   region 491  liveness: 100%   region 492  liveness: 99 %
region 493  liveness: 99 %   region 494  liveness: 99 %   region 495  liveness: 100%
region 496  liveness: 100%   region 497  liveness: 99 %   region 498  liveness: 100%
region 499  liveness: 100%   region 500  liveness: 100%   region 501  liveness: 100%
region 502  liveness: 100%   region 503  liveness: 100%   region 504  liveness: 100%
region 505  liveness: 100%   region 506  liveness: 100%   region 507  liveness: 100%
region 508  liveness: 100%   region 509  liveness: 100%   region 510  liveness: 100%
region 511  liveness: 100%   region 512  liveness: 99 %   region 513  liveness: 99 %
region 514  liveness: 100%   region 515  liveness: 99 %   region 516  liveness: 99 %
region 517  liveness: 100%   region 518  liveness: 100%   region 519  liveness: 100%
region 520  liveness: 99 %   region 521  liveness: 99 %   region 522  liveness: 100%
region 523  liveness: 99 %   region 524  liveness: 99 %   region 525  liveness: 99 %
region 526  liveness: 99 %   region 527  liveness: 100%   region 528  liveness: 99 %
region 529  liveness: 100%   region 530  liveness: 100%   region 531  liveness: 99 %
region 532  liveness: 100%   region 533  liveness: 100%   region 534  liveness: 99 %
region 535  liveness: 100%   region 536  liveness: 100%   region 537  liveness: 100%
region 538  liveness: 100%   region 539  liveness: 100%   region 540  liveness: 100%
region 541  liveness: 100%   region 542  liveness: 100%   region 543  liveness: 100%
region 544  liveness: 99 %   region 545  liveness: 100%   region 546  liveness: 99 %
region 547  liveness: 100%   region 548  liveness: 100%   region 549  liveness: 100%
region 550  liveness: 99 %   region 551  liveness: 99 %   region 552  liveness: 99 %
region 553  liveness: 100%   region 554  liveness: 100%   region 555  liveness: 100%
region 556  liveness: 100%   region 557  liveness: 99 %   region 558  liveness: 100%
region 559  liveness: 99 %   region 560  liveness: 100%   region 561  liveness: 100%
region 562  liveness: 100%   region 563  liveness: 100%   region 564  liveness: 100%
region 565  liveness: 100%   region 566  liveness: 100%   region 567  liveness: 100%
region 568  liveness: 100%   region 569  liveness: 100%   region 570  liveness: 100%
region 571  liveness: 100%   region 572  liveness: 100%   region 573  liveness: 99 %
region 574  liveness: 100%   region 575  liveness: 100%   region 576  liveness: 100%
region 577  liveness: 100%   region 578  liveness: 100%   region 579  liveness: 100%
region 580  liveness: 100%   region 581  liveness: 100%   region 582  liveness: 100%
region 583  liveness: 100%   region 584  liveness: 100%   region 585  liveness: 100%
region 586  liveness: 100%   region 587  liveness: 100%   region 588  liveness: 99 %
region 589  liveness: 100%   region 590  liveness: 100%   region 591  liveness: 100%
region 592  liveness: 100%   region 593  liveness: 100%   region 594  liveness: 100%
region 595  liveness: 100%   region 596  liveness: 100%   region 597  liveness: 99 %
region 598  liveness: 100%   region 599  liveness: 99 %   region 600  liveness: 100%
region 601  liveness: 99 %   region 602  liveness: 99 %   region 603  liveness: 99 %
region 604  liveness: 100%   region 605  liveness: 100%   region 606  liveness: 100%
region 607  liveness: 100%   region 608  liveness: 100%   region 609  liveness: 99 %
region 610  liveness: 100%   region 611  liveness: 99 %   region 612  liveness: 100%
region 613  liveness: 99 %   region 614  liveness: 99 %   region 615  liveness: 99 %
region 616  liveness: 100%   region 617  liveness: 99 %   region 618  liveness: 99 %
region 619  liveness: 100%   region 620  liveness: 99 %   region 621  liveness: 100%
region 622  liveness: 99 %   region 623  liveness: 100%   region 624  liveness: 100%
region 625  liveness: 99 %   region 626  liveness: 100%   region 627  liveness: 99 %
region 628  liveness: 100%   region 629  liveness: 100%   region 630  liveness: 99 %
region 631  liveness: 100%   region 632  liveness: 100%   region 633  liveness: 100%
region 634  liveness: 100%   region 635  liveness: 99 %   region 636  liveness: 100%
region 637  liveness: 100%   region 638  liveness: 100%   region 639  liveness: 100%
region 640  liveness: 100%   region 641  liveness: 100%   region 642  liveness: 99 %
region 643  liveness: 100%   region 644  liveness: 100%   region 645  liveness: 100%
region 646  liveness: 100%   region 647  liveness: 99 %   region 648  liveness: 100%
region 649  liveness: 100%   region 650  liveness: 100%   region 651  liveness: 100%
region 652  liveness: 100%   region 653  liveness: 100%   region 654  liveness: 99 %
region 655  liveness: 99 %   region 656  liveness: 100%   region 657  liveness: 100%
region 658  liveness: 99 %   region 659  liveness: 100%   region 660  liveness: 100%
region 661  liveness: 100%   region 662  liveness: 100%   region 663  liveness: 99 %
region 664  liveness: 100%   region 665  liveness: 100%   region 666  liveness: 100%
region 667  liveness: 99 %   region 668  liveness: 100%   region 669  liveness: 100%
region 670  liveness: 99 %   region 671  liveness: 100%   region 672  liveness: 100%
region 673  liveness: 100%   region 674  liveness: 100%   region 675  liveness: 100%
region 676  liveness: 100%   region 677  liveness: 100%   region 678  liveness: 99 %
region 679  liveness: 99 %   region 680  liveness: 99 %   region 681  liveness: 99 %
region 682  liveness: 100%   region 683  liveness: 100%   region 684  liveness: 99 %
region 685  liveness: 100%   region 686  liveness: 100%   region 687  liveness: 100%
region 688  liveness: 100%   region 689  liveness: 100%   region 690  liveness: 99 %
region 691  liveness: 99 %   region 692  liveness: 100%   region 693  liveness: 100%
region 694  liveness: 100%   region 695  liveness: 100%   region 696  liveness: 99 %
region 697  liveness: 99 %   region 698  liveness: 99 %   region 699  liveness: 99 %
region 700  liveness: 99 %   region 701  liveness: 99 %   region 702  liveness: 99 %
region 703  liveness: 99 %   region 704  liveness: 100%   region 705  liveness: 99 %
region 706  liveness: 99 %   region 707  liveness: 99 %   region 708  liveness: 99 %
region 709  liveness: 99 %   region 710  liveness: 99 %   region 711  liveness: 99 %
region 712  liveness: 99 %   region 713  liveness: 100%   region 714  liveness: 99 %
region 715  liveness: 99 %   region 716  liveness: 99 %   region 717  liveness: 99 %
region 718  liveness: 99 %   region 719  liveness: 99 %   region 720  liveness: 99 %
region 721  liveness: 99 %   region 722  liveness: 99 %   region 723  liveness: 99 %
region 724  liveness: 99 %   region 725  liveness: 99 %   region 726  liveness: 99 %
region 727  liveness: 99 %   region 728  liveness: 99 %   region 729  liveness: 99 %
region 730  liveness: 99 %   region 731  liveness: 99 %   region 732  liveness: 99 %
region 733  liveness: 99 %   region 734  liveness: 99 %   region 735  liveness: 100%
region 736  liveness: 99 %   region 737  liveness: 100%   region 738  liveness: 100%
region 739  liveness: 99 %   region 740  liveness: 99 %   region 741  liveness: 100%
region 742  liveness: 100%   region 743  liveness: 99 %   region 744  liveness: 99 %
region 745  liveness: 99 %   region 746  liveness: 100%   region 747  liveness: 100%
region 748  liveness: 99 %   region 749  liveness: 99 %   region 750  liveness: 100%
region 751  liveness: 99 %   region 752  liveness: 100%   region 753  liveness: 99 %
region 754  liveness: 99 %   region 755  liveness: 99 %   region 756  liveness: 99 %
region 757  liveness: 99 %   region 758  liveness: 100%   region 759  liveness: 99 %
region 760  liveness: 100%   region 761  liveness: 99 %   region 762  liveness: 99 %
region 763  liveness: 99 %   region 764  liveness: 99 %   region 765  liveness: 99 %
region 766  liveness: 100%   region 767  liveness: 100%   region 768  liveness: 99 %
region 769  liveness: 99 %   region 770  liveness: 99 %   region 771  liveness: 99 %
region 772  liveness: 99 %   region 773  liveness: 99 %   region 774  liveness: 99 %
region 775  liveness: 99 %   region 776  liveness: 99 %   region 777  liveness: 99 %
region 778  liveness: 99 %   region 779  liveness: 99 %   region 780  liveness: 100%
region 781  liveness: 99 %   region 782  liveness: 99 %   region 783  liveness: 99 %
region 784  liveness: 99 %   region 785  liveness: 99 %   region 786  liveness: 99 %
region 787  liveness: 99 %   region 788  liveness: 99 %   region 789  liveness: 99 %
region 790  liveness: 99 %   region 791  liveness: 99 %   region 792  liveness: 99 %
region 793  liveness: 99 %   region 794  liveness: 99 %   region 795  liveness: 99 %
region 796  liveness: 99 %   region 797  liveness: 100%   region 798  liveness: 100%
region 799  liveness: 99 %   region 800  liveness: 99 %   region 801  liveness: 99 %
region 802  liveness: 100%   region 803  liveness: 99 %   region 804  liveness: 100%
region 805  liveness: 99 %   region 806  liveness: 99 %   region 807  liveness: 99 %
region 808  liveness: 99 %   region 809  liveness: 99 %   region 810  liveness: 99 %
region 811  liveness: 99 %   region 812  liveness: 100%   region 813  liveness: 99 %
region 814  liveness: 100%   region 815  liveness: 99 %   region 816  liveness: 100%
region 817  liveness: 99 %   region 818  liveness: 99 %   region 819  liveness: 99 %
region 820  liveness: 99 %   region 821  liveness: 99 %   region 822  liveness: 99 %
region 823  liveness: 99 %   region 824  liveness: 99 %   region 825  liveness: 99 %
region 826  liveness: 100%   region 827  liveness: 99 %   region 828  liveness: 99 %
region 829  liveness: 99 %   region 830  liveness: 99 %   region 831  liveness: 99 %
region 832  liveness: 99 %   region 833  liveness: 99 %   region 834  liveness: 99 %
region 835  liveness: 99 %   region 836  liveness: 99 %   region 837  liveness: 99 %
region 838  liveness: 99 %   region 839  liveness: 99 %   region 840  liveness: 99 %
region 841  liveness: 99 %   region 842  liveness: 99 %   region 843  liveness: 99 %
region 844  liveness: 99 %   region 845  liveness: 100%   region 846  liveness: 99 %
region 847  liveness: 99 %   region 848  liveness: 99 %   region 849  liveness: 99 %
region 850  liveness: 99 %   region 851  liveness: 99 %   region 852  liveness: 99 %
region 853  liveness: 100%   region 854  liveness: 99 %   region 855  liveness: 99 %
region 856  liveness: 99 %   region 857  liveness: 99 %   region 858  liveness: 100%
region 859  liveness: 99 %   region 860  liveness: 99 %   region 861  liveness: 100%
region 862  liveness: 99 %   region 863  liveness: 99 %   region 864  liveness: 99 %
region 865  liveness: 100%   region 866  liveness: 99 %   region 867  liveness: 100%
region 868  liveness: 99 %   region 869  liveness: 99 %   region 870  liveness: 99 %
region 871  liveness: 99 %   region 872  liveness: 99 %   region 873  liveness: 99 %
region 874  liveness: 99 %   region 875  liveness: 99 %   region 876  liveness: 99 %
region 877  liveness: 99 %   region 878  liveness: 99 %   region 879  liveness: 99 %
region 880  liveness: 99 %   region 881  liveness: 99 %   region 882  liveness: 99 %
region 883  liveness: 99 %   region 884  liveness: 99 %   region 885  liveness: 99 %
region 886  liveness: 99 %   region 887  liveness: 99 %   region 888  liveness: 99 %
region 889  liveness: 99 %   region 890  liveness: 99 %   region 891  liveness: 99 %
region 892  liveness: 99 %   region 893  liveness: 99 %   region 894  liveness: 99 %
region 895  liveness: 99 %   region 896  liveness: 99 %   region 897  liveness: 99 %
region 898  liveness: 99 %   region 899  liveness: 99 %   region 900  liveness: 99 %
region 901  liveness: 99 %   region 902  liveness: 99 %   region 903  liveness: 99 %
region 904  liveness: 99 %   region 905  liveness: 99 %   region 906  liveness: 99 %
region 907  liveness: 99 %   region 908  liveness: 99 %   region 909  liveness: 99 %
region 910  liveness: 99 %   region 911  liveness: 99 %   region 912  liveness: 99 %
region 913  liveness: 99 %   region 914  liveness: 99 %   region 915  liveness: 99 %
region 916  liveness: 99 %   region 917  liveness: 99 %   region 918  liveness: 99 %
region 919  liveness: 99 %   region 920  liveness: 99 %   region 921  liveness: 99 %
region 922  liveness: 99 %   region 923  liveness: 99 %   region 924  liveness: 99 %
region 925  liveness: 99 %   region 926  liveness: 99 %   region 927  liveness: 99 %
region 928  liveness: 99 %   region 929  liveness: 99 %   region 930  liveness: 99 %
region 931  liveness: 99 %   region 932  liveness: 99 %   region 933  liveness: 99 %
region 934  liveness: 99 %   region 935  liveness: 99 %   region 936  liveness: 99 %
region 937  liveness: 99 %   region 938  liveness: 99 %   region 939  liveness: 99 %
region 940  liveness: 99 %   region 941  liveness: 99 %   region 942  liveness: 99 %
region 943  liveness: 99 %   region 944  liveness: 99 %   region 945  liveness: 99 %
region 946  liveness: 99 %   region 947  liveness: 99 %   region 948  liveness: 99 %
region 949  liveness: 99 %   region 950  liveness: 99 %   region 951  liveness: 99 %
region 952  liveness: 99 %   region 953  liveness: 99 %   region 954  liveness: 99 %
region 955  liveness: 99 %   region 956  liveness: 99 %   region 957  liveness: 99 %
region 958  liveness: 99 %   region 959  liveness: 99 %   region 960  liveness: 99 %
region 961  liveness: 99 %   region 962  liveness: 99 %   region 963  liveness: 99 %
region 964  liveness: 99 %   region 965  liveness: 99 %   region 966  liveness: 99 %
region 967  liveness: 99 %   region 968  liveness: 99 %   region 969  liveness: 99 %
region 970  liveness: 99 %   region 971  liveness: 99 %   region 972  liveness: 99 %
region 973  liveness: 99 %   region 974  liveness: 99 %   region 975  liveness: 99 %
region 976  liveness: 99 %   region 977  liveness: 100%   region 978  liveness: 99 %
region 979  liveness: 99 %   region 980  liveness: 99 %   region 981  liveness: 99 %
region 982  liveness: 99 %   region 983  liveness: 100%   region 984  liveness: 99 %
region 985  liveness: 99 %   region 986  liveness: 99 %   region 987  liveness: 99 %
region 988  liveness: 99 %   region 989  liveness: 99 %   region 990  liveness: 99 %
region 991  liveness: 99 %   region 992  liveness: 99 %   region 993  liveness: 99 %
region 994  liveness: 99 %   region 995  liveness: 99 %   region 996  liveness: 99 %
region 997  liveness: 99 %   region 998  liveness: 99 %   region 999  liveness: 99 %
region 1000 liveness: 99 %   region 1001 liveness: 100%   region 1002 liveness: 99 %
region 1003 liveness: 99 %   region 1004 liveness: 99 %   region 1005 liveness: 99 %
region 1006 liveness: 99 %   region 1007 liveness: 99 %   region 1008 liveness: 99 %
region 1009 liveness: 99 %   region 1010 liveness: 99 %   region 1011 liveness: 99 %
region 1012 liveness: 99 %   region 1013 liveness: 99 %   region 1014 liveness: 99 %
region 1015 liveness: 99 %   region 1016 liveness: 99 %   region 1017 liveness: 99 %
region 1018 liveness: 99 %   region 1019 liveness: 99 %   region 1020 liveness: 99 %
region 1021 liveness: 99 %   region 1022 liveness: 99 %   region 1023 liveness: 99 %
region 1024 liveness: 99 %   region 1025 liveness: 99 %   region 1026 liveness: 99 %
region 1027 liveness: 99 %   region 1028 liveness: 99 %   region 1029 liveness: 99 %
region 1030 liveness: 99 %   region 1031 liveness: 99 %   region 1032 liveness: 99 %
region 1033 liveness: 99 %   region 1034 liveness: 99 %   region 1035 liveness: 99 %
region 1036 liveness: 99 %   region 1037 liveness: 99 %   region 1038 liveness: 99 %
region 1039 liveness: 100%   region 1040 liveness: 99 %   region 1041 liveness: 99 %
region 1042 liveness: 99 %   region 1043 liveness: 100%   region 1044 liveness: 99 %
region 1045 liveness: 99 %   region 1046 liveness: 99 %   region 1047 liveness: 99 %
region 1048 liveness: 99 %   region 1049 liveness: 99 %   region 1050 liveness: 99 %
region 1051 liveness: 99 %   region 1052 liveness: 91 %   region 1053 liveness: 99 %
region 1054 liveness: 99 %   region 1055 liveness: 100%   region 1056 liveness: 99 %
region 1057 liveness: 99 %   region 1058 liveness: 99 %   region 1059 liveness: 99 %
region 1060 liveness: 99 %   region 1061 liveness: 99 %   region 1062 liveness: 99 %
region 1063 liveness: 73 %   region 1064 liveness: 99 %   region 1065 liveness: 99 %
region 1066 liveness: 100%   region 1067 liveness: 99 %   region 1068 liveness: 99 %
region 1069 liveness: 99 %   region 1070 liveness: 99 %   region 1071 liveness: 99 %
region 1072 liveness: 99 %   region 1073 liveness: 99 %   region 1074 liveness: 99 %
region 1075 liveness: 99 %   region 1076 liveness: 99 %   region 1077 liveness: 99 %
region 1078 liveness: 99 %   region 1079 liveness: 59 %   region 1080 liveness: 99 %
region 1081 liveness: 99 %   region 1082 liveness: 99 %   region 1083 liveness: 49 %
region 1084 liveness: 99 %   region 1085 liveness: 99 %   region 1086 liveness: 99 %
region 1087 liveness: 99 %   region 1088 liveness: 99 %   region 1089 liveness: 99 %
region 1090 liveness: 99 %   region 1091 liveness: 32 %   region 1092 liveness: 99 %
region 1093 liveness: 99 %   region 1094 liveness: 99 %   region 1095 liveness: 99 %
region 1096 liveness: 99 %   region 1097 liveness: 99 %   region 1098 liveness: 99 %
region 1099 liveness: 99 %   region 1100 liveness: 99 %   region 1101 liveness: 99 %
region 1102 liveness: 99 %   region 1103 liveness: 99 %   region 1104 liveness: 99 %
region 1105 liveness: 99 %   region 1106 liveness: 99 %   region 1107 liveness: 99 %
region 1108 liveness: 99 %   region 1109 liveness: 99 %   region 1110 liveness: 99 %
region 1111 liveness: 99 %   region 1112 liveness: 99 %   region 1113 liveness: 99 %
region 1114 liveness: 99 %   region 1115 liveness: 99 %   region 1116 liveness: 99 %
region 1117 liveness: 91 %   region 1118 liveness: 100%   region 1119 liveness: 44 %
region 1120 liveness: 99 %   region 1121 liveness: 99 %   region 1122 liveness: 99 %
region 1123 liveness: 99 %   region 1124 liveness: 99 %   region 1125 liveness: 99 %
region 1126 liveness: 99 %   region 1127 liveness: 99 %   region 1128 liveness: 100%
region 1129 liveness: 99 %   region 1130 liveness: 99 %   region 1131 liveness: 99 %
region 1132 liveness: 99 %   region 1133 liveness: 99 %   region 1134 liveness: 99 %
region 1135 liveness: 99 %   region 1136 liveness: 99 %   region 1137 liveness: 99 %
region 1138 liveness: 49 %   region 1139 liveness: 99 %   region 1140 liveness: 99 %
region 1141 liveness: 99 %   region 1142 liveness: 99 %   region 1143 liveness: 99 %
region 1144 liveness: 99 %   region 1145 liveness: 99 %   region 1146 liveness: 99 %
region 1147 liveness: 99 %   region 1148 liveness: 99 %   region 1149 liveness: 99 %
region 1150 liveness: 99 %   region 1151 liveness: 99 %   region 1152 liveness: 99 %
region 1153 liveness: 99 %   region 1154 liveness: 99 %   region 1155 liveness: 44 %
region 1156 liveness: 99 %   region 1157 liveness: 42 %   region 1158 liveness: 2  %
region 1159 liveness: 99 %   region 1160 liveness: 99 %   region 1161 liveness: 99 %
region 1162 liveness: 99 %   region 1163 liveness: 99 %   region 1164 liveness: 99 %
region 1165 liveness: 99 %   region 1166 liveness: 99 %   region 1167 liveness: 61 %
region 1168 liveness: 99 %   region 1169 liveness: 100%   region 1170 liveness: 100%
region 1171 liveness: 100%   region 1172 liveness: 100%   region 1173 liveness: 100%
region 1174 liveness: 47 %   region 1175 liveness: 100%   region 1176 liveness: 100%
region 1177 liveness: 100%   region 1178 liveness: 100%   region 1179 liveness: 100%
region 1180 liveness: 100%   region 1181 liveness: 100%   region 1182 liveness: 100%
region 1183 liveness: 100%   region 1184 liveness: 100%   region 1185 liveness: 100%
region 1186 liveness: 100%   region 1187 liveness: 100%   region 1188 liveness: 100%
region 1189 liveness: 100%   region 1190 liveness: 100%   region 1191 liveness: 100%
region 1192 liveness: 100%   region 1193 liveness: 100%   region 1194 liveness: 100%
region 1195 liveness: 100%   region 1196 liveness: 100%   region 1197 liveness: 100%
region 1198 liveness: 100%   region 1199 liveness: 100%   region 1200 liveness: 100%
region 1201 liveness: 100%   region 1202 liveness: 100%   region 1203 liveness: 100%
region 1204 liveness: 100%   region 1205 liveness: 100%   region 1206 liveness: 100%
region 1207 liveness: 100%   region 1208 liveness: 100%   region 1209 liveness: 100%
region 1210 liveness: 100%   region 1211 liveness: 100%   region 1212 liveness: 100%
region 1213 liveness: 100%   region 1214 liveness: 100%   region 1215 liveness: 100%
region 1216 liveness: 100%   region 1217 liveness: 100%   region 1218 liveness: 100%
region 1219 liveness: 100%   region 1220 liveness: 100%   region 1221 liveness: 100%
region 1222 liveness: 100%   region 1223 liveness: 100%   region 1224 liveness: 100%
region 1225 liveness: 100%   region 1226 liveness: 100%   region 1227 liveness: 100%
region 1228 liveness: 100%   region 1229 liveness: 100%   region 1230 liveness: 100%
region 1231 liveness: 100%   region 1232 liveness: 100%   region 1233 liveness: 100%
region 1234 liveness: 100%   region 1235 liveness: 100%   region 1236 liveness: 100%
region 1237 liveness: 100%   region 1238 liveness: 100%   region 1239 liveness: 100%
region 1240 liveness: 100%   region 1241 liveness: 100%   region 1242 liveness: 100%
region 1243 liveness: 100%   region 1244 liveness: 100%   region 1245 liveness: 100%
region 1246 liveness: 100%   region 1247 liveness: 100%   region 1248 liveness: 100%
region 1249 liveness: 100%   region 1250 liveness: 100%   region 1251 liveness: 100%
region 1252 liveness: 100%   region 1253 liveness: 100%   region 1254 liveness: 100%
region 1255 liveness: 100%   region 1256 liveness: 100%   region 1257 liveness: 100%
region 1258 liveness: 100%   region 1259 liveness: 100%   region 1260 liveness: 100%
region 1261 liveness: 100%   region 1262 liveness: 100%   region 1263 liveness: 100%
region 1264 liveness: 100%   region 1265 liveness: 100%   region 1266 liveness: 100%
region 1267 liveness: 100%   region 1268 liveness: 100%   region 1269 liveness: 100%
region 1270 liveness: 100%   region 1271 liveness: 100%   region 1272 liveness: 100%
region 1273 liveness: 100%   region 1274 liveness: 100%   region 1275 liveness: 100%
region 1276 liveness: 100%   region 1277 liveness: 100%   region 1278 liveness: 100%
region 1279 liveness: 100%   region 1280 liveness: 100%   region 1281 liveness: 100%
region 1282 liveness: 100%   region 1283 liveness: 100%   region 1284 liveness: 100%
region 1285 liveness: 100%   region 1286 liveness: 100%   region 1287 liveness: 100%
region 1288 liveness: 100%   region 1289 liveness: 100%   region 1290 liveness: 100%
region 1291 liveness: 100%   region 1292 liveness: 100%   region 1293 liveness: 100%
region 1294 liveness: 100%   region 1295 liveness: 100%   region 1296 liveness: 100%
region 1297 liveness: 100%   region 1298 liveness: 100%   region 1299 liveness: 100%
region 1300 liveness: 100%   region 1301 liveness: 100%   region 1302 liveness: 100%
region 1303 liveness: 100%   region 1304 liveness: 100%   region 1305 liveness: 100%
region 1306 liveness: 100%   region 1307 liveness: 100%   region 1308 liveness: 100%
region 1309 liveness: 100%   region 1310 liveness: 100%   region 1311 liveness: 100%
region 1312 liveness: 100%   region 1313 liveness: 100%   region 1314 liveness: 100%
region 1315 liveness: 100%   region 1316 liveness: 100%   region 1317 liveness: 100%
region 1318 liveness: 100%   region 1319 liveness: 100%   region 1320 liveness: 100%
region 1321 liveness: 100%   region 1322 liveness: 100%   region 1323 liveness: 100%
region 1324 liveness: 100%   region 1325 liveness: 100%   region 1326 liveness: 100%
region 1327 liveness: 100%   region 1328 liveness: 100%   region 1329 liveness: 100%
region 1330 liveness: 100%   region 1331 liveness: 100%   region 1332 liveness: 100%
region 1333 liveness: 100%   region 1334 liveness: 100%   region 1335 liveness: 100%
region 1336 liveness: 100%   region 1337 liveness: 100%   region 1338 liveness: 100%
region 1339 liveness: 100%   region 1340 liveness: 100%   region 1341 liveness: 100%
region 1342 liveness: 100%   region 1343 liveness: 100%   region 1344 liveness: 100%
region 1345 liveness: 100%   region 1346 liveness: 100%   region 1347 liveness: 100%
region 1348 liveness: 100%   region 1349 liveness: 100%   region 1350 liveness: 100%
region 1351 liveness: 100%   region 1352 liveness: 100%   region 1353 liveness: 100%
region 1354 liveness: 100%   region 1355 liveness: 100%   region 1356 liveness: 100%
region 1357 liveness: 100%   region 1358 liveness: 100%   region 1359 liveness: 100%
region 1360 liveness: 100%   region 1361 liveness: 100%   region 1362 liveness: 100%
region 1363 liveness: 100%   region 1364 liveness: 100%   region 1365 liveness: 100%
region 1366 liveness: 100%   region 1367 liveness: 100%   region 1368 liveness: 100%
region 1369 liveness: 100%   region 1370 liveness: 100%   region 1371 liveness: 100%
region 1372 liveness: 100%   region 1373 liveness: 100%   region 1374 liveness: 100%
region 1375 liveness: 100%   region 1376 liveness: 100%   region 1377 liveness: 100%
region 1378 liveness: 100%   region 1379 liveness: 100%   region 1380 liveness: 100%
region 1381 liveness: 100%   region 1382 liveness: 100%   region 1383 liveness: 100%
```
