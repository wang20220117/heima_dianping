package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //session实现登录的拦截
//        HttpSession session = request.getSession();
//
//        Object user = session.getAttribute("user");
//
//        if (user == null) {
//            //不存在拦截
//            response.setStatus(401);  //未授权
//            return false;
//        }
//
//        //存在则把用户保存在ThreadLocal
//        UserHolder.saveUser((UserDTO) user);

//        // 获取请求头中的token
//        String token = request.getHeader("authorization");
//
//        if (StrUtil.isBlank(token)) {
//            response.setStatus(401);
//            return false;
//        }
//
//        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);
//        if(userMap.isEmpty()){
//            response.setStatus(401);
//            return false;
//        }
//
//        // Hash数据转为UserDTO对象
//        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
//
//        //把用户保存在ThreadLocal
//        UserHolder.saveUser(userDTO);
        //刷新有效期
//        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        /*以上代码注释掉的原因，是又增加了一个拦截器RefreshTokenIntercepton，上面的功能放在这个拦截器去做。
        * */
        //判断ThreadLocal中是否有用户
        if(UserHolder.getUser()==null){
            //拦截
            response.setStatus(401);
            return false;
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {

        UserHolder.removeUser();

    }
}
