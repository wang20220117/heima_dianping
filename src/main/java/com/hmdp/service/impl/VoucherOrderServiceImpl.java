package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


//ServiceImpl 是 MyBatis-Plus 提供的一个通用实现类，封装了对数据库操作的常见逻辑（如增删改查）。
/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);

    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //代理对象
    private IVoucherOrderService proxy;

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VocherOrderHandler());
    }

    //线程任务
    private class VocherOrderHandler implements Runnable{
        @Override
        public void run(){

            try {
                //1.获取队列中的订单信息
                VoucherOrder voucherOrder = orderTasks.take();
                //2.创建订单
                handleVoucherOrder(voucherOrder);

            } catch (InterruptedException e) {
                log.error("处理订单异常",e);
            }


        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        long userID = voucherOrder.getUserId();
        //使用redission锁
        RLock lock = redissonClient.getLock("lock:order:" + userID);

        //获取锁
        boolean tryLock = lock.tryLock();
        //判断是否获取锁成功
        if(!tryLock){ //获取锁失败
            log.error("不允许重复下单");
            return;
        }

        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        //2. 判断结果是否为0
        int r = result.intValue();
        if(r != 0) {
            //2.1.不为0，没有购买资格
            return Result.fail(r==1 ? "库存不足" : "不能重复下单");
        }

        //2.2.为0，有购买资格，把下单的信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        //TODO 保存阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        //代金券id
        voucherOrder.setVoucherId(voucherId);
        //放到阻塞队列
        //创建阻塞队列
        orderTasks.add(voucherOrder);

        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //3.返回订单id

        return Result.ok(orderId);
        }


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5 一人一单
        Long userID = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //使用悲观锁
        //查询订单
        int count = query().eq("user_id", userID).eq("voucher_id", voucherId).count();
        if(count > 0){
            // 用户已经购买过了
            log.error("用户已经购买过一次");
        }

        //6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")   // set stock = stock -1
//                .eq("voucher_id",voucherId).eq("stock",voucher.getStock()) //where id = ? and stock = ? 失败率太高
                .eq("voucher_id", voucherId).gt("stock",0) //where id = ? and stock > 0
                .update();
        if(!success){
            //扣减失败
            log.error("库存不足");
        }
        //7.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
        save(voucherOrder);

    }
}

////秒杀优化前的代码
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            //未开始
//            return Result.fail("秒杀尚未开始");
//        }
//        //3.判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            //未开始
//            return Result.fail("秒杀已经结束");
//        }
//        //4.判断库存是否充足
//        if(voucher.getStock() < 1){
//            return Result.fail("库存不足");
//        }
//        //用户id
//        Long userID = UserHolder.getUser().getId();
////        synchronized (userID.toString().intern()) {
////            //拿到事务代理的对象
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId, userID);
////        }
//        //实验redis分布式锁
//        //创建锁对象(实现1)
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userID, stringRedisTemplate);
//
//        //使用redission锁
//        RLock lock = redissonClient.getLock("lock:order:" + userID);
//
//        //获取锁
////        boolean tryLock = lock.tryLock(120);
//        //redission锁的tryLock
//        boolean tryLock = lock.tryLock();
//        //判断是否获取锁成功
//        if(!tryLock){ //获取锁失败
//            return Result.fail("不允许重复下单");
//        }
//
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId, userID);
//        } finally {
//            lock.unlock();
//        }
//
//    }
//    @Transactional
//    public Result createVoucherOrder(Long voucherId,Long userID) {
//        //5 一人一单
//        //使用悲观锁
//        //查询订单
//        int count = query().eq("user_id", userID).eq("voucher_id", voucherId).count();
//        if(count > 0){
//            // 用户已经购买过了
//            return Result.fail("用户已经购买过一次");
//        }
//
//        //6.扣减库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock - 1")   // set stock = stock -1
////                .eq("voucher_id",voucherId).eq("stock",voucher.getStock()) //where id = ? and stock = ? 失败率太高
//                .eq("voucher_id", voucherId).gt("stock",0) //where id = ? and stock > 0
//                .update();
//        if(!success){
//            //扣减失败
//            return Result.fail("库存不足");
//        }
//        //7.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//
//        //订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//
//
//        voucherOrder.setUserId(userID);
//
//        //代金券id
//        voucherOrder.setVoucherId(voucherId);
//        save(voucherOrder);
//
//        return Result.ok(orderId);
//    }
//}
