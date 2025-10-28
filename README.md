zookeeper
=========

分布式系统服务ZooKeeper的学习历程

####一.入门
[ZooKeeper Overview](https://github.com/llohellohe/zookeeper/blob/master/docs/overview.md)

[Watcher的简单例子](https://github.com/llohellohe/zookeeper/blob/master/docs/java-example.md)

[Watcher例子的类图](https://raw.github.com/llohellohe/zookeeper/master/docs/class-java-example.png)

####二.O'Reilly.ZooKeeper.Distributed process coordination.2013笔记
[按章节笔记](https://github.com/llohellohe/llohellohe.github.com/tree/master/readers/ZooKeeper)

####三.深入
[分布式一致性算法Paxos](https://github.com/llohellohe/llohellohe.github.com/blob/master/_posts/2014-01-04-paxos.md)

[源代码解读之ZooKeeper](https://github.com/llohellohe/llohellohe.github.com/blob/master/_posts/2014-01-04-read-zookeeper-source-code-zookeeper.md)

[源代码解读之ClientCnxn](https://github.com/llohellohe/llohellohe.github.com/blob/master/_posts/2014-01-06-read-zookeeper-source-code-client-cnxn.md)

[源代码解读之ClientCnxnSocketNIO](https://github.com/llohellohe/llohellohe.github.com/blob/master/_posts/2014-02-02-read-zookeeper-source-code-nio-socket.md)

[源代码解读之Jute生成传输协议消息体](https://github.com/llohellohe/llohellohe.github.com/tree/master/readers/ZooKeeper/11-传输协议.md)


### async 模块的 Watch/Ensure/Retry 准则

1) 选主（LeaderElector，观察者-精确触发）
- 先看事实再设表：`exists(path, null)` → 若存在，`exists(path, this)` 监听 NodeDeleted；若不存在则 `createEphemeral` 抢主。
- ConnectionLoss/Timeout 统一回入口自愈；`onElected` 用一次性门闩保证幂等。

2) 监控子列表（WorkersWatcher，快照+一次性watch）
- `getChildrenOrEmpty(parent, this)` 一次拿“全量快照+一次性watch”。
- 基于上帧快照计算 diff 并幂等应用；失败走自愈并回到刷新入口。

3) 等待任务结果（TaskResultWatcher，节点可能不存在）
- 不存在先设表：`exists(resultPath, this)` 等 NodeCreated；存在则 `getData(resultPath, this)` 拉取。
- 完成与回调在单线程执行器中串行执行；对瞬时错误做退避重试。

通用建议
- 根路径先 `ensurePersistent(...)` 再 watch/操作。
- 使用一次性 watch，处理后立刻重挂。
- 每个组件独立单线程执行器，序列化推进，减少竞态复杂度。

#### 补充：读-设表 与 直接操作 的取舍
- 观察/监听类（LeaderElector/WorkersWatcher/TaskResultWatcher）：目标是“不丢事件”。
  - 先读事实，再在需要的一侧挂“一次性 watch”。
  - 节点可能不存在：`exists(path, this)` 先设表；存在再 `getData(path, this)` 取值。
  - 需要全量快照：`getChildrenOrEmpty(parent, this)` 同时取全集并设表。
- 条件决策类：需要基于内容/版本/拓扑做分支时，先读再决策（例如 pick worker）。
- 直接操作类（create/delete/set）：直接尝试操作并按返回码分支（`NodeExists`/`NoNode`），必要时自愈重试并回到统一入口再读事实，避免多一次“先读”。
