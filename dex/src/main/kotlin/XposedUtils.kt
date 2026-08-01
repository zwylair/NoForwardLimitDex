package org.zwylair.nfldex

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.zwylair.nfldex.PluginCore.unhookObjs
import java.lang.reflect.Field
import java.lang.reflect.Method

val methodsCache: HashMap<String, Method?> = hashMapOf()
val fieldsCache: HashMap<String, Field?> = hashMapOf()
val classesCache: HashMap<String, Class<*>?> = hashMapOf()

fun hook(method: Method, hookInstance: XC_MethodHook) =
    unhookObjs.add(XposedBridge.hookMethod(method, hookInstance))

fun hook(clazz: Class<*>, methodName: String, argsCount: Int, hookInstance: XC_MethodHook) {
    val method = findMethod(clazz, methodName, argsCount)?: return
    hook(method, hookInstance)
}

fun hook(className: String, methodName: String, argsCount: Int, hookInstance: XC_MethodHook) {
    val clazz = classesCache.getOrPut(className) { Class.forName(className) }
    if (clazz == null) {
        PluginCore.jLog("Class '$className' not found.")
        return
    }
    hook(clazz, methodName, argsCount, hookInstance)
}

inline fun <reified T : Any> T.findField(fieldName: String): Field? {
    return try {
        val cacheKey = "${this.javaClass.name}#$fieldName"

        fieldsCache.getOrPut(cacheKey) {
            var currentClass: Class<*>? = this.javaClass
            var field: Field? = null

            while (field == null && currentClass != null) {
                try { field = currentClass.getDeclaredField(fieldName)  }
                catch (_: NoSuchFieldException) { currentClass = currentClass.superclass }
            }

            field?.apply { isAccessible = true }
                ?: run {
                    PluginCore.jLog("Field '$fieldName' not found in ${this.javaClass.name} hierarchy.")
                    null
                }
        }
    } catch (e: Exception) {
        PluginCore.jLog("An error occurred while getting field '$fieldName':", exception = e)
        null
    }
}

inline fun <reified T : Any> T.getFieldValue(fieldName: String): Any? =
    this.findField(fieldName)?.get(this)

inline fun <reified T : Any> T.setFieldValue(fieldName: String, value: Any?) =
    this.findField(fieldName)?.set(this, value)

fun findMethod(clazz: Class<*>, methodName: String, argsCount: Int = 0): Method? {
    val cacheKey = "${clazz.name}#$methodName($argsCount)"

    val method = methodsCache.getOrPut(cacheKey) {
        clazz.declaredMethods.firstOrNull {
            it.name == methodName && it.parameterCount == argsCount
        }?.apply { isAccessible = true }
    }

    return method?: run {
        PluginCore.jLog("Method '$methodName' ($argsCount args) not found for class ${clazz.name}.")
        null
    }
}

inline fun <reified T : Any> T.findMethod(methodName: String, argsCount: Int = 0): Method? =
    findMethod(this.javaClass, methodName, argsCount)

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
