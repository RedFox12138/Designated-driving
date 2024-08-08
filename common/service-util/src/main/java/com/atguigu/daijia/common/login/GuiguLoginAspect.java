package com.atguigu.daijia.common.login;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Component
@Aspect//表示这是一个切面类
public class GuiguLoginAspect {
    @Around("execution(* com.atguigu.daijia.*.controller.*.*(..)) @ @annotation(guiguLogin)")
    public Object login(ProceedingJoinPoint proceedingJoinPoint ,GuiguLogin guiguLogin) throws Throwable {
        return proceedingJoinPoint.proceed();
    }
}
