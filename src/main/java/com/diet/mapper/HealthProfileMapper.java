package com.diet.mapper;

import com.diet.model.HealthProfileRow;
import com.diet.model.HealthProfileVersionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** health_profile / health_profile_version 表（34 号票健康档案）。 */
@Mapper
public interface HealthProfileMapper {

    /** 按匿名身份查询当前档案。 */
    HealthProfileRow findByUserId(@Param("userId") Long userId);

    /** 插入当前档案（新用户首份档案）。 */
    int insert(HealthProfileRow row);

    /** 更新当前档案（版本号递增由服务层负责）。 */
    int update(HealthProfileRow row);

    /** 插入档案版本快照。 */
    int insertVersion(HealthProfileVersionRow row);
}
