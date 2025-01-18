package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
//ServiceImpl 是mybatisplus提供的，可以帮助我们实现单表的增删改查
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号(使用正则表达式校验)
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.不符合返回错误信息
            return Result.fail("手机号格式错误");
        }

        //3 符合生成验证码
        String code = RandomUtil.randomNumbers(6);
//        //4 保存验证码到session
//        session.setAttribute("code",code);

        // 4 保存验证码到redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY +phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5发送验证码
        log.debug("发送短信验证码成功: "+ code);
        //返回 ok
        return Result.ok();
    }

    //登录功能
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号和验证码
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.不符合返回错误信息
            return Result.fail("手机号格式错误");
        }

        //2.验证码不一致报错(session中取出验证码和表单的验证码进行比较)
        String code = loginForm.getCode();

//        //从session中获取
//        Object cachecode = session.getAttribute("code");

        // 从redis中获取
        String cachecode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);

        if(cachecode == null || !cachecode.equals(code)){
            return Result.fail("验证码错误");
        }

        //3 一致，从数据库中查询用户
        User user = query().eq("phone", phone).one();
        //4 判断用户是否存在
        if(user == null){
            //5 不存在，创建新用户并保存
            user = crerateUserWithPhone(phone);

        }

//        //6 保存用户信息到session中
        //将复制后的用户对象保存到session中
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        // 7 保存用户信息到redis
        // 7.1 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);

        // 7.2 将User对象转为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO , new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        // 7.3 存储
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+token,userMap);
        //7.4 设置有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //8 返回token到前端
        return Result.ok(token);
    }

    private User crerateUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("user_"+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
