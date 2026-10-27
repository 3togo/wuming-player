package com.wuming.tv.core.runtime.guardspider

import java.net.URL
import java.net.URLClassLoader
import java.security.CodeSource
import java.security.cert.Certificate
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode

/** Adapts provider-owned request construction without changing OkHttp or the approved JAR. */
class GuardedProviderClassLoader(urls: Array<URL?>, parent: ClassLoader) : URLClassLoader(urls, parent) {
    override fun findClass(name: String): Class<*> {
        val resource = findResource(name.replace('.', '/') + ".class") ?: throw ClassNotFoundException(name)
        val bytes = resource.openStream().use { it.readNBytes(MAX_CLASS_BYTES + 1) }
        require(bytes.size <= MAX_CLASS_BYTES) { "Provider class exceeds size limit" }
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        var changed = Dex2JarShortArrayRepair.apply(node)
        node.methods.forEach { method ->
            method.instructions.toArray().filterIsInstance<MethodInsnNode>().forEach { call ->
                val pair = call.owner in BUILDERS && call.name in HEADER_SETTERS &&
                    call.desc == "(Ljava/lang/String;Ljava/lang/String;)L${call.owner};"
                val map = call.owner in HEADERS && call.name in setOf("of", "-deprecated_of") &&
                    call.desc == "(Ljava/util/Map;)Lokhttp3/Headers;"
                if (pair || map) {
                    method.instructions.insertBefore(call, InsnList().apply {
                        if (pair) {
                            add(InsnNode(Opcodes.DUP2))
                            add(MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "value",
                                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false))
                            add(InsnNode(Opcodes.SWAP))
                            add(InsnNode(Opcodes.POP))
                        } else {
                            add(MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "values",
                                "(Ljava/util/Map;)Ljava/util/Map;", false))
                        }
                    })
                    changed = true
                }
            }
        }
        val adapted = if (changed) ClassWriter(ClassWriter.COMPUTE_MAXS).also(node::accept).toByteArray() else bytes
        return defineClass(name, adapted, 0, adapted.size, CodeSource(getURLs().first(), null as Array<Certificate>?))
    }

    private companion object {
        const val MAX_CLASS_BYTES = 16 * 1024 * 1024
        const val BRIDGE = "com/wuming/tv/core/base/ProviderHttpHeaders"
        val BUILDERS = setOf("okhttp3/Request${'$'}Builder", "okhttp3/Headers${'$'}Builder")
        val HEADERS = setOf("okhttp3/Headers", "okhttp3/Headers${'$'}Companion")
        val HEADER_SETTERS = setOf("header", "addHeader", "add", "set")
    }
}
