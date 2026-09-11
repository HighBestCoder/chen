package org.jumpserver.chen.framework.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ReflectUtils {
    public static Field getField(Class<?> clazz, String fieldName) {
        Field field = null;
        while (clazz != null) {
            try {
                field = clazz.getDeclaredField(fieldName);
                break;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return field;
    }



    // 查找此属性的 setter 方法 并设置值
    public static void setFieldValue(Object obj, String fieldName, Object value) {
        Class<?> clazz = obj.getClass();
        String methodName = "set" + fieldName.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + fieldName.substring(1);
        try {
            Method method = null;
            if (value != null) {
                try { method = clazz.getMethod(methodName, value.getClass()); }
                catch (NoSuchMethodException ignored) { /* Use the declared property type below. */ }
            }
            if (method == null) {
                Field field = getField(clazz, fieldName);
                if (field == null) throw new NoSuchFieldException(fieldName);
                method = clazz.getMethod(methodName, field.getType());
            }
            method.invoke(obj, value);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            // Never return a partially populated metadata object as a successful result.
            throw new IllegalArgumentException("Cannot set property " + clazz.getName() + "." + fieldName, e);
        }
    }
}
