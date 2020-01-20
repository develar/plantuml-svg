package org.develar.plantuml.net.sourceforge.plantuml.svg

import org.develar.plantuml.extraSvgStyleLines
import org.w3c.dom.Element
import java.lang.StringBuilder

private class IntCounter(private val prefix: String) {
  private var counter = 0

  fun generate() = "$prefix${counter++}"
}

internal class SvgCssBuilder {
  private val attributesToId = linkedMapOf<Map<String, String>, String>()
  private val styleNameToAttributes = linkedMapOf<String, Map<String, String>>()

  private var counter = linkedMapOf<String, IntCounter>()

  fun add(prefix: String, attributes: Map<String, String>): String {
    var className = attributesToId.get(attributes)
    if (className != null) {
      return className
    }

    className = counter.getOrPut(prefix, { IntCounter(prefix) }).generate()
    attributesToId.put(attributes, className)
    styleNameToAttributes.put(".$className", attributes)
    return className
  }

  fun addNamed(name: String, attributes: Map<String, String>) {
    styleNameToAttributes[name] = attributes
  }

  fun applyStyle(styleElementProducer: () -> Element) {
    if (styleNameToAttributes.isEmpty()) {
      return
    }

    val indent = "  "
    val builder = StringBuilder()
    extraSvgStyleLines.joinTo(builder, separator = "\n")
    for (name in styleNameToAttributes.keys.sorted()) {
      builder.append('\n')
      builder.append(name)
      builder.append(" {")
      for ((key, value) in styleNameToAttributes.get(name)!!) {
        builder.append('\n')
        builder.append(indent)
        builder.append(key)
        builder.append(": ")
        builder.append(value)
        if (!value.endsWith("px") && (key.endsWith("-width") || key.endsWith("-size"))) {
          builder.append("px")
        }
        builder.append(";")
      }
      builder.append("\n}\n")
    }

    val element = styleElementProducer()
    val cdata = element.ownerDocument.createCDATASection(builder.toString())
    element.appendChild(cdata)
  }
}