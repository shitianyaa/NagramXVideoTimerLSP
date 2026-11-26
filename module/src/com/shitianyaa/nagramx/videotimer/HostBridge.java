package com.shitianyaa.nagramx.videotimer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 宿主对象与类反射调用边界。
 */
final class HostBridge {

    public interface MethodPredicate {
        boolean test(Method method);
    }

    public interface Logger {
        void log(String message, Throwable throwable);
    }

    private final ClassLoader classLoader;
    private final Logger logger;
    private volatile Map<String, String> classNames = Collections.emptyMap();

    HostBridge(ClassLoader classLoader, Logger logger) {
        this.classLoader = classLoader;
        this.logger = logger != null ? logger : (msg, t) -> {};
    }

    Class<?> loadClass(String name) {
        try {
            return Class.forName(classNames.getOrDefault(name, name), false, classLoader);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Throwable t) {
            logger.log("加载类 " + name + " 失败", t);
            return null;
        }
    }

    void bindClasses(Map<String, String> names) {
        classNames = Collections.unmodifiableMap(new HashMap<>(names));
    }

    void report(String message, Throwable failure) {
        logger.log(message, failure);
    }

    Method findMethod(Class<?> type, String name, int parameterCount, MethodPredicate predicate) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Method[] methods;
            try {
                methods = current.getDeclaredMethods();
            } catch (Throwable t) {
                logger.log("枚举 " + current.getName() + "." + name + " 方法失败", t);
                continue;
            }
            for (Method m : methods) {
                if (m.getName().equals(name) &&
                        (parameterCount < 0 || m.getParameterTypes().length == parameterCount) &&
                        (predicate == null || predicate.test(m))) {
                    makeAccessible(m);
                    return m;
                }
            }
        }
        return null;
    }

    Method findMethod(Class<?> type, String name, int parameterCount) {
        return findMethod(type, name, parameterCount, null);
    }

    Method findMethod(Class<?> type, String name) {
        return findMethod(type, name, 0, null);
    }

    /**
     * 名字优先，形状回退。
     *
     * <p>原版 NagramX 保留成员名，直接按名命中；被 R8 改名（或成员被内联后
     * 只剩同形状的其它方法）时，退化为按形状谓词定位，且仅当候选唯一才采用。</p>
     */
    Method findMethodOrShape(Class<?> type, String name, int parameterCount, MethodPredicate shape) {
        Method byName = findMethod(type, name, parameterCount, shape);
        if (byName != null) return byName;
        return findUniqueByShape(type, name, shape);
    }

    /** 忽略方法名枚举全部候选（含父类）。 */
    List<Method> findMethodsByShape(Class<?> type, MethodPredicate predicate) {
        List<Method> out = new ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Method[] methods;
            try {
                methods = current.getDeclaredMethods();
            } catch (Throwable t) {
                logger.log("枚举 " + current.getName() + " 方法失败", t);
                continue;
            }
            for (Method m : methods) {
                if (predicate == null || predicate.test(m)) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** 忽略方法名，仅当候选唯一时采用；候选不唯一时记录歧义并返回 null，不做猜测。 */
    Method findUniqueByShape(Class<?> type, String label, MethodPredicate shape) {
        if (type == null || shape == null) return null;
        List<Method> hits = findMethodsByShape(type, shape);
        if (hits.size() == 1) {
            Method method = hits.get(0);
            makeAccessible(method);
            return method;
        }
        if (hits.size() > 1) {
            StringBuilder sb = new StringBuilder();
            int shown = Math.min(hits.size(), 6);
            for (int i = 0; i < shown; i++) {
                Method method = hits.get(i);
                if (sb.length() > 0) sb.append(", ");
                sb.append(method.getName()).append(descriptorOf(method));
            }
            if (hits.size() > shown) sb.append(", ...");
            logger.log("形状定位 " + type.getName() + "." + label + " 命中 " + hits.size()
                    + " 个候选，放弃：" + sb, null);
        }
        return null;
    }

    /** 按字段类型查找（用于被 R8 搬到默认包的内部类型做锚点）。 */
    Field findFieldByType(Class<?> type, Class<?> fieldType) {
        if (type == null || fieldType == null) return null;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                for (Field f : current.getDeclaredFields()) {
                    if (f.getType() == fieldType) {
                        makeAccessible(f);
                        return f;
                    }
                }
            } catch (Throwable t) {
                logger.log("枚举 " + current.getName() + " 字段失败", t);
            }
        }
        return null;
    }

    /** 混淆名表未覆盖该版本时退回源码原名。 */
    static String pickName(String obfuscated, String original) {
        return obfuscated != null ? obfuscated : original;
    }

    static String descriptorOf(Method method) {        if (method == null) return "<null>";
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : method.getParameterTypes()) {
            sb.append(p.getSimpleName()).append(',');
        }
        sb.append(")->").append(method.getReturnType().getSimpleName());
        return sb.toString();
    }

    Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        int count = parameterTypes != null ? parameterTypes.length : 0;
        return findMethod(type, name, count, m -> {
            Class<?>[] types = m.getParameterTypes();
            if (types.length != count) return false;
            for (int i = 0; i < count; i++) {
                if (!types[i].equals(parameterTypes[i])) return false;
            }
            return true;
        });
    }

    /** 读取字段值，字段为 null 时返回 null。 */
    Object getFieldOrDefault(Object instance, Field field, Object fallback) {
        if (instance == null || field == null) return fallback;
        Object value = getField(instance, field);
        return value != null ? value : fallback;
    }

    Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field f = current.getDeclaredField(name);
                makeAccessible(f);
                return f;
            } catch (NoSuchFieldException ignored) {
                continue;
            } catch (Throwable t) {
                logger.log("查找 " + current.getName() + "." + name + " 字段失败", t);
                return null;
            }
        }
        return null;
    }

    Object newInstance(Class<?> type, Object... args) {
        if (type == null) return null;
        Constructor<?> constructor = findConstructor(type, args);
        if (constructor == null) return null;
        try {
            makeAccessible(constructor);
            return constructor.newInstance(args);
        } catch (Throwable t) {
            Throwable cause = (t instanceof InvocationTargetException)
                    ? ((InvocationTargetException) t).getTargetException()
                    : t;
            logger.log("实例化 " + type.getName() + " 失败", cause);
            return null;
        }
    }

    Object newInstance(String className, Object... args) {
        return newInstance(loadClass(className), args);
    }

    boolean setStaticField(Class<?> type, String name, Object value) {
        Field field = findField(type, name);
        if (field == null) return false;
        try {
            field.set(null, value);
            return true;
        } catch (Throwable t) {
            logger.log("写入静态字段 " + field.getDeclaringClass().getName() + "." + field.getName() + " 失败", t);
            return false;
        }
    }

    Object getField(Object instance, String name) {
        if (instance == null) return null;
        Field field = findField(instance.getClass(), name);
        return getField(instance, field);
    }

    Object getField(Object instance, Field field) {
        if (instance == null || field == null) return null;
        try {
            return field.get(instance);
        } catch (Throwable t) {
            logger.log("读取字段 " + field.getDeclaringClass().getName() + "." + field.getName() + " 失败", t);
            return null;
        }
    }

    boolean setField(Object instance, String name, Object value) {
        if (instance == null) return false;
        Field field = findField(instance.getClass(), name);
        return setField(instance, field, value);
    }

    boolean setField(Object instance, Field field, Object value) {
        if (instance == null || field == null) return false;
        try {
            field.set(instance, value);
            return true;
        } catch (Throwable t) {
            logger.log("写入字段 " + field.getName() + " 失败", t);
            return false;
        }
    }

    Object getStaticField(Class<?> type, String name) {
        Field field = findField(type, name);
        return getStaticField(field);
    }

    Object getStaticField(Field field) {
        if (field == null) return null;
        try {
            return field.get(null);
        } catch (Throwable t) {
            logger.log("读取静态字段 " + field.getDeclaringClass().getName() + "." + field.getName() + " 失败", t);
            return null;
        }
    }

    Object invoke(Object instance, Method method, Object... args) {
        if (method == null) return null;
        try {
            makeAccessible(method);
            return method.invoke(instance, args);
        } catch (Throwable t) {
            Throwable cause = (t instanceof InvocationTargetException)
                    ? ((InvocationTargetException) t).getTargetException()
                    : t;
            logger.log("调用 " + method.getDeclaringClass().getName() + "." + method.getName() + " 失败", cause);
            return null;
        }
    }

    Object invokeNamed(Object instance, String name, Object... args) {
        if (instance == null) return null;
        Method method = findCompatibleMethod(instance.getClass(), name, args);
        if (method == null) return null;
        return invoke(instance, method, args);
    }

    Object invokeStatic(Class<?> type, String name, Object... args) {
        Method method = findCompatibleMethod(type, name, args);
        if (method == null) return null;
        return invoke(null, method, args);
    }

    boolean hasMethod(Class<?> type, String name, int parameterCount) {
        return findMethod(type, name, parameterCount, null) != null;
    }

    private Method findCompatibleMethod(Class<?> type, String name, Object[] args) {
        int length = args != null ? args.length : 0;
        return findMethod(type, name, length, method -> {
            Class<?>[] parameterTypes = method.getParameterTypes();
            for (int i = 0; i < length; i++) {
                Object arg = args[i];
                Class<?> param = parameterTypes[i];
                if (arg == null) {
                    if (param.isPrimitive()) return false;
                } else {
                    if (!box(param).isAssignableFrom(arg.getClass())) return false;
                }
            }
            return true;
        });
    }

    private Constructor<?> findConstructor(Class<?> type, Object[] args) {
        int length = args != null ? args.length : 0;
        try {
            for (Constructor<?> ctor : type.getDeclaredConstructors()) {
                Class<?>[] parameterTypes = ctor.getParameterTypes();
                if (parameterTypes.length != length) continue;
                boolean match = true;
                for (int i = 0; i < length; i++) {
                    Object arg = args[i];
                    Class<?> param = parameterTypes[i];
                    if (arg == null) {
                        if (param.isPrimitive()) {
                            match = false;
                            break;
                        }
                    } else {
                        if (!box(param).isAssignableFrom(arg.getClass())) {
                            match = false;
                            break;
                        }
                    }
                }
                if (match) return ctor;
            }
        } catch (Throwable t) {
            logger.log("枚举 " + type.getName() + " 构造方法失败", t);
        }
        return null;
    }

    private void makeAccessible(Object member) {
        try {
            if (member instanceof Method) {
                ((Method) member).setAccessible(true);
            } else if (member instanceof Field) {
                ((Field) member).setAccessible(true);
            } else if (member instanceof Constructor<?>) {
                ((Constructor<?>) member).setAccessible(true);
            }
        } catch (Throwable t) {
            logger.log("设置反射访问权限失败", t);
        }
    }

    private Class<?> box(Class<?> type) {
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return type;
    }
}
