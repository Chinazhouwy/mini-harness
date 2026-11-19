package com.chinazhouwy.miniharness.agentscope.study;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 响应式执行语义的手动练习。Mono 表示零或一个结果、Flux 表示零到多个结果；是否异步取决于源与调度器，不能仅凭返回类型判断。本类不调用模型，manual 标签默认排除。 */
@org.junit.jupiter.api.Tag("manual")
public class ReactorStudyTest {

    /**
     * 区分组装响应式流程和执行流程。
     * <p>关键点：fromSupplier/map 在没有订阅时不执行；此方法故意不调用 block 或 subscribe。
     * <p>观察：只出现开始与组装结束日志，不应打印查询和结果加工。
     */
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

        // 第一遍先注释，第二遍再打开
        // String value = result.block();
        // System.out.println("5. 收到结果：" + value);
    }


    /**
     * 用 block 触发一次冷流执行。
     * <p>关键点：block 发起订阅并等待终止；未指定调度器时，本例同步工作通常留在订阅线程。
     * <p>观察：查询和 map 输出在 block 期间出现，再返回最终值。
     */
    @Test
    void creationAndExecution() {
        System.out.println("1. 开始组装，线程：" + thread());

        Mono<String> result = Mono.fromSupplier(() -> {
            System.out.println("2. 执行查询，线程：" + thread());
            return "任务：拍午餐照片";
        }).map(value -> {
            System.out.println("3. 处理结果，线程：" + thread());
            return value + "，状态：待完成";
        });

        System.out.println("4. 组装结束，线程：" + thread());

        //第一遍先注释，第二遍再打开
         String value = result.block();
         System.out.println("5. 收到结果：" + value);
    }


    /**
     * 观察同一个冷 Mono 被订阅两次。
     * <p>关键点：fromSupplier 不自动缓存；两次 block 是两个独立订阅，源函数各执行一次。
     * <p>观察：查询与加工日志出现两遍；若要复用结果需另行选择缓存策略。
     */
    @Test
    void creationAndExecutionTwice() {
        System.out.println("1. 开始组装，线程：" + thread());

        Mono<String> result = Mono.fromSupplier(() -> {
            System.out.println("2. 执行查询，线程：" + thread());
            return "任务：拍午餐照片";
        }).map(value -> {
            System.out.println("3. 处理结果，线程：" + thread());
            return value + "，状态：待完成";
        });

        System.out.println("4. 组装结束，线程：" + thread());

        //第一遍先注释，第二遍再打开
        String value = result.block();
        System.out.println("5. 收到结果：" + value);

        String value2 = result.block();
        System.out.println("6. 收到结果2：" + value2);
    }

    /**
     * 用两次时间值说明冷流会重新求值。
     * <p>关键点：Supplier 保存的是取时间的动作；第二次 block 时才再次调用 currentTimeMillis。
     * <p>观察：两次输出相差约一秒，实际差值包含调度和执行开销。
     */
    @Test
    public void testTwice() throws InterruptedException {
        Mono<Long> time = Mono.fromSupplier(System::currentTimeMillis);

        System.out.println(time.block());
        Thread.sleep(1000);
        System.out.println(time.block());
    }


    /**
     * 比较装配时求值与订阅时求值。
     * <p>关键点：Java 会先求出 Mono.just 的实参；fromSupplier 将 query 延迟到订阅时。
     * <p>观察：just 的查询出现在组装阶段，supplier 的查询出现在订阅阶段。
     */
    @Test
    void justAndSupplier() {
        System.out.println("开始组装");

        Mono<String> just = Mono.just(query("just"));

        Mono<String> supplier =
                Mono.fromSupplier(() -> query("supplier"));

        System.out.println("组装结束");

        System.out.println("订阅 just");
        just.block();

        System.out.println("订阅 supplier");
        supplier.block();
    }


    /**
     * 观察同步的一对一值转换。
     * <p>关键点：map 返回普通值，subscribe 消费结果；本例没有切线程操作。
     * <p>观察：先打印 map 收到的任务，再打印加工后的最终值。
     */
    @Test
    void mapDemo() {
        Mono.just("拍午餐照片")
                .map(task -> {
                    System.out.println("map 收到：" + task);
                    return task + "，状态：待完成";
                })
                .subscribe(result -> {
                    System.out.println("最终收到：" + result);
                });
    }


    /**
     * 把返回 Mono 的动作接入处理链。
     * <p>关键点：flatMap 订阅并展开内部 Publisher；它本身不等于创建线程或线程池。
     * <p>观察：内部 fromSupplier 执行后，下游拿到状态字符串而非 Mono 对象。
     */
    @Test
    void flatMapDemo() {
        Mono.just("拍午餐照片")
                .flatMap(task -> {
                    return Mono.fromSupplier(() -> {
                        System.out.println("查询任务状态：" + task);
                        return "待完成";
                    });
                })
                .subscribe(status -> {
                    System.out.println("最终状态：" + status);
                });
    }

    /**
     * 观察 Flux 的多元素处理。
     * <p>关键点：Flux.just 依次发出三个任务，map 对每个元素转换，subscribe 消费每个结果。
     * <p>观察：输出三条状态；Flux 表示多值，并不自动表示并行。
     */
    @Test
    void fluxDemo() {
        Flux.just("拍午餐照片", "整理学习笔记", "复习 Java")
                .map(task -> "待完成：" + task)
                .subscribe(System.out::println);
    }


    /**
     * 观察多个异步内部流交错完成。
     * <p>关键点：delayElement 产生不同完成时间；flatMap 可同时订阅内部流，并按到达时间合并。
     * <p>观察：一秒任务通常先输出，注意区分字符串中预先拼接的线程名与下游实际线程日志。
     */
    @Test
    void asyncFlatMapDemo() {
        Flux.just("拍午餐照片", "整理学习笔记")
                .flatMap(task -> queryStatusAsync(task))
                .doOnNext(result -> System.out.println(result + "，线程：" + Thread.currentThread().getName()))
                .blockLast();
    }

    /**
     * 对照 flatMap 内部没有异步边界时的行为。
     * <p>关键点：queryStatus 返回立即可用的 Mono.just；操作符名称不决定并发。
     * <p>观察：输出通常保持本例输入顺序，不能据此推断所有 flatMap 都串行。
     */
    @Test
    void syncFlatMapDemo() {
        Flux.just("拍午餐照片", "整理学习笔记")
                .flatMap(task -> queryStatus(task))
                .doOnNext(result -> System.out.println(result + "，线程：" + Thread.currentThread().getName()))
                .blockLast();
    }


    /**
     * 观察按顺序订阅异步任务。
     * <p>关键点：concatMap 等前一个内部流结束后再订阅下一个，代价是无法重叠等待。
     * <p>观察：输出按输入顺序，耗时包含两次延迟之和。
     */
    @Test
    void asyncConcatMapDemo() {
        Flux.just("拍午餐照片", "整理学习笔记")
                .concatMap(task -> queryStatusAsync(task))
                .doOnNext(result ->
                        System.out.println(result + "，线程：" +
                                Thread.currentThread().getName()))
                .blockLast();
    }

    /**
     * 把源端阻塞查询安排到指定调度器。
     * <p>关键点：subscribeOn 影响订阅和源执行；boundedElastic 适合本例模拟的阻塞任务。
     * <p>观察：queryTask 打印工作线程；block 返回后测试线程继续执行。
     */
    @Test
    void subscribeOnDemo() {
        System.out.println("开始，线程：" +
                Thread.currentThread().getName());

        String result = Mono.fromCallable(() -> queryTask())
                .subscribeOn(Schedulers.boundedElastic())
                .block();

        System.out.println("收到：" + result +
                "，线程：" + Thread.currentThread().getName());
    }


    /**
     * 观察处理链中途切换执行线程。
     * <p>关键点：publishOn 影响其后的下游操作；之前的 map 保持原执行位置。
     * <p>观察：比较两个 map 的线程名，以及 block 返回后调用方的线程。
     */
    @Test
    void publishOnDemo() {
        var result = Flux.just("拍午餐照片")
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
        System.out.println("收到：" + result +
                "，线程：" + Thread.currentThread().getName());
    }


    /**
     * 观察未恢复的异常如何终止流。
     * <p>关键点：map 抛出异常后，上游序列不会继续处理剩余元素；blockLast 把错误传播给调用方。
     * <p>观察：打印第一项后在第二项失败，assertThrows 接住原业务异常。
     */
    @Test
    void errorDemo() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                Flux.just("拍午餐照片", "整理学习笔记", "复习 Java")
                .map(task -> {
                    if (task.equals("整理学习笔记")) {
                        throw new IllegalStateException("查询任务失败");
                    }
                    return task + "：待完成";
                })
                .doOnNext(System.out::println)
                .blockLast());

        System.out.println(exception.getMessage());
    }

    /**
     * 用替代 Publisher 恢复整个流的错误。
     * <p>关键点：onErrorResume 接手的是出错后的终止信号，不是继续原序列的下一项。
     * <p>观察：doOnNext 在恢复之前，因此替代值不会经过前面的这一个观察器。
     */
    @Test
    void onErrorResumeDemo() {
        Flux.just("拍午餐照片", "整理学习笔记", "复习 Java")
                .map(task -> {
                    if (task.equals("整理学习笔记")) {
                        throw new IllegalStateException("查询任务失败");
                    }
                    return task + "：待完成";
                })
//                .onErrorResume(error -> {
//                    System.out.println("发生错误：" + error.getMessage());
//                    return Mono.just("暂时无法查询任务");
//                })
                .doOnNext(System.out::println)
                .onErrorResume(error -> {
                    System.out.println("发生错误：" + error.getMessage()+"thread:"+thread());
                    return Mono.just("暂时无法查询任务");
                })
                .blockLast();
    }

    /**
     * 观察错误恢复位置对下游的影响。
     * <p>关键点：本例把 doOnNext 放在 onErrorResume 后面，因此它也能看到恢复流发出的值。
     * <p>观察：对照上一例的输出位置；原序列的第三项仍不会自动继续。
     */
    @Test
    void onErrorResumeDemo2() {
        Flux.just("拍午餐照片", "整理学习笔记", "复习 Java")
                .map(task -> {
                    if (task.equals("整理学习笔记")) {
                        throw new IllegalStateException("查询任务失败");
                    }
                    return task + "：待完成";
                })
                .onErrorResume(error -> {
                    System.out.println("发生错误：" + error.getMessage()+"thread:"+thread());
                    return Mono.just("暂时无法查询任务");
                })
                .doOnNext(System.out::println)
//                .onErrorResume(error -> {
//                    System.out.println("发生错误：" + error.getMessage());
//                    return Mono.just("暂时无法查询任务");
//                })
                .blockLast();
    }

    /**
     * 让单个任务失败不终止其他任务。
     * <p>关键点：把 onErrorResume 放在每个内部任务流中，错误先在局部转换为该任务的替代状态。
     * <p>观察：三个任务都有结果，其中一项是失败说明；没有把整条外层流替换掉。
     */
    @Test
    void handleEachTaskError() {
        Flux.just("拍午餐照片", "整理学习笔记", "复习 Java")
                .flatMap(task ->
                        queryTaskStatus(task)
                                .onErrorResume(error -> {
                                    System.out.println(task + " 查询失败");
                                    return Mono.just(task + "：暂时无法查询");
                        })
                )
                .doOnNext(System.out::println)
                .blockLast();
    }

    /**
     * 观察消费者主动声明需求量。
     * <p>关键点：BaseSubscriber 在订阅时暂不 request；随后分批请求 2 和 1，最后取消。
     * <p>观察：仅接收三项后结束观察；没有请求全部五项，所以不会正常收到 onComplete。
     */
    @Test
    void backpressureDemo() {
        BaseSubscriber<Integer> subscriber = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                System.out.println("订阅成功，暂时不请求数据");
            }
            @Override
            protected void hookOnNext(Integer value) {
                System.out.println("收到：" + value);
            }

            @Override
            protected void hookOnComplete() {
                System.out.println("上游数据全部发完，流结束");
            }
        };
        Flux.range(1, 5)
                .doOnRequest(n -> System.out.println("上游收到请求：" + n))
                .subscribe(subscriber);
        System.out.println("第一次请求 2 个");
        subscriber.request(2);
        System.out.println("第二次请求 1 个");
        subscriber.request(1);
        // 本例只 request 了三项，源还有两项未发出，因此这里主动取消，并不会正常收到 onComplete。
        // 如果已经正常完成，则无需额外 cancel；二者不是同一种终止信号。
        subscriber.cancel();
    }

    /**
     * 观察 take 如何完成下游并取消上游。
     * <p>关键点：take(2) 获得两项后向上游发送取消，而下游看到正常完成。
     * <p>观察：同时看到上游取消与下游完成，两种信号作用方向不同。
     */
    @Test
    void cancelDemo() {
        Flux.range(1, 5)
                .doOnCancel(() -> System.out.println("上游收到取消"))
                .take(2)
                .doOnNext(value -> System.out.println("收到：" + value))
                .doOnComplete(() -> System.out.println("下游完成"))
                .subscribe();
    }


    /**
     * 模拟用户主动停止正在运行的流。
     * <p>关键点：subscribe 返回 Disposable；dispose 发送取消，CountDownLatch 只用于让测试观察到事件。
     * <p>观察：收到两项后取消，检查 doOnCancel；finally 负责失败路径也释放订阅。
     */
    @Test
    void cancelFromOutside() throws InterruptedException {
        // 用来通知测试线程：已经收到两条数据
        CountDownLatch receivedTwo = new CountDownLatch(2);
        // 用来确认上游收到了取消信号
        CountDownLatch cancelled = new CountDownLatch(1);
        Disposable subscription = Flux.interval(Duration.ofSeconds(1))
                .doOnCancel(() -> {
                    System.out.println("上游收到取消，线程：" + Thread.currentThread().getName());
                    cancelled.countDown();
                })
                .subscribe(value -> {
                    System.out.println("收到数据：" + value + "，线程：" + Thread.currentThread().getName());
                    receivedTwo.countDown();
                });
        try {
            System.out.println("main：等待收到两条数据");
            boolean received = receivedTwo.await(5, TimeUnit.SECONDS);
            System.out.println(received);
            // 模拟用户此时点击了“停止”
            System.out.println("main：模拟用户点击停止");
            subscription.dispose();
            assertTrue(cancelled.await(1, TimeUnit.SECONDS), "上游没有收到取消信号");
        } finally {
            // 如果测试中途失败，也释放订阅
            subscription.dispose();
        }
    }

    /**
     * 观察等待信号超时后的替代结果。
     * <p>关键点：delayElement 两秒，timeout 一秒，因此超时算子取消当前等待并发出 TimeoutException。
     * <p>观察：onErrorResume 将该异常转换成提示语；取消对底层工作的效果仍取决于源实现。
     */
    @Test
    void timeoutDemo() {
        String result = Mono.just("任务状态：待完成")
                .delayElement(Duration.ofSeconds(2))
                .timeout(Duration.ofSeconds(1))
                .onErrorResume(TimeoutException.class, error ->
                        Mono.just("查询超时，请稍后重试"))
                .block();

        System.out.println(result);
    }


    /**
     * 观察 retry 通过重新订阅重复执行源。
     * <p>关键点：retry(2) 表示首次失败后最多再订阅两次，计数器在每次源函数执行时递增。
     * <p>观察：本例第三次成功；若源函数有副作用，重订阅也会重复副作用。
     */
    @Test
    void retryDemo() {
        AtomicInteger count = new AtomicInteger();

        String result = Mono.fromCallable(() -> {
                    int attempt = count.incrementAndGet();
                    System.out.println("第 " + attempt + " 次调用"+Thread.currentThread().getName());

                    if (attempt < 3) {
                        throw new IllegalStateException("模拟调用失败");
                    }
                    return "查询成功";
                })
                // retry 会重新订阅源，不是从出错行继续执行；对真实写操作重试前需要考虑重复副作用。
                .retry(2)
                .block();

        System.out.println("最终结果：" + result);
    }


    /**
     * 合并多个 Mono 的值。
     * <p>关键点：zip 等各源提供所需值后产生 Tuple；源是否并行由源本身的调度决定。
     * <p>观察：从 getT1/getT2 取值拼结果；此处两个源已就绪，没有演示耗时并行。
     */
    @Test
    void zipDemo() {
        Mono<String> taskMono = Mono.just("拍午餐照片");
        Mono<String> statusMono = Mono.just("待完成");

        String result = Mono.zip(taskMono, statusMono)
                .map(tuple -> {
                    String task = tuple.getT1();
                    String status = tuple.getT2();
                    System.out.println(thread());
                    return task + "：" + status;
                })
                .block();

        System.out.println(result);
    }

    /**
     * 用 Future 对照多个结果的组合。
     * <p>关键点：completedFuture 已有结果；thenCombine 组合两边，不因此自动分配新线程。
     * <p>观察：join 读取组合值，线程名用于观察组合动作的实际执行位置。
     */
    @Test
    void testCompleteFuture(){
        CompletableFuture<String> taskFuture =
                CompletableFuture.completedFuture("拍午餐照片");
        System.out.println("1"+thread());
        CompletableFuture<String> statusFuture =
                CompletableFuture.completedFuture("待完成");
        System.out.println("2"+thread());
        CompletableFuture<String> resultFuture =
                taskFuture.thenCombine(
                        statusFuture,
                        (task, status) -> task + "：" + status
                );

        System.out.println(resultFuture.join());
    }

    /**
     * 观察 supplyAsync 与组合回调的执行。
     * <p>关键点：未指定 Executor 的 supplyAsync 使用默认异步执行设施；thenCombine 等两边完成再组合。
     * <p>观察：比较任务线程与测试线程，join 等结果；回调具体由哪个线程执行不应写死。
     */
    @Test
    void testCompleteFutureDelay(){
        CompletableFuture<String> taskFuture =
                CompletableFuture.supplyAsync(() -> {
                    System.out.println(thread());
                    return "拍午餐照片";
                });
        System.out.println("1"+thread());
        CompletableFuture<String> statusFuture =
                CompletableFuture.supplyAsync(() -> {
                    System.out.println(thread());
                    return "待完成";
                });

        System.out.println("2"+thread());

        CompletableFuture<String> resultFuture =
                taskFuture.thenCombine(
                        statusFuture,
                        (task, status) -> task + "：" + status
                );

        System.out.println(resultFuture.join());
    }


    /**
     * 用定时发出的字符串模拟文本流。
     * <p>关键点：delayElements 控制每个元素的发出节奏，blockLast 防止测试提前退出。
     * <p>观察：逐片段打印，最后完成；本方法没有调用模型或解析 SSE。
     */
    @Test
    void streamingDemo() {
        Flux.just("今天", "的任务", "是拍", "午餐照片。")
                .delayElements(Duration.ofMillis(500))
                .doOnNext(chunk -> System.out.println(
                        "收到片段：" + chunk +
                                "，线程：" + Thread.currentThread().getName()))
                .blockLast();

        System.out.println("流结束");
    }



    // 把可能失败的工作包装为冷 Mono，只有每个内部流被订阅时才执行并产生错误信号。
    private Mono<String> queryTaskStatus(String task) {
        return Mono.fromCallable(() -> {
            if (task.equals("整理学习笔记")) {
                throw new IllegalStateException("查询失败");
            }
            return task + "：待完成";
        });
    }


    private String queryTask() throws InterruptedException {
        System.out.println("执行查询，线程：" + Thread.currentThread().getName());

        Thread.sleep(2000); // 模拟阻塞查询

        return "拍午餐照片：待完成";
    }

    // 这是定时延迟发出元素的演示，不是外部异步 I/O；字符串里的线程名在 delayElement 之前已经求值。
    private Mono<String> queryStatusAsync(String task) {
        int seconds = task.equals("拍午餐照片") ? 2 : 1;
        return Mono.just(task + "：待完成"+Thread.currentThread().getName()).delayElement(Duration.ofSeconds(seconds));
    }

    // 立即返回 Mono.just，没有异步边界；用于和带 delayElement 的版本比较 flatMap 行为。
    private Mono<String> queryStatus(String task) {
        return Mono.just(task + "：待完成");
    }

    private String query(String name) {
        System.out.println("执行查询：" + name);
        return "查询结果";
    }
    private static String thread() {
        return Thread.currentThread().getName();
    }

}
