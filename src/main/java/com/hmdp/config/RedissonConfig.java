package com.hmdp.config;


import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class RedissonConfig {

    @Bean
    @Primary
    public RedissonClient redissonCLient(){
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.169.129:6379").setPassword("123456");
        return Redisson.create(config);

    }
    @Bean
    public RedissonClient redissonCLient2(){
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.169.129:6380").setPassword(null);
        return Redisson.create(config);

    }
    @Bean
    public RedissonClient redissonCLient3(){
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.169.129:6381").setPassword(null);
        return Redisson.create(config);

    }
}
