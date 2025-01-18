package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryType() {
        String shoptypeJson = stringRedisTemplate.opsForValue().get("cache:shoptype");

        if (StrUtil.isNotBlank(shoptypeJson)) {
            List<ShopType> typeList = JSONUtil.toList(shoptypeJson,ShopType.class);
            return Result.ok(typeList);
        }
        //不存在查数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();

        if(typeList == null){
            return Result.fail("商铺类型列表不存在");
        }
        stringRedisTemplate.opsForValue().set("cache:shoptype",JSONUtil.toJsonStr(typeList));

        return Result.ok(typeList);
    }
}
