package com.stw.im.service.aop;

import com.stw.im.common.annotation.AutoFill;
import com.stw.im.common.model.BaseEntity;
import com.stw.im.common.utils.UserContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公共字段自动填充切面：拦截@AutoFill标记的方法，通过反射填充字段
 */
@Aspect
@Component
public class AutoFillAspect {

    // 缓存类的字段信息，减少反射性能损耗
    private static final ConcurrentHashMap<Class<?>, Field[]> FIELD_CACHE = new ConcurrentHashMap<>();

    @Pointcut("@annotation(autoFill)")
    public void pointcut(AutoFill autoFill) {}

    @Around("pointcut(autoFill)")
    public Object around(ProceedingJoinPoint joinPoint, AutoFill autoFill) throws Throwable {
        long currentTime = System.currentTimeMillis();
        String operatorId = UserContextHolder.getOperatorId(); // 从上下文获取操作人
        Integer appId = UserContextHolder.getCurrentAppId();   // 从上下文获取应用ID

        // 遍历方法参数，对BaseEntity子类进行字段填充
        for (Object arg : joinPoint.getArgs()) {
            if (arg instanceof BaseEntity) {
                BaseEntity entity = (BaseEntity) arg;
                // 根据操作类型填充字段
                if (autoFill.value() == AutoFill.Operation.INSERT) {
                    entity.setCreateTime(currentTime);
                    entity.setAppId(appId);
                    entity.setDelFlag(0); // 默认未删除
                    entity.setOperatorId(operatorId);
                }
                if (autoFill.value() == AutoFill.Operation.UPDATE) {
                    entity.setUpdateTime(currentTime);
                    entity.setOperatorId(operatorId);
                }
                // 支持非BaseEntity的扩展字段填充（反射处理）
                fillExtendFields(entity, autoFill.value(), currentTime, operatorId, appId);
            }
        }

        return joinPoint.proceed();
    }

    // 反射填充非BaseEntity的扩展公共字段（如特殊实体的自定义公共字段）
    private void fillExtendFields(BaseEntity entity, AutoFill.Operation operation,
                                  long currentTime, String operatorId, Integer appId) throws IllegalAccessException {
        Class<?> clazz = entity.getClass();
        Field[] fields = FIELD_CACHE.computeIfAbsent(clazz, Class::getDeclaredFields);

        for (Field field : fields) {
            field.setAccessible(true);
            String fieldName = field.getName();
            // 例如：如果有实体自定义了"createBy"字段，也需要填充
            if (operation == AutoFill.Operation.INSERT && "createBy".equals(fieldName) && field.get(entity) == null) {
                field.set(entity, operatorId);
            }
        }
    }
}