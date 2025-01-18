package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {

    @Autowired
    private  StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        //序列化
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit){

        //先将value转换为RedisData
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <S,ID> S queryWithPassThrough(String keyPrefix, ID id, Class<S> type, Function<ID,S> dbFallback,Long time, TimeUnit timeUnit){
        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);

        if(StrUtil.isNotBlank(json)){
            S shop = JSONUtil.toBean(json, type);
            return shop;
        }
        if(json != null){
            return null;

        }

        S s = dbFallback.apply(id);
        if(s == null){
            stringRedisTemplate.opsForValue().set(keyPrefix + id,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
//        stringRedisTemplate.opsForValue().set(keyPrefix + id,JSONUtil.toJsonStr(s), time, timeUnit);
        this.set(keyPrefix + id, s, time, timeUnit);
        return s;
    }

    public<S,ID> S queryWithLogicalExpire(String keyPrefix, ID id, Class<S> type,Function<ID,S> dbFallback,Long time, TimeUnit timeUnit){
        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        if(StrUtil.isBlank(json)){
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        S s = JSONUtil.toBean(json, type);
        LocalDateTime expireDateTime = redisData.getExpireTime();

        if(expireDateTime.isAfter(LocalDateTime.now())){
            return s;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            //开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(()->{
                //缓存重建
                try {
                    //查询数据库
                    S newS = dbFallback.apply(id);
                    this.setWithLogicalExpire(keyPrefix + id,newS,time,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    freeLock(lockKey);
                }
            });
        }
        return s;
    }

    public<S,ID> S queryWithMutex(String keyPrefix, ID id, Class<S> type,Function<ID,S> dbFallback,Long time, TimeUnit timeUnit){
        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        if(json != null){
            return null;
        }
        String lockKey = LOCK_SHOP_KEY + id;

        S s = null;
        try {
            boolean isLock = tryLock(lockKey);
            if(!isLock){
                return queryWithMutex(keyPrefix,id,type,dbFallback,time,timeUnit);
            }

            s = dbFallback.apply(id);
            if(s == null){
                this.set(keyPrefix + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            this.set(keyPrefix + id, s, time, timeUnit);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            freeLock(lockKey);
        }
        return s;

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









}
