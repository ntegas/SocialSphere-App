package com.aistudio.socialsphere.crmlxb.utils

import java.nio.charset.Charset

/**
 * PHONE / VCF PACK #4 — фикстуры vCard в БАЙТАХ (а не в строках): именно байты приходят из
 * ContentResolver.openInputStream(), и именно на байтовом уровне ломались кодировки.
 */
object VcfFixtures {
    const val CRLF = "\r\n"

    fun bytes(text: String, charset: Charset = Charsets.UTF_8): ByteArray = text.toByteArray(charset)
    fun lines(vararg l: String, eol: String = CRLF): String = l.joinToString(eol) + eol

    /** Quoted-printable как его пишут Android-контакты: =XX для не-ASCII/служебных байт, мягкий перенос "=\r\n". */
    fun qp(text: String, charset: Charset = Charsets.UTF_8, softWrapAt: Int = 0): String {
        val sb = StringBuilder()
        var col = 0
        for (b in text.toByteArray(charset)) {
            val v = b.toInt() and 0xFF
            val piece = if (v in 33..126 && v != '='.code || v == ' '.code) v.toChar().toString() else "=%02X".format(v)
            if (softWrapAt > 0 && col + piece.length > softWrapAt) { sb.append("=").append(CRLF); col = 0 }
            sb.append(piece); col += piece.length
        }
        return sb.toString()
    }

    /** RFC-фолдинг ПО БАЙТАМ (как у реальных экспортёров): перенос может попасть внутрь UTF-8 последовательности. */
    fun foldBytes(line: String, everyBytes: Int, charset: Charset = Charsets.UTF_8): ByteArray {
        val raw = line.toByteArray(charset)
        val out = java.io.ByteArrayOutputStream()
        raw.toList().chunked(everyBytes).forEachIndexed { i, chunk ->
            if (i > 0) out.write("\r\n ".toByteArray())
            out.write(chunk.toByteArray())
        }
        out.write("\r\n".toByteArray())
        return out.toByteArray()
    }

    fun concat(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { a, b -> a + b }
}
