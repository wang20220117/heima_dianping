package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    ShopServiceImpl shopService;

    @Test
    void cacheExpiretTest() throws InterruptedException {
        shopService.saveShop2Redis(1L, 20L);

    }

    @Autowired
    private RedisIdWorker redisIdWorker;

    private ExecutorService threadPool = Executors.newFixedThreadPool(500);
    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(100);
        Runnable task = ()->{
            for(int i = 0;i < 100;i++){
                long id = redisIdWorker.nextId("order");
                System.out.println("id"+id);
            }
            countDownLatch.countDown();
        };
        for(int j = 0;j < 100;j++){
            threadPool.submit(task);
        }
        countDownLatch.await();


    }

}
