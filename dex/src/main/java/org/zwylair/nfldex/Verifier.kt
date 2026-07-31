package org.zwylair.nfldex

fun checkPluginId(pluginId: String): Boolean {
    val targetChars = intArrayOf(122, 119, 121, 78, 111, 70, 111, 114, 119, 97, 114, 100, 76, 105, 109, 105, 116)

    if (pluginId.length != targetChars.size)
        return false

    for (i in pluginId.indices)
        if (pluginId[i].code != targetChars[i])
            return false
    return true
}

fun burnCpu(iterations: Int = 100_000) {
    val digest = java.security.MessageDigest.getInstance("SHA-512")
    var data = "anti-tamper-seed".toByteArray()

    repeat(iterations) {
        data = digest.digest(data)
    }
}
