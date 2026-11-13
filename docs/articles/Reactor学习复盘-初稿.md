# 跟着 Codex 学 Reactor：我写了一堆测试，才弄明白代码到底在哪个线程执行

最近在学习 AgentScope4j，代码里经常看到 `Mono` 和 `Flux`。之前也听过响应式编程、异步这些词，但一看到一长串操作符，还是会犯嘀咕：这代码是从上往下执行的吗？什么时候开始执行？又是在哪个线程上执行的？

于是我让 Codex 带着我从头走一遍。它没有一上来讲一堆概念，而是让我先写一个测试，预测输出，再运行看看。过程中我不断追问，也改了它给的代码。后来这些小实验都留在了项目的 `ReactorStudyTest` 里。

这篇就按当时的过程来记录。代码里的任务、查询和延迟都是模拟的；除了明确注明是我当时贴出来的日志，其他输出和线程图都是根据示例画的，线程编号和调度顺序不保证每次一样。示例对应项目的 Java 21 和 Reactor Core 3.8.6。

## 第一步：先写代码，但不让它执行

Codex 让我先写这样一段，暂时把最后的 `block()` 注释掉：

```java
@Test
void creation() {
    System.out.println("1. 开始组装，线程：" + thread());

    Mono<String> result = Mono.fromSupplier(() -> {
        System.out.println("2. 执行查询，线程：" + thread());
        return "任务：拍午餐照片";
    }).map(value -> {
        System.out.println("3. 处理结果，线程：" + thread());
        return value + "，状态：待完成";
    });

    System.out.println("4. 组装结束，线程：" + thread());

    // String value = result.block();
    // System.out.println("5. 收到结果：" + value);
}
```

我原以为代码都写在这里了，至少里面的查询会执行。但保持注释时，能看到的是“开始组装”和“组装结束”，`fromSupplier` 和 `map` 里的打印没有出现。

然后再打开 `block()`。这个例子的预期顺序就变成：

```text
1. 开始组装
4. 组装结束
2. 执行查询
3. 处理结果
5. 收到结果
```

我当时问了一句：**意思是 `.block()` 后才会执行？**

对于这个例子，是的。但 Codex 补充了一句：真正需要分清的是**订阅**和**等待**。

`block()` 会订阅这个 `Mono`，然后等待结果。前面的 `Mono.fromSupplier(...)` 和 `map(...)` 先把流程组装好；`block()` 发起订阅后，Supplier 产生数据，`map` 再处理数据。

```text
组装：fromSupplier → map

订阅：block() → map → fromSupplier
数据：block() ← map ← fromSupplier
```

这两条箭头的方向帮了我很大的忙：订阅关系从下游往上游建立，数据再从上游流向下游。这个同步例子没有指定线程切换，所以查询和 `map` 都在调用 `block()` 的线程上执行。

但 `block()` 不是唯一的订阅方式。后面的测试用了 `subscribe()`，同样会发起订阅；它不会像 `block()` 那样让调用线程等待结果。异步示例里，如果程序结束得太快，还得考虑有没有时间收到结果。

## 同一个 Mono，订阅两次会怎样？

接下来 Codex 让我预测：如果 `result` 只创建一次，却连续调用两次 `block()`，查询会执行几次？

我回答：**两次；信息不变？**

在刚才的例子里是这样。两次 `block()` 是两次订阅，Supplier 会运行两次；但是它每次返回的都是写死的“拍午餐照片”，所以内容相同。

为了看清楚“执行两次”和“结果相同”不是一回事，我又写了一个 `testTwice`：

```java
Mono<Long> time = Mono.fromSupplier(System::currentTimeMillis);

System.out.println(time.block());
Thread.sleep(1000);
System.out.println(time.block());
```

两次订阅分别获取当前时间，中间让测试线程休息一秒，打印的时间戳通常不同。这里的 `Thread.sleep` 只是为了拉开观察时间，不是在展示 Reactor 的异步能力。

当时我开始意识到：**同一个变量，不一定等于只执行一次。** 如果上游代表模型或工具调用，重复订阅可能带来重复调用；至于具体框架的某个 `Mono` 是否如此，还要看它的实现，不能只看返回类型就下结论。缓存、共享或已经启动的外部任务，都可能改变行为。

## 为什么 `just` 里的查询已经执行了？

刚理解了一点“订阅才执行”，Codex 又让我比较：

```java
System.out.println("开始组装");

Mono<String> just = Mono.just(query("just"));
Mono<String> supplier = Mono.fromSupplier(() -> query("supplier"));

System.out.println("组装结束");

System.out.println("订阅 just");
just.block();

System.out.println("订阅 supplier");
supplier.block();
```

其中 `query` 只是打印并返回一个固定字符串：

```java
private String query(String name) {
    System.out.println("执行查询：" + name);
    return "查询结果";
}
```

Codex 问我两次查询什么时候打印。我看着第一行说：**立马执行的。**

原因其实是 Java 自己的参数求值顺序。执行 `Mono.just(query("just"))` 时，要先调用 `query` 得到参数，再把结果交给 `just`：

```java
String value = query("just");
Mono<String> just = Mono.just(value);
```

另一行传入的是函数：`fromSupplier` 先保存它，在这个例子的订阅和请求发生后才调用它。

```text
组装时：query("just") → just 保存结果
        保存 supplier 函数

订阅时：just 发出已有结果
        supplier 执行查询并发出结果
```

所以这一步不能总结成“Reactor 里的代码都等订阅才执行”。要具体看：**哪个表达式在 Java 组装时已经执行，哪个函数被交给了 Reactor 之后再调用。** `fromSupplier` 也不会自动把阻塞查询变成非阻塞查询。

## map 之后，为什么还要有 flatMap？

下一步是把数据交给下一个操作。先写一个普通转换：

```java
Mono.just("拍午餐照片")
    .map(task -> {
        System.out.println("map 收到：" + task);
        return task + "，状态：待完成";
    })
    .subscribe(result -> System.out.println("最终收到：" + result));
```

这里 `map` 收到 `String`，返回的还是一个普通 `String`。因为末尾用了 `subscribe()`，这条同步链会执行。

接着 Codex 把查询任务状态也写成一个 `Mono`，让我用 `flatMap` 接上：

```java
Mono.just("拍午餐照片")
    .flatMap(task -> Mono.fromSupplier(() -> {
        System.out.println("查询任务状态：" + task);
        return "待完成";
    }))
    .subscribe(status -> System.out.println("最终状态：" + status));
```

我当时的理解是：**是不是把 `Mono<String>` 里的 `String` 提取出来，作为下一个方法的入参？**

这样想能抓到一点直觉，但还不够完整。`flatMap` 接上的是函数返回的内部 `Mono`：它订阅这个内部流，把内部流发出的值、完成或错误继续传给下游。这个内部流也可能是异步的、没有值，或者出错。

类型上也容易看出来：如果用 `map` 返回一个 `Mono<String>`，结果会是 `Mono<Mono<String>>`；这里用 `flatMap`，最终得到 `Mono<String>`。

不过，**`flatMap` 自己并不意味着新线程**。这一点，我是在接下来的对比里才真正注意到。

## 从 Flux 开始，我被线程名绕进去了

前面一条任务最多产生一个结果。Codex 接着让我用 `Flux` 处理多条任务：

```java
Flux.just("拍午餐照片", "整理学习笔记", "复习 Java")
    .map(task -> "待完成：" + task)
    .subscribe(System.out::println);
```

我问：`Flux.just(...)` 是异步的，还是一个一个顺序来的？

在这个没有额外调度的例子里，它按给出的顺序发出，处理也同步进行。`Flux` 可以表达多条数据，但不代表每条数据都会自动分配一个线程。

随后 Codex 让我试 `flatMap`，每个任务接一个延迟返回的 `Mono`。我又把测试改成同步版本：

```java
Flux.just("拍午餐照片", "整理学习笔记")
    .flatMap(task -> queryStatus(task))
    .doOnNext(result -> System.out.println(result + "，线程："
        + Thread.currentThread().getName()))
    .blockLast();

// queryStatus 返回 Mono.just(task + "：待完成")
```

我发现这次执行线程是 `main`；换成带 `delayElement` 的版本，打印线程又不一样。于是接着问：**异步是通过 `delayElement` 来实现的吗？**

在这个 demo 中，线程切换和延迟来自 `delayElement`，不是来自 `flatMap`。Codex 提供的查询方法给第一个任务延迟两秒、第二个任务延迟一秒：

```java
private Mono<String> queryStatusAsync(String task) {
    int seconds = task.equals("拍午餐照片") ? 2 : 1;
    return Mono.just(task + "：待完成")
        .delayElement(Duration.ofSeconds(seconds));
}
```

`delayElement` 在这里延迟的是值向下游发出。`flatMap` 可以同时接入多个内部流并合并结果，因此后发出的第二个任务，可能先把结果交到下游。

```text
示意：时间从左向右，线程编号不代表固定值

main          创建 A 的内部流 ── 创建 B 的内部流
                    │                 │
parallel-1          └─ 等约 2 秒 ─────────────→ A
parallel-2                            └─ 等约 1 秒 ─→ B

下游可能先收到 B，再收到 A
```

这里的等待可以重叠，不等于证明两个真正的业务查询正在两个 CPU 核心上并行。我们的代码只是在模拟延迟。

### 我把线程名拼进了结果

我还轻微改了一下辅助方法，把创建字符串时的线程名拼进去：

```java
return Mono.just(task + "：待完成" + Thread.currentThread().getName())
    .delayElement(Duration.ofSeconds(seconds));
```

当时我贴出了这两行实际日志：

```text
整理学习笔记：待完成main，线程：parallel-2
拍午餐照片：待完成main，线程：parallel-1
```

我看懵了：**都是 `main` 啊，为什么后面又是 `parallel`？**

原来一行里记录的是两个时间点。`Mono.just(...)` 的参数要先计算，字符串里的 `main` 是拼接字符串时记录的；逗号后的 `parallel-2` 是延迟结束后，`doOnNext` 打印时的当前线程。

```text
main：       拼字符串，写入 "main" → 交给 Mono.just
parallel：   延迟结束 → doOnNext 打印当前线程
```

字符串里的“main”是数据，线程切换后不会自动变。看日志不能只数出现了几个线程名，还得看**每个线程名究竟在哪里取的**。

### 如果必须按顺序收到呢？

我又试了 `concatMap`。这次它等前一个内部流结束，再处理下一个：

```java
Flux.just("拍午餐照片", "整理学习笔记")
    .concatMap(task -> queryStatusAsync(task))
    .doOnNext(result -> System.out.println(result + "，线程："
        + Thread.currentThread().getName()))
    .blockLast();
```

对于这个每个内部 `Mono` 发出一个值的例子，第一个等约两秒，完成后第二个再等约一秒，结果保持任务顺序：

```text
A： [──── 约 2 秒 ────] → A
B：                       [─ 约 1 秒 ─] → B
```

之前那两行 `flatMap` 的实际日志不能直接拿来当作 `concatMap` 的输出。后一个内部流会在前一个完成后才开始处理，创建第二个内部流时可能已经处于前一个信号所在的线程。想知道实际线程，仍然要在对应位置打印。

## 我让 Codex 画了线程图

看到这里，我还是有点懵，就让 Codex 把 `main` 和其他线程分开画出来，再看 `subscribeOn` 和 `publishOn`。

先看 `subscribeOnDemo`。`queryTask()` 里有一个 `Thread.sleep(2000)`，用来模拟阻塞查询：

```java
String result = Mono.fromCallable(() -> queryTask())
    .subscribeOn(Schedulers.boundedElastic())
    .block();

System.out.println("收到：" + result +
    "，线程：" + Thread.currentThread().getName());
```

示意过程：

```text
main                         boundedElastic 工作线程
  │                             │
  ├─ block，发起订阅 ──────────→ 订阅上游、执行 queryTask
  ├─ 等待                       │
  │←──────────────────────────── 返回结果
  └─ block 返回，继续打印
```

我问：`subscribeOn(Schedulers.boundedElastic())` 是不是在其他线程进行订阅？可以这么理解这个例子：它安排订阅及相关请求的执行上下文，所以这里同步的 `fromCallable` 查询在指定调度器上执行。`subscribeOn` 自己不发起订阅；后面如果有其他异步操作或调度器，也不能保证所有回调永远都在同一线程。

接着是 `publishOnDemo`：

```java
Flux.just("拍午餐照片")
    .map(task -> {
        System.out.println("第一个 map：" + Thread.currentThread().getName());
        return task;
    })
    .publishOn(Schedulers.boundedElastic())
    .map(task -> {
        System.out.println("第二个 map：" + Thread.currentThread().getName());
        return task;
    })
    .blockLast();
```

```text
main：            Flux.just → 第一个 map → publishOn
                                              │
boundedElastic：                              └→ 第二个 map → 结果
main：                                                          blockLast 返回
```

这次 `publishOn` 影响它后面的信号处理；前面的 `map` 不会因为它而跑到指定线程。`blockLast()` 返回后，继续执行打印的仍是调用它的 `main`。

我后来把每次处理看成一个事件，先问：**这个事件发生时，是哪条线程在执行它？** 这样读日志比直接给整个流贴一个“异步线程”的标签更容易理解。

## 错误出现后，第三个任务去哪了？

接下来，Codex 让第二条任务抛异常：

```java
Flux.just("拍午餐照片", "整理学习笔记", "复习 Java")
    .map(task -> {
        if (task.equals("整理学习笔记")) {
            throw new IllegalStateException("查询任务失败");
        }
        return task + "：待完成";
    })
    .onErrorResume(error -> Mono.just("暂时无法查询任务"))
    .doOnNext(System.out::println)
    .blockLast();
```

第一条正常处理，第二条发生错误后，原来的流终止；`onErrorResume` 接上备用流，发出“暂时无法查询任务”。第三条不会在原流里从出错位置继续执行。

我还发现 `onErrorResume` 的打印像是在 `main` 线程。这里是因为异常由同步链上的 `main` 触发，不是说这个操作符固定使用 `main`。

我做了两个版本，把 `doOnNext` 放在 `onErrorResume` 前后。放在前面时，它看不到后面备用流发出的值；放在后面，才能观察到备用值。

如果希望的是**每个任务各自失败、其他任务继续**，就得把错误处理移进每个任务的内部流：

```java
Flux.just("拍午餐照片", "整理学习笔记", "复习 Java")
    .flatMap(task -> queryTaskStatus(task)
        .onErrorResume(error -> Mono.just(task + "：暂时无法查询")))
    .doOnNext(System.out::println)
    .blockLast();
```

同样叫“处理错误”，一个是在整条上游流失败后换备用流，另一个是让每个任务的内部流分别处理自己的错误。放的位置不同，结果就不一样。

## 消费者请求数据：发完怎么通知？提前不想要了怎么办？

学背压时，Codex 写了 `BaseSubscriber`，先订阅，但不在 `hookOnSubscribe` 里请求数据，然后手动请求：

```java
Flux.range(1, 5)
    .doOnRequest(n -> System.out.println("上游收到请求：" + n))
    .subscribe(subscriber);

subscriber.request(2);
subscriber.request(1);
subscriber.cancel();
```

`subscriber` 在 `hookOnNext` 打印收到的数字，也在 `hookOnComplete` 里打印完成信息。前两次请求依次接收 1、2、3，随后主动取消，剩下的就不再请求。

我问：**如果生产者已经没有数据了，怎么告诉订阅者“全部发完了，可以终止了”？**

正常完成会通过 `onComplete` 通知；出错则通过 `onError` 通知。上面的代码只请求了三个值就主动取消，因此不会因为这次取消收到正常完成信号。如果让这个有限数据源发完五个值，正常情况下才会收到完成信号。

要注意，这个测试特意使用了自定义请求。常见的 `subscribe()` Lambda 形式通常会发出无界请求；链里的操作符也可能调整向上游请求的数量。

之后我又问：**怎么感知并取消？是不是要设置一个变量，不断查看它的状态？**

我们分别试了 `take` 和 `Disposable`。

```java
Flux.range(1, 5)
    .doOnCancel(() -> System.out.println("上游收到取消"))
    .take(2)
    .doOnNext(value -> System.out.println("收到：" + value))
    .doOnComplete(() -> System.out.println("下游完成"))
    .subscribe();
```

我当时觉得这个例子有点奇怪：**既然只取两个，为什么不直接写 `Flux.range(1, 2)`？**

如果源本来就是固定的两个数字，当然可以直接写。这里的 `range(1, 5)` 只是方便观察：真实场景里，上游可能是已有的长数据流，下游才决定“我只要前两个”。`take(2)` 达到数量后会取消上游，并向自己的下游正常完成。`doOnCancel` 和 `doOnComplete` 分别放在 `take` 两边，正是为了观察两边不同的信号。

但我想看的还有一种情况：**停止的时机不是写死的，而是外部动态决定的。** 最后测试里用 `Flux.interval` 模拟持续发数据，保存 `subscribe()` 返回的 `Disposable`，收到两条后再从测试线程调用 `dispose()`：

```java
CountDownLatch receivedTwo = new CountDownLatch(2);
CountDownLatch cancelled = new CountDownLatch(1);

Disposable subscription = Flux.interval(Duration.ofSeconds(1))
    .doOnCancel(() -> {
        System.out.println("上游收到取消，线程：" + Thread.currentThread().getName());
        cancelled.countDown();
    })
    .subscribe(value -> {
        System.out.println("收到数据：" + value + "，线程："
            + Thread.currentThread().getName());
        receivedTwo.countDown();
    });

try {
    boolean received = receivedTwo.await(5, TimeUnit.SECONDS);
    System.out.println(received);
    // 模拟用户此时点击停止
    subscription.dispose();
    assertTrue(cancelled.await(1, TimeUnit.SECONDS));
} finally {
    subscription.dispose();
}
```

这里没有真的创建按钮：`CountDownLatch` 是测试中的协调工具，让测试线程等待接收，再确认上游观察到取消。如果等待两条超时，这段测试会打印 `false`，然后仍执行取消。实际应用可以在按钮事件或生命周期结束时调用 `dispose()`，不必靠循环检查变量。

取消是向上游表达“不再需要”，不保证已经在途的信号瞬间消失，也不保证任意已经开始的阻塞工作立刻中断。

## 超时、重试，又回到了“订阅”

我们用一段两秒后发出值的 `Mono` 模拟慢操作，设置只等一秒：

```java
Mono.just("任务状态：待完成")
    .delayElement(Duration.ofSeconds(2))
    .timeout(Duration.ofSeconds(1))
    .onErrorResume(TimeoutException.class,
        error -> Mono.just("查询超时，请稍后重试"))
    .block();
```

一秒内没有等到值，`timeout` 发出超时错误、取消上游订阅，再由 `onErrorResume` 返回备用值。取消订阅不等于远端服务一定停止处理。

重试则让我想起了前面订阅两次的实验：

```java
AtomicInteger count = new AtomicInteger();

String result = Mono.fromCallable(() -> {
    int attempt = count.incrementAndGet();
    System.out.println("第 " + attempt + " 次调用" + Thread.currentThread().getName());
    if (attempt < 3) {
        throw new IllegalStateException("模拟调用失败");
    }
    return "查询成功";
}).retry(2).block();
```

这里 `retry(2)` 最多重试两次，加上第一次，总共最多三次。关键是：**重试通过重新订阅上游发生**。这个 `fromCallable` 每次订阅都会再次调用函数，所以计数递增，第三次返回成功。

如果源包装的是已经失败的同一个结果，重新订阅不一定会重新发起工作；如果操作带副作用，重复执行前也需要考虑幂等和重试条件。

## zip 看起来像 CompletableFuture，那它们都什么时候开始？

后面我们用 `Mono.zip` 组合任务和状态：

```java
Mono<String> taskMono = Mono.just("拍午餐照片");
Mono<String> statusMono = Mono.just("待完成");

String result = Mono.zip(taskMono, statusMono)
    .map(tuple -> tuple.getT1() + "：" + tuple.getT2())
    .block();
```

我看到这里就问：**这个是不是类似 CompletableFuture？**

确实可以和 `thenCombine` 比较：两个结果正常到齐，再组合。但上面都是现成的 `Mono.just`，组合不代表两个查询自动异步或并行。`zip` 的源如果空完成或出错，也有对应的终止和取消行为。

Codex 最初说 Future 通常在创建后就代表正在进行或已经完成的计算。我追问：**CompletableFuture 声明意思就是开始执行了？它不是也可以用 Lambda 延迟加载吗？**

这一问把刚才的说法纠正了：**看创建方式，不能只看类型或 Lambda。**

```java
CompletableFuture<String> pending = new CompletableFuture<>();
// 只是创建未完成的 Future，没有提交查询

CompletableFuture<String> running =
    CompletableFuture.supplyAsync(() -> queryTask());
// 调用 supplyAsync 时提交任务

Supplier<CompletableFuture<String>> factory =
    () -> CompletableFuture.supplyAsync(() -> queryTask());
// factory.get() 时才调用 supplyAsync、提交任务
```

然后我自己写了 `testCompleteFutureDelay`，用两次 `supplyAsync` 分别提供任务和状态，再通过 `thenCombine` 组合。当时我贴出来的实际输出是：

```text
ForkJoinPool.commonPool-worker-1
1main
2main
ForkJoinPool.commonPool-worker-1
拍午餐照片：待完成
```

我问：**这个是延迟吗？**

第一行已经能说明问题：第一次工作线程的打印发生在 `1main` 之前，更早于最后的 `join()`。任务在调用 `supplyAsync` 时已经提交；`join()` 等待组合结果，不负责启动任务。下一次运行谁先打印可能不同；两次都看到 `worker-1` 也不能证明两个任务同时执行。

```text
main：       提交 A ── 打印 1 ── 提交 B ── 打印 2 ── join
工作线程：       执行 A                 执行 B
                 ↑ 与 main 的打印可以交错；这里只是示意
```

无 `Async` 后缀的 `thenCombine` 回调也不能一概指定为某条固定线程；需要明确执行器时，要考虑相应的 `Async` 重载。

这个问题还可以接回 Reactor：

```java
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> queryTask());
Mono<String> existing = Mono.fromFuture(future);
```

这里 `future` 在包进 `Mono` 之前已经提交。订阅 `existing` 是观察同一个 Future 的结果，多次订阅不会让这个 Future 重新执行。若希望订阅时再创建 Future，可以传入工厂：

```java
Mono<String> deferred = Mono.fromFuture(
    () -> CompletableFuture.supplyAsync(() -> queryTask())
);
```

这里每次订阅时调用工厂，通常会得到新的任务。默认 `fromFuture` 被取消时会尝试取消 Future，但 `CompletableFuture` 的取消不保证底层计算被中断；多个订阅共享同一个 Future 时尤其需要注意取消的影响。

这次我终于把“延迟”拆成了两个问题：**什么时候提交工作？什么时候取得结果？** `join()` 和 `block()` 看起来都在等结果，但不能据此假设它们之前的工作是同一时刻启动的。

## 最后，用 Flux 模拟一次分段输出

最后 Codex 让我们把数据流拉回 Agent 的场景。没有接模型，先用固定字符串模拟分段返回：

```java
Flux.just("今天", "的任务", "是拍", "午餐照片。")
    .delayElements(Duration.ofMillis(500))
    .doOnNext(chunk -> System.out.println(
        "收到片段：" + chunk + "，线程：" + Thread.currentThread().getName()))
    .blockLast();

System.out.println("流结束");
```

它大约每隔半秒发出一段，流正常完成后 `blockLast()` 返回，再由测试线程打印“流结束”。这里仅仅是在程序里模拟分段：没有调用模型，也没有发送到浏览器。后面 Codex 提到了 SSE，但我当时选择先跳过，没有在这轮实验里验证它。

回头看，刚开始我以为 Reactor 的重点是“异步、多线程、懒加载”。做完这些实验后，我更愿意先问几个具体的问题：这行代码是在组装时执行，还是订阅后执行？值从哪里来？线程名是在什么时候记录的？出了错，是整个流结束，还是某一项自己恢复？取消之后，上游和下游分别收到什么信号？

最有帮助的不是一下记住多少操作符，而是把代码改一点、重新看一遍数据和线程的走向。尤其是那一行同时出现 `main` 和 `parallel` 的日志：如果不亲手把线程名加进去，我大概也不会追问得那么细。


## Reactor API 速查表

下面只整理这次实验中用到的主要 API。示例省略了导入和部分上下文；执行时机、线程和取消效果还要结合具体数据源判断。

### 创建、订阅与转换

| API | 作用与用法 | 留意 |
|---|---|---|
| `Mono.just(value)` | 创建最多发出一个值的 `Mono`，例如 `Mono.just("任务")`。 | Java 先计算参数；`Mono.just(query())` 中的 `query()` 在组装时执行。 |
| `Mono.fromSupplier(supplier)` | 用 Supplier 提供一个值，例如 `Mono.fromSupplier(() -> query())`。 | 此例在订阅并请求数据后调用 Supplier；多次订阅可重复执行。 |
| `Mono.fromCallable(callable)` | 用 Callable 提供一个值，例如 `Mono.fromCallable(() -> queryTask())`。 | 可接收抛出受检异常的 Callable；不会自动让阻塞操作变成非阻塞。 |
| `Flux.just(a, b, ...)` | 创建依次发出给定元素的 `Flux`。 | 给定值在调用方法前已作为参数计算；它本身不自动并行。 |
| `Flux.range(start, count)` | 依次产生指定数量的整数，例如 `Flux.range(1, 5)`。 | 第二个参数是数量，不是结束值。 |
| `subscribe(...)` | 发起订阅，使用回调接收信号。 | 常见 Lambda 重载通常请求无界数量；异步程序需管理订阅和生命周期。 |
| `block()` / `blockLast()` | 分别订阅 `Mono` / `Flux`，阻塞等待值或终止。 | 每次调用都会发起订阅；会阻塞调用线程。 |
| `map(fn)` | 对每个值做同步转换，例如 `.map(x -> x + "：待完成")`。 | 函数返回普通结果；默认不负责切换线程。 |
| `flatMap(fn)` | 将值映射为内部 Publisher，再合并它们的信号。 | `Flux.flatMap` 的内部流可以重叠、结果可能乱序；不自动创建线程。 |
| `concatMap(fn)` | 按顺序订阅并连接内部 Publisher。 | 等前一个内部流结束，再订阅下一个，通常牺牲一些重叠执行机会。 |

### 时间、线程与结果组合

| API | 作用与用法 | 留意 |
|---|---|---|
| `delayElement(duration)` | 延迟一个 `Mono` 的值信号，例如 `.delayElement(Duration.ofSeconds(1))`。 | 默认有值时后续在 `parallel` 调度器继续；空完成和错误不被延迟。它不推迟 `Mono.just` 参数的计算。 |
| `delayElements(duration)` | 间隔地延迟 `Flux` 中的元素，例如 `.delayElements(Duration.ofMillis(500))`。 | 本文用它模拟分段到达，不代表真实模型输出的分片方式。 |
| `Flux.interval(duration)` | 定期发出递增的 `Long`，例如 `Flux.interval(Duration.ofSeconds(1))`。 | 默认持续产生，需要考虑取消、下游消费速度和程序生命周期。 |
| `subscribeOn(scheduler)` | 安排订阅及相关请求的执行上下文，例如 `.subscribeOn(Schedulers.boundedElastic())`。 | 本身不发起订阅；其他具有自身调度器的操作仍可能改变后续线程。 |
| `publishOn(scheduler)` | 将上游信号交给指定调度器，影响其后的处理。 | 放置位置重要；后续其他切换线程的操作仍可能改变执行上下文。 |
| `Mono.zip(a, b)` | 等所需来源各自发出一个值后组合，例如 `Mono.zip(a, b)`。 | 不自动使阻塞操作并行；空完成和错误会影响组合及其他来源。 |

### 错误、需求与取消

| API | 作用与用法 | 留意 |
|---|---|---|
| `onErrorResume(fn)` | 出错时切换到备用 Publisher，例如 `.onErrorResume(e -> Mono.just("降级"))`。 | 原上游已终止；放在外层或某个内部流中，恢复范围不同。 |
| `timeout(duration)` | 等待超过指定时间时发出超时错误并取消上游订阅。 | 取消订阅不保证外部工作或服务端操作立刻停止。 |
| `retry(n)` | 发生错误后最多重新订阅上游 `n` 次。 | `retry(2)` 加上首次最多三次；是否重新执行工作取决于上游，注意副作用。 |
| `request(n)` | 订阅者向上游表示需要最多 `n` 个元素。 | 操作符可以调整传播的请求量；完成和错误信号与元素需求不同。 |
| `cancel()` | 订阅者要求上游停止发送、释放相关资源。 | 是提前取消，不是正常的 `onComplete`；在途信号和底层任务的停止取决于实现。 |
| `take(n)` | 只向下游发出前 `n` 个元素，例如 `.take(2)`。 | 到达数量后取消上游，并向下游正常完成。 |
| `Disposable.dispose()` | 从外部取消由 `subscribe()` 返回的订阅。 | 适合停止按钮或生命周期结束等场景；不保证中断任意外部阻塞工作。 |
| `doOnNext` / `doOnRequest` / `doOnCancel` | 在相应信号经过时执行观察回调，例如打印日志。 | 回调位置决定观察到哪一段流；这些操作不等于自动恢复或取消。 |

### 与 CompletableFuture 对照

| API | 作用与用法 | 留意 |
|---|---|---|
| `CompletableFuture.supplyAsync(...)` | 将 Supplier 对应的任务提交给异步执行器。 | 调用时提交，不等 `join()`；开始执行的具体时刻由调度决定。 |
| `thenCombine(...)` / `join()` | 前者组合两个正常完成的结果；后者等待并获取结果。 | 不带 `Async` 的回调没有固定线程；`join()` 不启动已提交任务。 |
| `Mono.fromFuture(future)` | 把已有 Future 的结果接入 `Mono`。 | 不会重新启动同一个 Future；默认取消订阅时会尝试取消 Future。 |
| `Mono.fromFuture(() -> future)` | 订阅时调用工厂获取 Future。 | 工厂若每次创建新任务，重复订阅可能重复提交；Future 的取消也不保证中断底层计算。 |

---

参考：[Reactor Core 3.8.6 Mono API](https://projectreactor.io/docs/core/3.8.6/api/reactor/core/publisher/Mono.html) · [Flux API](https://projectreactor.io/docs/core/3.8.6/api/reactor/core/publisher/Flux.html) · [Java 21 CompletableFuture API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html)
