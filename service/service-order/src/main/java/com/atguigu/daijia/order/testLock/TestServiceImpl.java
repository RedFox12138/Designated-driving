package com.atguigu.daijia.order.testLock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TestServiceImpl implements TestService{
    @Autowired
    private StringRedisTemplate redisTemplate;

    public void testLock() {
        //从redis里面获取数据
        String value = redisTemplate.opsForValue().get("num");
//        if (!StringUtils.hasText(value)) {
//            return;
//        }
//        //把redis的数据+1
//        int num = Integer.parseInt(value);
//        redisTemplate.opsForValue().set("num", String.valueOf(num + 1));
    }
}
