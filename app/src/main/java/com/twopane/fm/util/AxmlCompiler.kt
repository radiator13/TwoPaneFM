package com.twopane.fm.util

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AXML Compiler — converts text XML back to Android binary XML format.
 * Supports basic AndroidManifest.xml and resource XML compilation.
 */
object AxmlCompiler {

    // Android binary XML constants
    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
    private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val RES_XML_CDATA_TYPE = 0x0104

    // Attribute value types
    private const val VALUE_TYPE_NULL = 0x00
    private const val VALUE_TYPE_REFERENCE = 0x01
    private const val VALUE_TYPE_ATTRIBUTE = 0x02
    private const val VALUE_TYPE_STRING = 0x03
    private const val VALUE_TYPE_FLOAT = 0x04
    private const val VALUE_TYPE_DIMENSION = 0x05
    private const val VALUE_TYPE_FRACTION = 0x06
    private const val VALUE_TYPE_INT_DEC = 0x10
    private const val VALUE_TYPE_INT_HEX = 0x11
    private const val VALUE_TYPE_INT_BOOLEAN = 0x12

    /**
     * Compile text XML to binary AXML bytes.
     */
    fun compile(xmlText: String): Result<ByteArray> = runCatching {
        val strings = mutableListOf<String>()
        val namespaces = mutableListOf<Pair<String, String>>() // prefix -> uri
        val elements = parseXml(xmlText)

        // Collect all strings
        collectStrings(elements, strings)

        // Build binary XML
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // 1. Write XML header
        dos.writeShort(RES_XML_TYPE) // type
        dos.writeShort(8) // header size
        dos.writeInt(0) // total size (placeholder)

        // 2. Write string pool
        val stringPoolBytes = writeStringPool(strings)
        dos.write(stringPoolBytes)

        // 3. Write namespaces and elements
        writeElements(dos, elements, strings, namespaces)

        // Fix total size
        val result = baos.toByteArray()
        val buf = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(4, result.size)

        result
    }

    private data class XmlElement(
        val name: String,
        val attributes: List<Pair<String, String>>, // name -> value
        val children: MutableList<XmlElement> = mutableListOf(),
        val isSelfClosing: Boolean = false
    )

    private fun parseXml(xml: String): List<XmlElement> {
        val elements = mutableListOf<XmlElement>()
        val stack = ArrayDeque<XmlElement>()
        val lines = xml.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            i++

            if (line.isEmpty() || line.startsWith("<?") || line.startsWith("<!--")) continue

            when {
                // Self-closing tag
                line.startsWith("<") && line.endsWith("/>") -> {
                    val (name, attrs) = parseTag(line.removePrefix("<").removeSuffix("/>").trim())
                    val elem = XmlElement(name, attrs, isSelfClosing = true)
                    if (stack.isNotEmpty()) stack.last().children.add(elem)
                    else elements.add(elem)
                }
                // Opening tag
                line.startsWith("<") && !line.startsWith("</") -> {
                    val (name, attrs) = parseTag(line.removePrefix("<").removeSuffix(">").trim())
                    val elem = XmlElement(name, attrs)
                    stack.addLast(elem)
                }
                // Closing tag
                line.startsWith("</") -> {
                    if (stack.isNotEmpty()) {
                        val elem = stack.removeLast()
                        if (stack.isNotEmpty()) stack.last().children.add(elem)
                        else elements.add(elem)
                    }
                }
                // Text content
                stack.isNotEmpty() && line.isNotBlank() -> {
                    // Add as a child text node (simplified)
                }
            }
        }

        // Flush remaining stack
        while (stack.isNotEmpty()) {
            val elem = stack.removeLast()
            if (stack.isNotEmpty()) stack.last().children.add(elem)
            else elements.add(elem)
        }

        return elements
    }

    private fun parseTag(tag: String): Pair<String, List<Pair<String, String>>> {
        val parts = tag.split("\\s+".toRegex(), limit = 2)
        val name = parts[0]
        val attrs = mutableListOf<Pair<String, String>>()

        if (parts.size > 1) {
            val attrStr = parts[1]
            val attrRegex = """(\w[\w:]*)(?:\s*=\s*"([^"]*)")?""".toRegex()
            attrRegex.findAll(attrStr).forEach { match ->
                val attrName = match.groupValues[1]
                val attrValue = match.groupValues[2]
                attrs.add(attrName to attrValue)
            }
        }

        return name to attrs
    }

    private fun collectStrings(elements: List<XmlElement>, strings: MutableList<String>) {
        for (elem in elements) {
            addString(strings, elem.name)
            for ((attrName, attrValue) in elem.attributes) {
                addString(strings, attrName)
                addString(strings, attrValue)
            }
            collectStrings(elem.children, strings)
        }
    }

    private fun addString(strings: MutableList<String>, str: String): Int {
        if (str.isNotEmpty() && str !in strings) {
            strings.add(str)
        }
        return strings.indexOf(str)
    }

    private fun writeStringPool(strings: List<String>): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // String pool header
        dos.writeShort(RES_STRING_POOL_TYPE)
        dos.writeShort(28) // header size
        dos.writeInt(0) // total size placeholder

        val stringCount = strings.size
        dos.writeInt(stringCount) // string count
        dos.writeInt(0) // style count
        dos.writeInt(0x00000100) // flags: UTF-8
        dos.writeInt(0) // strings start offset (placeholder)
        dos.writeInt(0) // styles start offset

        // String offsets
        var offset = 0
        val offsets = mutableListOf<Int>()
        for (str in strings) {
            offsets.add(offset)
            offset += getStringEncodedSize(str)
        }

        for (off in offsets) {
            dos.writeInt(off)
        }

        // String data
        for (str in strings) {
            val encoded = encodeString(str)
            dos.write(encoded)
        }

        val result = baos.toByteArray()
        val buf = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(4, result.size)
        buf.putInt(16, 28 + stringCount * 4) // strings start offset

        return result
    }

    private fun getStringEncodedSize(str: String): Int {
        val utf8 = str.toByteArray(Charsets.UTF_8)
        return if (utf8.size < 128) 2 + utf8.size else 3 + utf8.size
    }

    private fun encodeString(str: String): ByteArray {
        val utf8 = str.toByteArray(Charsets.UTF_8)
        val baos = ByteArrayOutputStream()
        // Write UTF-8 length
        if (utf8.size < 128) {
            baos.write(utf8.size)
        } else {
            baos.write((utf8.size shr 8) or 0x80)
            baos.write(utf8.size and 0xFF)
        }
        baos.write(utf8)
        return baos.toByteArray()
    }

    private fun writeElements(
        dos: DataOutputStream,
        elements: List<XmlElement>,
        strings: MutableList<String>,
        namespaces: MutableList<Pair<String, String>>
    ) {
        for (elem in elements) {
            writeElement(dos, elem, strings, namespaces)
        }
    }

    private fun writeElement(
        dos: DataOutputStream,
        elem: XmlElement,
        strings: MutableList<String>,
        namespaces: MutableList<Pair<String, String>>
    ) {
        val nameIdx = strings.indexOf(elem.name).coerceAtLeast(0)

        // Start namespace for android: attributes
        for ((attrName, _) in elem.attributes) {
            if (attrName.contains(":")) {
                val prefix = attrName.substringBefore(":")
                val uri = when (prefix) {
                    "android" -> "http://schemas.android.com/apk/res/android"
                    else -> ""
                }
                if (uri.isNotEmpty() && prefix to uri !in namespaces) {
                    namespaces.add(prefix to uri)
                    val prefixIdx = addString(strings, prefix)
                    val uriIdx = addString(strings, uri)

                    // Write start namespace
                    dos.writeShort(RES_XML_START_NAMESPACE_TYPE)
                    dos.writeShort(16) // header size
                    dos.writeInt(0) // size placeholder
                    dos.writeInt(0) // line number
                    dos.writeInt(0) // comment
                    dos.writeInt(prefixIdx)
                    dos.writeInt(uriIdx)
                }
            }
        }

        // Write start element
        dos.writeShort(RES_XML_START_ELEMENT_TYPE)
        dos.writeShort(16) // header size
        dos.writeInt(0) // size placeholder
        dos.writeInt(0) // line number
        dos.writeInt(-1) // comment (none)
        dos.writeInt(0) // namespace index (-1 = none)
        dos.writeInt(nameIdx)
        dos.writeShort(0) // attribute start
        dos.writeShort(20) // attribute size
        dos.writeShort(elem.attributes.size) // attribute count
        dos.writeShort(0) // id index
        dos.writeShort(0) // class index
        dos.writeShort(0) // style index

        // Write attributes
        for ((attrName, attrValue) in elem.attributes) {
            val attrNameIdx = addString(strings, attrName)
            val (type, value) = interpretAttributeValue(attrValue)

            dos.writeInt(-1) // namespace index
            dos.writeInt(attrNameIdx)
            dos.writeInt(attrValue.length) // raw string value
            dos.writeShort(type) // value type
            dos.writeByte(0) // padding
            dos.writeByte(0) // padding
            dos.writeInt(value) // typed value
        }

        // Write children
        for (child in elem.children) {
            writeElement(dos, child, strings, namespaces)
        }

        // Write end element
        dos.writeShort(RES_XML_END_ELEMENT_TYPE)
        dos.writeShort(16)
        dos.writeInt(0)
        dos.writeInt(0) // line number
        dos.writeInt(-1) // comment
        dos.writeInt(0) // namespace
        dos.writeInt(nameIdx)

        // Write end namespaces
        for ((prefix, uri) in namespaces.toList()) {
            val prefixIdx = strings.indexOf(prefix).coerceAtLeast(0)
            val uriIdx = strings.indexOf(uri).coerceAtLeast(0)
            dos.writeShort(RES_XML_END_NAMESPACE_TYPE)
            dos.writeShort(16)
            dos.writeInt(0)
            dos.writeInt(0)
            dos.writeInt(-1)
            dos.writeInt(prefixIdx)
            dos.writeInt(uriIdx)
        }
    }

    private fun interpretAttributeValue(value: String): Pair<Int, Int> {
        val result: Pair<Int, Int> = when {
            value == "true" || value == "TRUE" -> VALUE_TYPE_INT_BOOLEAN to 1
            value == "false" || value == "FALSE" -> VALUE_TYPE_INT_BOOLEAN to 0
            value.startsWith("@") -> VALUE_TYPE_REFERENCE to 0
            value.endsWith("dp") || value.endsWith("sp") || value.endsWith("px") ||
            value.endsWith("dip") || value.endsWith("pt") || value.endsWith("mm") -> {
                VALUE_TYPE_DIMENSION to 0
            }
            value.startsWith("#") -> {
                VALUE_TYPE_INT_HEX to parseColor(value)
            }
            value.toIntOrNull() != null -> {
                VALUE_TYPE_INT_DEC to value.toInt()
            }
            value.startsWith("0x") -> {
                VALUE_TYPE_INT_HEX to (value.removePrefix("0x").toIntOrNull(16) ?: 0)
            }
            else -> {
                VALUE_TYPE_STRING to 0
            }
        }
        return result
    }

    private fun parseColor(color: String): Int {
        val hex = color.removePrefix("#")
        return when (hex.length) {
            3 -> { // #RGB
                val r = hex[0].digitToInt() * 17
                val g = hex[1].digitToInt() * 17
                val b = hex[2].digitToInt() * 17
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            6 -> { // #RRGGBB
                val rgb = hex.toIntOrNull(16) ?: 0
                (0xFF shl 24) or rgb
            }
            8 -> { // #AARRGGBB
                hex.toIntOrNull(16) ?: 0
            }
            else -> 0
        }
    }
}
