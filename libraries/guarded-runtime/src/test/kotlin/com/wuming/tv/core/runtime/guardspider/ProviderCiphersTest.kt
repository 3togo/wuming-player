package com.wuming.tv.core.runtime.guardspider

import java.nio.file.Files
import java.security.NoSuchAlgorithmException
import java.util.HexFormat
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.tools.ToolProvider
import org.junit.Assert.*
import org.junit.Test

class ProviderCiphersTest {
    private val key = SecretKeySpec(ByteArray(16), "AES")
    private val iv = IvParameterSpec(ByteArray(16))

    @Test
    fun encryptsAndroidPaddingWithKnownAesVector() {
        for (name in listOf("AES/CBC/PKCS7Padding", "aes/cbc/pkcs7padding")) {
            val cipher = ProviderCiphers.getInstance(name)
            cipher.init(Cipher.ENCRYPT_MODE, key, iv)
            // Empty input must encrypt a complete block of 0x10 padding.
            assertEquals("0143db63ee66b0cdff9f69917680151e", HexFormat.of().formatHex(cipher.doFinal(byteArrayOf())))
        }
    }

    @Test
    fun decryptsPaddingAndRejectsMalformedCiphertext() {
        val cipher = ProviderCiphers.getInstance("AES/CBC/PKCS7Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        assertArrayEquals(byteArrayOf(), cipher.doFinal(HexFormat.of().parseHex("0143db63ee66b0cdff9f69917680151e")))
        assertThrows(BadPaddingException::class.java) { cipher.doFinal(ByteArray(16)) }
    }

    @Test
    fun preservesOtherTransformationsAndUnsupportedNames() {
        assertThrows(NoSuchAlgorithmException::class.java) { ProviderCiphers.getInstance(null) }
        assertEquals("AES/GCM/NoPadding", ProviderCiphers.getInstance("AES/GCM/NoPadding").algorithm)
        assertThrows(NoSuchAlgorithmException::class.java) { ProviderCiphers.getInstance("missing/CBC/PKCS7Padding") }
        assertThrows(NoSuchAlgorithmException::class.java) { ProviderCiphers.getInstance("DES/CBC/PKCS7Padding") }
    }

    @Test
    fun adaptsPrecompiledProviderWithoutChangingItsClassFile() {
        val root = Files.createTempDirectory("provider-cipher-test-")
        try {
            val source = root.resolve("CipherFixture.java")
            Files.writeString(source, """
                public class CipherFixture {
                    public static javax.crypto.Cipher create(String name) throws Exception {
                        return javax.crypto.Cipher.getInstance(name);
                    }
                    public static javax.crypto.Cipher explicit(String name) throws Exception {
                        return javax.crypto.Cipher.getInstance(name, "SunJCE");
                    }
                }
            """.trimIndent())
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()))
            val classFile = root.resolve("CipherFixture.class")
            val original = Files.readAllBytes(classFile)
            GuardedProviderClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader).use { loader ->
                val fixture = loader.loadClass("CipherFixture")
                val cipher = fixture.getMethod("create", String::class.java).invoke(null, "AES/CBC/PKCS7Padding") as Cipher
                cipher.init(Cipher.ENCRYPT_MODE, key, iv)
                assertEquals("0143db63ee66b0cdff9f69917680151e", HexFormat.of().formatHex(cipher.doFinal(byteArrayOf())))
                assertEquals("AES/CBC/PKCS5Padding", (fixture.getMethod("explicit", String::class.java)
                    .invoke(null, "AES/CBC/PKCS5Padding") as Cipher).algorithm)
                val failure = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                    fixture.getMethod("explicit", String::class.java).invoke(null, "AES/CBC/PKCS7Padding")
                }
                val originalFailure = assertThrows(java.security.GeneralSecurityException::class.java) {
                    Cipher.getInstance("AES/CBC/PKCS7Padding", "SunJCE")
                }
                assertEquals(originalFailure.javaClass, failure.cause!!.javaClass)
            }
            assertArrayEquals(original, Files.readAllBytes(classFile))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
