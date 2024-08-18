package com.atguigu.daijia.customer.service;

import com.atguigu.daijia.model.vo.customer.CustomerLoginVo;

public interface CustomerService {

    //微信小程序登录
    String login(String code);

    CustomerLoginVo getCustomerLoginInfo(String token);

    CustomerLoginVo getCustomerInfo(Long customerId);
}
