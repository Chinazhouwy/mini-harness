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

@org.junit.jupiter.api.Tag("manual")
public class ReactorStudyTest {

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

    @Test
    public void testTwice() throws InterruptedException {
        Mono<Long> time = Mono.fromSupplier(System::currentTimeMillis);

        System.out.println(time.block());
        Thread.sleep(1000);
        System.out.println(time.block());
    }


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

    @Test
    void fluxDemo() {
        Flux.just("拍午餐照片", "整理学习笔记", "复习 Java")
                .map(task -> "待完成：" + task)
                .subscribe(System.out::println);
    }


    @Test
    void asyncFlatMapDemo() {
        Flux.just("拍午餐照片", "整理学习笔记")
                .flatMap(task -> queryStatusAsync(task))
                .doOnNext(result -> System.out.println(result + "，线程：" + Thread.currentThread().getName()))
                .blockLast();
    }

    @Test
    void syncFlatMapDemo() {
        Flux.just("拍午餐照片", "整理学习笔记")
                .flatMap(task -> queryStatus(task))
                .doOnNext(result -> System.out.println(result + "，线程：" + Thread.currentThread().getName()))
                .blockLast();
    }


    @Test
    void asyncConcatMapDemo() {
        Flux.just("拍午餐照片", "整理学习笔记")
                .concatMap(task -> queryStatusAsync(task))
                .doOnNext(result ->
                        System.out.println(result + "，线程：" +
                                Thread.currentThread().getName()))
                .blockLast();
    }

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
        // 正常收到完成信号后不需要再 cancel()，因为流已经结束。
        // `cancel()` 用在另一种情况：生产者可能还有数据，但消费者决定提前不接收了。
        subscriber.cancel();
    }

    @Test
    void cancelDemo() {
        Flux.range(1, 5)
                .doOnCancel(() -> System.out.println("上游收到取消"))
                .take(2)
                .doOnNext(value -> System.out.println("收到：" + value))
                .doOnComplete(() -> System.out.println("下游完成"))
                .subscribe();
    }


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
                .retry(2)
                .block();

        System.out.println("最终结果：" + result);
    }


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

    private Mono<String> queryStatusAsync(String task) {
        int seconds = task.equals("拍午餐照片") ? 2 : 1;
        return Mono.just(task + "：待完成"+Thread.currentThread().getName()).delayElement(Duration.ofSeconds(seconds));
    }

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
