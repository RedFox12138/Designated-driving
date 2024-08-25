package com.atguigu.daijia.map.service.impl;

import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.constant.SystemConstant;
import com.atguigu.daijia.common.result.Result;
import com.atguigu.daijia.driver.client.DriverInfoFeignClient;
import com.atguigu.daijia.map.service.LocationService;
import com.atguigu.daijia.model.entity.driver.DriverSet;
import com.atguigu.daijia.model.form.map.SearchNearByDriverForm;
import com.atguigu.daijia.model.form.map.UpdateDriverLocationForm;
import com.atguigu.daijia.model.vo.map.NearByDriverVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class LocationServiceImpl implements LocationService {
    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private DriverInfoFeignClient driverInfoFeignClient;

    //更新司机的位置信息
    @Override
    public Boolean updateDriverLocation(UpdateDriverLocationForm updateDriverLocationForm) {
        //把司机的位置信息添加到redis里面geo
        Point point = new Point(updateDriverLocationForm.getLongitude().doubleValue(),updateDriverLocationForm.getLatitude().doubleValue());
        redisTemplate.opsForGeo().add(RedisConstant.DRIVER_GEO_LOCATION,point,updateDriverLocationForm.getDriverId().toString());
        return true;
    }

    //删除司机的位置信息
    @Override
    public Boolean removeDriverLocation(Long driverId) {
        redisTemplate.opsForGeo().remove(RedisConstant.DRIVER_GEO_LOCATION,driverId.toString());
        return true;
    }

    //搜索附近满足条件的司机
    @Override
    public List<NearByDriverVo> searchNearByDriver(SearchNearByDriverForm searchNearByDriverForm) {
        //搜索经纬度位置5公里以内的司机

        //1 操作redis里面的geo
        //创建point，经纬度位置
        Point point = new Point(searchNearByDriverForm.getLongitude().doubleValue(),
                searchNearByDriverForm.getLatitude().doubleValue());

        //定义距离，5公里范围以内
        Distance distance = new Distance(SystemConstant.NEARBY_DRIVER_RADIUS,
                RedisGeoCommands.DistanceUnit.KILOMETERS);

        //创建一个circle对象,point,distance
        Circle circle = new Circle(point,distance);

        //定义GEO的相关参数
        RedisGeoCommands.GeoRadiusCommandArgs args=
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                        .includeDistance() //包含距离
                        .includeCoordinates()  //包含坐标
                        .sortAscending();  //按升序方式进行排列


        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().radius(RedisConstant.DRIVER_GEO_LOCATION, circle, args);
        //2 查询redis最终返回list集合
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        //3 对查询的list集合进行处理
        //  遍历list集合，得到每个司机的信息
        //  根据每个司机个性化信息进行判断
        List<NearByDriverVo> list = new ArrayList<>();
        if(!CollectionUtils.isEmpty(content)){
            Iterator<GeoResult<RedisGeoCommands.GeoLocation<String>>> iterator = content.iterator();
            while(iterator.hasNext()){
                GeoResult<RedisGeoCommands.GeoLocation<String>> item = iterator.next();

                //获取司机id
                long driverId = Long.parseLong(item.getContent().getName());

                //远程调用，根据司机id个性化设置信息
                Result<DriverSet> driverSetResult = driverInfoFeignClient.getDriverSet(driverId);
                DriverSet driverSet = driverSetResult.getData();

                //判断订单里程order_distance
                BigDecimal orderDistance = driverSet.getOrderDistance();
                //orderDistance等于0，司机没有限制，多远的订单都接
                //如果不等于0，比如30，接单30公里代驾订单
                //接单距离-当前单子的距离 < 0，则不符合条件
                if(orderDistance.doubleValue()!=0 &&
                        orderDistance.subtract(searchNearByDriverForm.getMileageDistance()).doubleValue()<0){
                    continue;
                }
                //判断接单里程 accept_distance
                //当前接单距离
                BigDecimal currrentDistance =new BigDecimal(item.getDistance().getValue()).setScale(2, RoundingMode.UP);
                BigDecimal acceptDistance = driverSet.getAcceptDistance();
                if(acceptDistance.doubleValue()!=0
                        && acceptDistance.subtract(currrentDistance).doubleValue()<0){
                    continue;
                }
                NearByDriverVo nearByDriverVo = new NearByDriverVo();
                nearByDriverVo.setDriverId(driverId);
                nearByDriverVo.setDistance(currrentDistance);
                list.add(nearByDriverVo);

            }
        }
        return list;
    }
}
