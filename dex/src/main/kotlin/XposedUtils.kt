package org.zwylair.nfldex

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

val methodsCache: ConcurrentHashMap<String, Optional<Method>> = ConcurrentHashMap()
val fieldsCache: ConcurrentHashMap<String, Optional<Field>> = ConcurrentHashMap()
val classesCache: ConcurrentHashMap<String, Class<*>> = ConcurrentHashMap()
val classesNotFound: MutableSet<String> = ConcurrentHashMap.newKeySet()

fun hook(method: Method, hookInstance: XC_MethodHook) =
    PluginCore.unhookObjs.add(XposedBridge.hookMethod(method, hookInstance))

inline fun Method.before(crossinline callable: (MethodHookParam) -> Unit) =
    PluginCore.unhookObjs.add(XposedBridge.hookMethod(this, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                callable(param)
            } catch (e: Throwable) {
                PluginCore.log("An error occurred in $name.beforeHookedMethod:", exception = e)
            }
        }
    }))

//inline fun Method.after(crossinline callable: (MethodHookParam) -> Unit) =
//    PluginCore.unhookObjs.add(XposedBridge.hookMethod(this, object : XC_MethodHook() {
//        override fun afterHookedMethod(param: MethodHookParam) {
//            try {
//                callable(param)
//            } catch (e: Throwable) {
//                PluginCore.jLog("An error occurred in $name.afterHookedMethod:", exception = e)
//            }
//        }
//    }))

fun findClass(className: String): Class<*>? {
    if (className in classesNotFound) return null

    return classesCache[className]
        ?: runCatching { Class.forName(className) }
            .getOrNull()?.also { classesCache[className] = it }
    ?: run {
        classesNotFound.add(className)
        PluginCore.log("Class '$className' not found.")
        null
    }
}

inline fun <reified T : Any> T.findField(fieldName: String): Field? {
    return try {
        val cacheKey = "${this.javaClass.name}#$fieldName"
        val optionalField = fieldsCache.getOrPut(cacheKey) {
            var currentClass: Class<*>? = this.javaClass
            var field: Field? = null

            while (field == null && currentClass != null) {
                try { field = currentClass.getDeclaredField(fieldName)  }
                catch (_: NoSuchFieldException) { currentClass = currentClass.superclass }
            }

            field?.apply { isAccessible = true }
                ?: run {
                    PluginCore.log("Field '$fieldName' not found in ${this.javaClass.name} hierarchy.")
                    null
                }

            Optional.ofNullable(field)
        }
        optionalField.orElse(null)
    } catch (e: Exception) {
        PluginCore.log("An error occurred while getting field '$fieldName':", exception = e)
        null
    }
}

inline fun <reified T : Any> T.getFieldValue(fieldName: String): Any? =
    this.findField(fieldName)?.get(this)

inline fun <reified T : Any> T.setFieldValue(fieldName: String, value: Any?) =
    this.findField(fieldName)?.set(this, value)

inline fun <reified T : Any> T.findMethod(methodName: String, argsCount: Int = 0): Method? {
    val targetClass: Class<*> = if (this is Class<*>) this else this.javaClass
    val cacheKey = "${targetClass.name}#$methodName($argsCount)"

    return methodsCache.getOrPut(cacheKey) {
        Optional.ofNullable(
            targetClass.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterCount == argsCount
            }?.apply { isAccessible = true }
        )
    }.orElseGet {
        PluginCore.log("Method '$methodName' ($argsCount args) not found for class ${targetClass.name}.")
        null
    }
}

inline fun <reified T : Any> T.safeInvoke(methodName: String, argsCount: Int = 0, vararg args: Any?) =
    this.findMethod(methodName, argsCount)?.invoke(this, *args)

//fun Method.safeInvoke(vararg args: Any?) {
//    this.isAccessible = true
//    this.invoke(this, *args)
//}

fun clearReflectionCache() {
    methodsCache.clear()
    fieldsCache.clear()
    classesCache.clear()
}
