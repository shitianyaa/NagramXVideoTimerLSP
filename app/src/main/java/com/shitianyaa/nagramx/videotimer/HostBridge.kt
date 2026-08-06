package com.shitianyaa.nagramx.videotimer

import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Small reflection boundary for objects owned by the target application's class loader. */
internal class HostBridge(
    private val classLoader: ClassLoader,
    private val logger: (String, Throwable?) -> Unit,
) {
    fun loadClass(name: String): Class<*>? = try {
        Class.forName(name, false, classLoader)
    } catch (_: ClassNotFoundException) {
        null
    } catch (t: Throwable) {
        logger("加载类 $name 失败", t)
        null
    }

    fun findMethod(
        type: Class<*>?,
        name: String,
        parameterCount: Int = -1,
        predicate: ((Method) -> Boolean)? = null,
    ): Method? {
        var current = type
        while (current != null) {
            val method = try {
                current.declaredMethods.firstOrNull {
                    it.name == name &&
                        (parameterCount < 0 || it.parameterTypes.size == parameterCount) &&
                        (predicate == null || predicate(it))
                }
            } catch (t: Throwable) {
                logger("枚举 ${current.name}.$name 方法失败", t)
                null
            }
            if (method != null) {
                makeAccessible(method)
                return method
            }
            current = current.superclass
        }
        return null
    }

    fun findMethod(type: Class<*>?, name: String, vararg parameterTypes: Class<*>): Method? {
        return findMethod(type, name, parameterTypes.size) {
            it.parameterTypes.contentEquals(parameterTypes)
        }
    }

    fun findField(type: Class<*>?, name: String): Field? {
        var current = type
        while (current != null) {
            try {
                return current.getDeclaredField(name).also(::makeAccessible)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            } catch (t: Throwable) {
                logger("查找 ${current.name}.$name 字段失败", t)
                return null
            }
        }
        return null
    }

    fun getField(instance: Any?, name: String): Any? {
        if (instance == null) return null
        val field = findField(instance.javaClass, name) ?: return null
        return getField(instance, field)
    }

    fun getField(instance: Any?, field: Field?): Any? {
        if (instance == null || field == null) return null
        return try {
            field.get(instance)
        } catch (t: Throwable) {
            logger("读取字段 ${field.declaringClass.name}.${field.name} 失败", t)
            null
        }
    }

    fun setField(instance: Any?, name: String, value: Any?): Boolean {
        if (instance == null) return false
        val field = findField(instance.javaClass, name) ?: return false
        return try {
            field.set(instance, value)
            true
        } catch (t: Throwable) {
            logger("写入字段 $name 失败", t)
            false
        }
    }

    fun getStaticField(type: Class<*>?, name: String): Any? {
        val field = findField(type, name) ?: return null
        return try {
            field.get(null)
        } catch (t: Throwable) {
            logger("读取静态字段 $name 失败", t)
            null
        }
    }

    fun invoke(instance: Any?, method: Method?, vararg args: Any?): Any? {
        if (method == null) return null
        return try {
            makeAccessible(method)
            method.invoke(instance, *args)
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            logger("调用 ${method.declaringClass.name}.${method.name} 失败", cause)
            null
        }
    }

    fun invokeNamed(instance: Any?, name: String, vararg args: Any?): Any? {
        val method = findCompatibleMethod(instance?.javaClass, name, args) ?: return null
        return invoke(instance, method, *args)
    }

    fun invokeStatic(type: Class<*>?, name: String, vararg args: Any?): Any? {
        val method = findCompatibleMethod(type, name, args) ?: return null
        return invoke(null, method, *args)
    }

    fun hasMethod(type: Class<*>?, name: String, parameterCount: Int = -1): Boolean {
        return findMethod(type, name, parameterCount) != null
    }

    private fun findCompatibleMethod(type: Class<*>?, name: String, args: Array<out Any?>): Method? {
        return findMethod(type, name, args.size) { method ->
            method.parameterTypes.withIndex().all { (index, parameter) ->
                val argument = args.getOrNull(index)
                argument == null && !parameter.isPrimitive ||
                    argument != null && box(parameter).isAssignableFrom(argument.javaClass)
            }
        }
    }

    private fun makeAccessible(member: Any) {
        try {
            when (member) {
                is Method -> member.isAccessible = true
                is Field -> member.isAccessible = true
            }
        } catch (t: Throwable) {
            logger("设置反射访问权限失败", t)
        }
    }

    private fun box(type: Class<*>): Class<*> = when (type) {
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
        Char::class.javaPrimitiveType -> Char::class.javaObjectType
        Short::class.javaPrimitiveType -> Short::class.javaObjectType
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        else -> type
    }
}
