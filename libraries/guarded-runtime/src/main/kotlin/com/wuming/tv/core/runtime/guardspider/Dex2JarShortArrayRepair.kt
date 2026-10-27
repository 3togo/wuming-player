package com.wuming.tv.core.runtime.guardspider

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.VarInsnNode

/** Repairs dex2jar 2.4's reversed fill-array-data copy, including already prepared provider JARs. */
internal object Dex2JarShortArrayRepair {
    fun apply(node: ClassNode): Boolean {
        var changed = false
        node.methods.forEach { method ->
            method.instructions.toArray().filterIsInstance<MethodInsnNode>().forEach copyLoop@{ copy ->
                if (copy.owner != "java/lang/System" || copy.name != "arraycopy" ||
                    copy.desc != "(Ljava/lang/Object;ILjava/lang/Object;II)V") return@copyLoop
                val count = previous(copy)
                val destinationOffset = previous(count)
                val decoder = previous(destinationOffset) as? MethodInsnNode ?: return@copyLoop
                if (decoder.owner != node.name || !decoder.name.startsWith("${'$'}d2j${'$'}hex${'$'}") ||
                    !decoder.name.endsWith("${'$'}decode_S") || decoder.desc != "(Ljava/lang/String;)[S") return@copyLoop
                val literal = previous(decoder) as? LdcInsnNode ?: return@copyLoop
                val hex = literal.cst as? String ?: return@copyLoop
                val sourceOffset = previous(literal)
                val source = previous(sourceOffset) as? VarInsnNode ?: return@copyLoop
                val store = previous(source) as? VarInsnNode ?: return@copyLoop
                val allocation = previous(store) as? IntInsnNode ?: return@copyLoop
                val size = integer(count) ?: return@copyLoop
                if (source.opcode != Opcodes.ALOAD || store.opcode != Opcodes.ASTORE || store.`var` != source.`var` ||
                    allocation.opcode != Opcodes.NEWARRAY || allocation.operand != Opcodes.T_SHORT ||
                    integer(previous(allocation)) != size || size <= 0 ||
                    sourceOffset?.opcode != Opcodes.ICONST_0 || destinationOffset?.opcode != Opcodes.ICONST_0 ||
                    hex.length.toLong() != size.toLong() * 4 || hex.any { it.digitToIntOrNull(16) == null }) return@copyLoop
                // Original stack: empty, 0, decoded, 0, count. The decoded array must be the source.
                method.instructions.remove(source)
                method.instructions.remove(sourceOffset)
                method.instructions.insert(decoder, InsnList().apply {
                    add(InsnNode(Opcodes.ICONST_0))
                    add(source)
                })
                changed = true
            }
        }
        return changed
    }

    private fun previous(node: AbstractInsnNode?): AbstractInsnNode? {
        var result = node?.previous
        while (result != null && result.opcode < 0) result = result.previous
        return result
    }

    private fun integer(node: AbstractInsnNode?): Int? = when (node) {
        is IntInsnNode -> if (node.opcode in setOf(Opcodes.BIPUSH, Opcodes.SIPUSH)) node.operand else null
        is LdcInsnNode -> node.cst as? Int
        else -> node?.opcode?.takeIf { it in Opcodes.ICONST_M1..Opcodes.ICONST_5 }?.minus(Opcodes.ICONST_0)
    }
}
