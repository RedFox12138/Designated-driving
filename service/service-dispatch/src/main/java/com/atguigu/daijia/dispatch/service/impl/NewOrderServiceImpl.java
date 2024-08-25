package com.atguigu.daijia.dispatch.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.dispatch.mapper.OrderJobMapper;
import com.atguigu.daijia.dispatch.service.NewOrderService;
import com.atguigu.daijia.dispatch.xxl.client.XxlJobClient;
import com.atguigu.daijia.map.client.LocationFeignClient;
import com.atguigu.daijia.model.entity.dispatch.OrderJob;
import com.atguigu.daijia.model.enums.OrderStatus;
import com.atguigu.daijia.model.form.map.SearchNearByDriverForm;
import com.atguigu.daijia.model.vo.dispatch.NewOrderTaskVo;
import com.atguigu.daijia.model.vo.map.NearByDriverVo;
import com.atguigu.daijia.model.vo.order.NewOrderDataVo;
import com.atguigu.daijia.order.client.OrderInfoFeignClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.weaver.ast.Or;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class NewOrderServiceImpl implements NewOrderService {
    @Autowired
    private OrderJobMapper orderJobMapper;

    @Autowired
    private XxlJobClient xxlJobClient;

    @Autowired
    private LocationFeignClient locationFeignClient;

    @Autowired
    private OrderInfoFeignClient orderInfoFeignClient;

    @Autowired
    private RedisTemplate redisTemplate;

    //创建并启动任务调度方法
    @Override
    public Long addAndStartTask(NewOrderTaskVo newOrderTaskVo) {
        //1 判断当前订单是否启动了任务调度
        LambdaQueryWrapper<OrderJob> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderJob::getOrderId,newOrderTaskVo.getOrderId());
        OrderJob orderJob = orderJobMapper.selectOne(wrapper);
        //2 没有启动，进行操作
        if(orderJob==null){
            //创建并启动任务调度
            Long jobId = xxlJobClient.addAndStart("newOrderTaskHandler", "", "0 0/1 * * * ?"
                    , "新创建订单任务调度: " + newOrderTaskVo.getOrderId());
            //记录任务调度的相关信息
            orderJob = new OrderJob();
            orderJob.setOrderId(newOrderTaskVo.getOrderId());
            orderJob.setJobId(jobId);
            orderJob.setParameter(JSONObject.toJSONString(newOrderTaskVo));
            orderJobMapper.insert(orderJob);
        }
        return orderJob.getJobId();
    }

    @Override
    public void executeTask(long jobId) {
        //1 根据jobid查询数据库，当前任务是否已经创建
        //如果没有创建，就不往下执行了
        LambdaQueryWrapper<OrderJob> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderJob::getJobId,jobId);
        OrderJob orderJob = orderJobMapper.selectOne(wrapper);
        if(orderJob==null){
            return;
        }
        //2 查询订单状态，如果当前订单是接单状态，继续执行。如果当前订单不是接单的状态，则停止任务的调度。
        //获取OrderJob里面的对象
        String jasonstring = orderJob.getParameter();
        NewOrderTaskVo newOrderTaskVo = JSONObject.parseObject(jasonstring, NewOrderTaskVo.class);
        //获取orderid
        Long orderId = newOrderTaskVo.getOrderId();
        Integer status = orderInfoFeignClient.getOrderStatus(orderId).getData();
        if(status.intValue()!= OrderStatus.WAITING_ACCEPT.getStatus().intValue()){
            //状态不是1，则不是接单状态，停止任务调度
            xxlJobClient.stopJob(jobId);
        }
        //3 远程调用：搜索附近满足条件可以接单的司机
        //4 远程调用之后，获取到满足可以接单司机的集合
        SearchNearByDriverForm searchNearByDriverForm = new SearchNearByDriverForm();
        searchNearByDriverForm.setLongitude(newOrderTaskVo.getStartPointLongitude());
        searchNearByDriverForm.setLatitude(newOrderTaskVo.getStartPointLatitude());
        searchNearByDriverForm.setMileageDistance(newOrderTaskVo.getExpectDistance());
        //远程调用得到满足条件的司机列表
        List<NearByDriverVo> nearByDriverVoList = locationFeignClient.searchNearByDriver(searchNearByDriverForm).getData();

        //5 遍历司机的集合，得到里面的每个司机，为每个司机创建临时队列，存储新的订单信息
        nearByDriverVoList.forEach(driver -> {
            //使用Redis
            //根据订单ID生成一个key
            String repeatKey = RedisConstant.DRIVER_ORDER_REPEAT_LIST+newOrderTaskVo.getOrderId();
            //记录司机id，防止它重复进行推送
            Boolean ismember = redisTemplate.opsForSet().isMember(repeatKey, driver.getDriverId());
            if(!ismember){
                //把订单的信息推送给满足条件的多个司机
                redisTemplate.opsForSet().add(repeatKey,driver.getDriverId());
                //过期时间设置为15分钟
                redisTemplate.expire(repeatKey,
                        RedisConstant.DRIVER_ORDER_REPEAT_LIST_EXPIRES_TIME,
                        TimeUnit.MINUTES);
            }

            NewOrderDataVo newOrderDataVo = new NewOrderDataVo();
            newOrderDataVo.setOrderId(newOrderTaskVo.getOrderId());
            newOrderDataVo.setStartLocation(newOrderTaskVo.getStartLocation());
            newOrderDataVo.setEndLocation(newOrderTaskVo.getEndLocation());
            newOrderDataVo.setExpectAmount(newOrderTaskVo.getExpectAmount());
            newOrderDataVo.setExpectDistance(newOrderTaskVo.getExpectDistance());
            newOrderDataVo.setExpectTime(newOrderTaskVo.getExpectTime());
            newOrderDataVo.setFavourFee(newOrderTaskVo.getFavourFee());
            newOrderDataVo.setDistance(driver.getDistance());
            newOrderDataVo.setCreateTime(newOrderTaskVo.getCreateTime());

            //新订单保存到司机的临时队列，Redis里面的list集合
            String key = RedisConstant.DRIVER_ORDER_TEMP_LIST+driver.getDriverId();
            redisTemplate.opsForList().leftPush(key,JSONObject.toJSONString(newOrderDataVo));
            //过期时间：1分钟
            redisTemplate.expire(key, RedisConstant.DRIVER_ORDER_TEMP_LIST_EXPIRES_TIME, TimeUnit.MINUTES);

        });

    }

    //获取最新的订单
    @Override
    public List<NewOrderDataVo> findNewOrderQueueData(Long driverId) {
        List<NewOrderDataVo> list = new ArrayList<>();
        String key = RedisConstant.DRIVER_ORDER_TEMP_LIST + driverId;
        Long size = redisTemplate.opsForList().size(key);
        if(size>0){
            for(int i=0;i<size;i++)
            {
                String content = (String)redisTemplate.opsForList().leftPop(key);
                NewOrderDataVo newOrderDataVo = JSONObject.parseObject(content,NewOrderDataVo.class);
                list.add(newOrderDataVo);
            }
        }
        return list;
    }

    //清空队列里的数据
    @Override
    public Boolean clearNewOrderQueueData(Long driverId) {
        String key = RedisConstant.DRIVER_ORDER_TEMP_LIST + driverId;
        redisTemplate.delete(key);
        return true;
    }
}
