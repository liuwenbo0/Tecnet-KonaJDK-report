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

## 各个阶段的含义

```log
[0.172s][info][gc,phases   ] GC(0)   Pre Evacuate Collection Set: 0.1ms
[0.172s][info][gc,phases   ] GC(0)   Merge Heap Roots: 0.1ms
[0.172s][info][gc,phases   ] GC(0)   Evacuate Collection Set: 5.7ms
[0.172s][info][gc,phases   ] GC(0)   Post Evacuate Collection Set: 0.3ms
[0.172s][info][gc,phases   ] GC(0)   Other: 0.7ms
```

G1GC 对 Young Generation 的回收分为四个阶段：

- Pre Evacuate Collection Set (标记出年轻代和老年代中需要清理的对象)
- Merge Heap Roots (更新和合并堆中的根集合，确保在随后的内存回收过程中，所有可达对象都不会被错误地回收)
- Evacuate Collection Set (将活动对象从一个区域复制到另一个区域，并标记原区域为可回收)
- Post Evacuate Collection Set(处理拷贝后的一些清理工作，如更新对象引用等)

G1GC 对 Young Generation 的回收，通常会增加老区中存活对象的数量，因为一部分 Young Generation 的对象会被复制到老区中。
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
