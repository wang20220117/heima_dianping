package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.hmdp.utils.CacheClient;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;
    //查询商铺信息
    @Override
    public Result queryById(Long id) {
        //缓存穿透
//        Shop shop = queryWithPassThrough(id);
//        Shop shop = queryWithMutex(id);
        Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY,id,Shop.class,this::getById,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }

    // 缓存穿透封装
    public Shop queryWithPassThrough(Long id) {
        //1.从redis查商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);

        //2.判断是否存在
        //3.存在直接返回
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        //判断是否为空值
        if(shopJson != null){
            return null;

        }
        //4.不存在，根据id查询数据库
        Shop shop = getById(id);
        //5.数据库不存在返回错误
        if(shop == null){
            stringRedisTemplate.opsForValue().set("cache:shop:" + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在,写入redis
        stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回
        return shop;

    }

    //缓存击穿互斥锁封装
    public Shop queryWithMutex(Long id) {
        //1.从redis查商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);

        //2.判断是否存在
        //3.存在直接返回
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        //判断是否为空值
        if(shopJson != null){
            return null;

        }

        //实现缓存重建
        //4.1判断是否获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2没有获取休眠
            if(!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //4.3获取了则查询数据库
            //4.根据id查询数据库
            shop = getById(id);
//            Thread.sleep(200);
            //5.数据库不存在返回错误
            if(shop == null){
                stringRedisTemplate.opsForValue().set("cache:shop:" + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在,写入redis
            stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7释放互斥锁
            freeLock(lockKey);
        }
        //8.返回
        return shop;
    }

    //获取互斥锁
    private boolean tryLock(String key){
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);

    }

    //释放互斥锁
    private void freeLock(String key){
        stringRedisTemplate.delete(key);
    }

    //封装逻辑过期时间
    public void saveShop2Redis(Long id,Long expireSeconds) throws  InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);

        Thread.sleep(200);

        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue().set("cache:shop:" + id,JSONUtil.toJsonStr(redisData));

    }


    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 逻辑过期封装
    public Shop queryWithLogicalExpire(Long id) {
        //1.从redis查商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);

        //3.缓存未命中
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //4命中 判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(shopJson, Shop.class);
        LocalDateTime expireDateTime = redisData.getExpireTime();

        //未过期,返回店铺信息
        if(expireDateTime.isAfter(LocalDateTime.now())){
            return shop;
        }

        //已过期，需要缓存重建
        String lockKey = "lock:shop:" + id;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            //开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(()->{
                //缓存重建
                try {
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    freeLock(lockKey);
                }
            });

        }

        return shop;

    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete("cache:shop:" + id);
        return Result.ok();
    }
}
