package app.revanced.patcher.signature.implementation.method.resolver

import app.revanced.patcher.data.PatcherData
import app.revanced.patcher.data.implementation.proxy
import app.revanced.patcher.extensions.MethodSignatureExtensions.fuzzyThreshold
import app.revanced.patcher.extensions.MethodSignatureExtensions.name
import app.revanced.patcher.extensions.parametersEqual
import app.revanced.patcher.signature.implementation.method.MethodSignature
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.formats.Instruction21c
import org.jf.dexlib2.iface.reference.StringReference
import java.util.logging.Logger

internal class MethodSignatureResolver(
    private val classes: List<ClassDef>,
    private val methodSignatures: Iterable<MethodSignature>
) {
    // These functions do not require the constructor values, so they can be static.
    companion object {
        private val LOGGER: Logger = Logger.getLogger(::MethodSignatureResolver.name)

        fun resolveFromProxy(
            classProxy: app.revanced.patcher.util.proxy.ClassProxy,
            signature: MethodSignature
        ): SignatureResolverResult? {
            for (method in classProxy.immutableClass.methods) {
                val result = compareSignatureToMethod(signature, method) ?: continue

                LOGGER.fine("${signature.name} match to ${method.definingClass}->${method.name}")

                return SignatureResolverResult(
                    classProxy,
                    result,
                    method,
                )
            }
            return null
        }

        private fun compareSignatureToMethod(
            signature: MethodSignature,
            method: Method
        ): PatternScanResult? {
            signature.returnType?.let {
                if (!method.returnType.startsWith(signature.returnType)) {
                    return null
                }
            }

            signature.accessFlags?.let {
                if (signature.accessFlags != method.accessFlags) {
                    return null
                }
            }

            signature.methodParameters?.let {
                if (!parametersEqual(signature.methodParameters, method.parameterTypes)) {
                    return null
                }
            }

            signature.strings?.let { strings ->
                method.implementation ?: return null

                val stringsList = strings.toMutableList()

                for (instruction in method.implementation!!.instructions) {
                    if (instruction.opcode != Opcode.CONST_STRING) continue

                    val string = ((instruction as Instruction21c).reference as StringReference).string
                    val i = stringsList.indexOfFirst { it == string }
                    if (i != -1) stringsList.removeAt(i)
                }

                if (stringsList.isNotEmpty()) return null
            }

            return if (signature.opcodes == null) {
                PatternScanResult(0, 0)
            } else {
                method.implementation?.instructions?.let {
                    compareOpcodes(signature, it)
                }
            }
        }

        private fun compareOpcodes(
            signature: MethodSignature,
            instructions: Iterable<Instruction>
        ): PatternScanResult? {
            val count = instructions.count()
            val pattern = signature.opcodes!!
            val size = pattern.count()

            val threshold = signature.fuzzyThreshold

            for (instructionIndex in 0 until count) {
                var patternIndex = 0
                var currentThreshold = threshold
                while (instructionIndex + patternIndex < count) {
                    val originalOpcode = instructions.elementAt(instructionIndex + patternIndex).opcode
                    val patternOpcode = pattern.elementAt(patternIndex)
                    if (
                        patternOpcode != null && // unknown opcode
                        originalOpcode != patternOpcode &&
                        currentThreshold-- == 0
                    ) break
                    if (++patternIndex < size) continue
                    patternIndex-- // fix pattern offset

                    val result = PatternScanResult(instructionIndex, instructionIndex + patternIndex)

                    result.warnings = generateWarnings(signature, instructions, result)

                    return result
                }
            }

            return null
        }

        private fun generateWarnings(
            signature: MethodSignature,
            instructions: Iterable<Instruction>,
            scanResult: PatternScanResult,
        ) = buildList {
            val pattern = signature.opcodes!!
            for ((patternIndex, instructionIndex) in (scanResult.startIndex until scanResult.endIndex).withIndex()) {
                val correctOpcode = instructions.elementAt(instructionIndex).opcode
                val patternOpcode = pattern.elementAt(patternIndex)
                if (
                    patternOpcode != null && // unknown opcode
                    correctOpcode != patternOpcode
                ) {
                    this.add(
                        PatternScanResult.Warning(
                            correctOpcode, patternOpcode,
                            instructionIndex, patternIndex,
                        )
                    )
                }
            }
        }
    }

    fun resolve(patcherData: PatcherData) {
        for (signature in methodSignatures) {
            val signatureName = signature.name
            LOGGER.fine("Resolve $signatureName")
            for (classDef in classes) {
                for (method in classDef.methods) {
                    val patternScanData = compareSignatureToMethod(signature, method) ?: continue

                    LOGGER.fine("$signatureName match to ${method.definingClass}->${method.name}")

                    // create class proxy, in case a patch needs mutability
                    val classProxy = patcherData.bytecodeData.proxy(classDef)
                    signature.result = SignatureResolverResult(
                        classProxy,
                        patternScanData,
                        method,
                    )
                }
            }
        }
    }
}

private operator fun ClassDef.component1() = this
private operator fun ClassDef.component2() = this.methods