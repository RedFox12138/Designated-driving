package com.atguigu.daijia.customer.service.impl;

import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.Result;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.customer.client.CustomerInfoFeignClient;
import com.atguigu.daijia.customer.service.CustomerService;
import com.atguigu.daijia.model.vo.customer.CustomerLoginVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class CustomerServiceImpl implements CustomerService {
    //注入远程调用的接口
    @Autowired
    private CustomerInfoFeignClient client;

    @Autowired
    private RedisTemplate redisTemplate;


    @Autowired
    private CustomerInfoFeignClient customerInfoFeignClient;

    @Override
    public String login(String code) {
        //1 拿着code进行远程调用，最终返回用户id
        Result<Long> loginResult = client.login(code);

        //2 判断如果返回失败了，返回错误的提示
        Integer codeResult = loginResult.getCode();
        if(codeResult!=200)
        {
            throw new GuiguException(ResultCodeEnum.DATA_ERROR);
        }
        //3 获取远程调用返回的用户id
        Long customerId = loginResult.getData();

        //4 判断返回的用户id是否为空，为空则也返回错误的提示
        if(customerId==null)
        {
            throw new GuiguException(ResultCodeEnum.DATA_ERROR);
        }
        //5 生成一个token的字符串
        //把原始UUID里面的所有横杠去掉
        String token = UUID.randomUUID().toString().replace("-","");
        //6 把用户id放到Redis进行存储，设置过期时间
        //key是token，value是id
        redisTemplate.opsForValue().set(RedisConstant.USER_LOGIN_KEY_PREFIX+token, customerId.toString(), RedisConstant.USER_LOGIN_KEY_TIMEOUT, TimeUnit.SECONDS);

        //7 返回token字符串

        return token;
    }

    @Override
    public CustomerLoginVo getCustomerLoginInfo(String token) {
        //2 根据token去查询redis
        String customerId = (String) redisTemplate.opsForValue().get(RedisConstant.USER_LOGIN_KEY_PREFIX + token);
        //3 查询token在redis里面对应得用户id
        if(!(StringUtils.hasText(customerId)))
        {
            throw new GuiguException(ResultCodeEnum.DATA_ERROR);
        }

        //4 根据用户id进行远程调用，得到用户信息
        Result<CustomerLoginVo> customerLoginVoResult = customerInfoFeignClient.getCustomerLoginInfo(Long.parseLong(customerId));
        Integer code = customerLoginVoResult.getCode();
        if(code!=200)
        {
            throw new GuiguException(ResultCodeEnum.DATA_ERROR);
        }
        CustomerLoginVo customerLoginVo = customerLoginVoResult.getData();
        if(customerLoginVo == null){
            throw new GuiguException(ResultCodeEnum.DATA_ERROR);
        }
        //5 返回
        return customerLoginVo;
    }
}
