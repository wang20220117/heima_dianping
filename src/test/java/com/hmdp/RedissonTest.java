package com.hmdp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@SpringBootTest
public class RedissonTest {
    //@Resource名称优先，@Autowired类型优先
    @Autowired
    @Qualifier("redissonCLient")
    private RedissonClient redissonClient;
    @Autowired
    @Qualifier("redissonCLient2")
    private RedissonClient redissonClient2;
    @Autowired
    @Qualifier("redissonCLient3")
    private RedissonClient redissonClient3;

    private RLock lock ;

    @BeforeEach
    void setUp(){
        RLock lock1 = redissonClient.getLock("lock:order");
        RLock lock2 = redissonClient2.getLock("lock:order");
        RLock lock3 = redissonClient3.getLock("lock:order");

        lock = redissonClient.getMultiLock(lock1,lock2,lock3);  //redissonClient、redissonClient2和redissonClient3 用哪个都一样
    }

    @Test
    void method1() throws InterruptedException {
        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
        if(!isLock){
            System.out.println("获取锁失败,1");
            return;
        }
        try{
            System.out.println("获取锁成功,1");
            method2();
        }finally {
            System.out.println("释放锁,1");
            lock.unlock();
        }

    }
    void method2(){
        boolean isLock = lock.tryLock();
        if(!isLock){
            System.out.println("获取锁失败,2");
            return;
        }
        try{
            System.out.println("获取锁成功,2");
        }finally {
            System.out.println("释放锁,2");
            lock.unlock();
        }
    }
}
