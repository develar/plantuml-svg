/* ========================================================================
 * PlantUML : a free UML diagram generator
 * ========================================================================
 *
 * (C) Copyright 2009-2020, Arnaud Roques
 *
 * Project Info:  http://plantuml.com
 *
 * If you like this project or if you find it useful, you can support us at:
 *
 * http://plantuml.com/patreon (only 1$ per month!)
 * http://plantuml.com/paypal
 *
 * This file is part of PlantUML.
 *
 * Licensed under The MIT License (Massachusetts Institute of Technology License)
 *
 * See http://opensource.org/licenses/MIT
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 *
 * Original Author:  Arnaud Roques
 */
package net.sourceforge.plantuml.svg

import net.sourceforge.plantuml.*
import net.sourceforge.plantuml.graphic.HtmlColorGradient
import net.sourceforge.plantuml.tikz.TikzGraphics
import net.sourceforge.plantuml.ugraphic.ColorMapper
import net.sourceforge.plantuml.ugraphic.UPath
import net.sourceforge.plantuml.ugraphic.USegmentType
import org.develar.plantuml.net.sourceforge.plantuml.svg.SvgCssBuilder
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.awt.geom.Dimension2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.*
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.OutputKeys
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.abs

class SvgGraphics(
  private val svgDimensionStyle: Boolean,
  minDim: Dimension2D,
  private val backcolor: String?,
  private val scale: Double,
  hover: String?,
  seed: Long,
  private val preserveAspectRatio: String?
) {
  private val document: Document
  private val root: Element
  private var defs: Element? = null
  private var gRoot: Element
  private var fill = "black"
  private var stroke = "black"
  private var strokeWidth: String? = null
  private var strokeDasharray: String? = null
  private var maxX = 10
  private var maxY = 10
  private var filterUid: String? = null
  private var c: String? = null
  private var gradientId: String? = null
  private fun ensureVisible(x: Double, y: Double) {
    if (x > maxX) {
      maxX = (x + 1).toInt()
    }
    if (y > maxY) {
      maxY = (y + 1).toInt()
    }
  }

  private val styleBuilder = SvgCssBuilder()
  private val shadowId: String

  constructor(
    svgDimensionStyle: Boolean,
    minDim: Dimension2D,
    scale: Double,
    hover: String?,
    seed: Long,
    preserveAspectRatio: String?
  ) : this(svgDimensionStyle, minDim, null, scale, hover, seed, preserveAspectRatio)

  companion object {
    // http://tutorials.jenkov.com/svg/index.html
    // http://www.svgbasics.com/
    // http://apike.ca/prog_svg_text.html
    // http://www.w3.org/TR/SVG11/shapes.html
    // http://en.wikipedia.org/wiki/Scalable_Vector_Graphics
    // Animation:
    // http://srufaculty.sru.edu/david.dailey/svg/
    // Shadow:
    // http://www.svgbasics.com/filters3.html
    // http://www.w3schools.com/svg/svg_feoffset.asp
    // http://www.adobe.com/svg/demos/samples.html
    private const val XLINK_TITLE1 = "title"
    private const val XLINK_TITLE2 = "xlink:title"
    private const val XLINK_HREF1 = "href"
    private const val XLINK_HREF2 = "xlink:href"

    private fun getSeed(seed: Long): String {
      return abs(seed).toString(36)
    }

    const val MD5_HEADER = "<!--MD5=["

    @JvmStatic
    fun getMD5Hex(comment: String?): String {
      return SignatureUtils.getMD5Hex(comment)
    }
  }

  init {
    try {
      document = getDocument()
      ensureVisible(minDim.width, minDim.height)
      val svg = document.createElement("svg") as Element
      document.appendChild(svg)

      // Set some attributes on the root node that are
      // required for proper rendering. Note that the
      // approach used here is somewhat different from the
      // approach used in the earlier program named Svg01,
      // particularly with regard to the style.
      svg.setAttribute("xmlns", "http://www.w3.org/2000/svg")
      svg.setAttribute("xmlns:xlink", "http://www.w3.org/1999/xlink")
      svg.setAttribute("version", "1.1")
      root = svg

      // Create a node named defs, which will be the parent
      // for a pair of linear gradient definitions.
      defs = simpleElement("defs")
      gRoot = simpleElement("g")
      strokeWidth = "" + scale
      filterUid = "b" + getSeed(seed)
      shadowId = "shadowFilter"
      gradientId = "g" + getSeed(seed)
      if (hover != null) {
        styleBuilder.addNamed("path:hover", mapOf("stroke" to "$hover !important"))
      }
    }
    catch (e: ParserConfigurationException) {
      e.printStackTrace()
      throw IllegalStateException(e)
    }
  }

  private var pendingBackground: Element? = null

  fun paintBackcolorGradient(mapper: ColorMapper, gr: HtmlColorGradient) {
    val id = createSvgGradient(
      StringUtils.getAsHtml(mapper.getMappedColor(gr.color1)),
      StringUtils.getAsHtml(mapper.getMappedColor(gr.color2)), gr.policy
    )
    setFillColor("url(#$id)")
    setStrokeColor(null)
    pendingBackground = createRectangleInternal(0.0, 0.0, 0.0, 0.0)
    g.appendChild(pendingBackground)
  }

  // This method returns a reference to a simple XML
  // element node that has no attributes.
  private fun simpleElement(type: String): Element {
    val theElement = document.createElement(type) as Element
    root.appendChild(theElement)
    return theElement
  }

  @Throws(ParserConfigurationException::class)
  private fun getDocument(): Document {
    val factory = DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()
    val document = builder.newDocument()
    document.xmlStandalone = true
    return document
  } // Create the root node named svg and append it to
  // the document.

  // Set some attributes on the root node that are
  // required for proper rendering. Note that the
  // approach used here is somewhat different from the
  // approach used in the earlier program named Svg01,
  // particularly with regard to the style.

  fun svgEllipse(
    x: Double,
    y: Double,
    xRadius: Double,
    yRadius: Double,
    deltaShadow: Double
  ) {
    manageShadow(deltaShadow)
    if (!hidden) {
      val elt = document.createElement("ellipse") as Element
      elt.setAttribute("cx", format(x))
      elt.setAttribute("cy", format(y))
      elt.setAttribute("rx", format(xRadius))
      elt.setAttribute("ry", format(yRadius))
      applyStrokeStyle(elt, fill, deltaShadow)
      g.appendChild(elt)
    }
    ensureVisible(x + xRadius + deltaShadow * 2, y + yRadius + deltaShadow * 2)
  }

  fun svgArcEllipse(
    rx: Double,
    ry: Double,
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double
  ) {
    if (!hidden) {
      val path =
        ("M" + format(x1) + "," + format(y1) + " A" + format(rx) + "," + format(ry) + " 0 0 0 "
         + format(x2) + " " + format(y2))
      val elt = document.createElement("path") as Element
      elt.setAttribute("d", path)
      applyStrokeStyle(elt, fill)
      g.appendChild(elt)
    }
    ensureVisible(x1, y1)
    ensureVisible(x2, y2)
  }

  private val gradients: MutableMap<List<Any>, String> =
    HashMap()

  fun createSvgGradient(color1: String, color2: String, policy: Char): String {
    val key = listOf(color1 as Any, color2, policy)
    var id = gradients[key]
    if (id != null) {
      return id
    }

    val elt = document.createElement("linearGradient") as Element
    if (policy == '|') {
      elt.setAttribute("x1", "0%")
      elt.setAttribute("y1", "50%")
      elt.setAttribute("x2", "100%")
      elt.setAttribute("y2", "50%")
    }
    else if (policy == '\\') {
      elt.setAttribute("x1", "0%")
      elt.setAttribute("y1", "100%")
      elt.setAttribute("x2", "100%")
      elt.setAttribute("y2", "0%")
    }
    else if (policy == '-') {
      elt.setAttribute("x1", "50%")
      elt.setAttribute("y1", "0%")
      elt.setAttribute("x2", "50%")
      elt.setAttribute("y2", "100%")
    }
    else {
      elt.setAttribute("x1", "0%")
      elt.setAttribute("y1", "0%")
      elt.setAttribute("x2", "100%")
      elt.setAttribute("y2", "100%")
    }
    id = gradientId + gradients.size
    gradients[key] = id
    elt.setAttribute("id", id)
    val stop1 = document.createElement("stop") as Element
    stop1.setAttribute("stop-color", color1)
    stop1.setAttribute("offset", "0%")
    val stop2 = document.createElement("stop") as Element
    stop2.setAttribute("stop-color", color2)
    stop2.setAttribute("offset", "100%")
    elt.appendChild(stop1)
    elt.appendChild(stop2)
    defs!!.appendChild(elt)
    return id
  }

  fun setFillColor(fill: String?) {
    this.fill = fill ?: "none"
  }

  fun setStrokeColor(stroke: String?) {
    this.stroke = stroke ?: "none"
  }

  fun setStrokeWidth(strokeWidth: Double, strokeDasharray: String?) {
    this.strokeWidth = "${scale * strokeWidth}"
    this.strokeDasharray = strokeDasharray
  }

  fun closeLink() {
    if (pendingAction.isNotEmpty()) {
      val element = pendingAction[0]
      pendingAction.removeAt(0)
      if (element.firstChild != null) {
        // Empty link
        g.appendChild(element)
      }
    }
  }

  private val pendingAction: MutableList<Element> = ArrayList()

  fun openLink(url: String?, title: String?, target: String?) {
    var title = title
    requireNotNull(url)
    if (!OptionFlags.ALLOW_INCLUDE && url.toLowerCase().startsWith("javascript")) {
      return
    }
    if (pendingAction.size > 0) {
      closeLink()
    }
    pendingAction.add(0, document.createElement("a") as Element)
    pendingAction[0].setAttribute("target", target)
    pendingAction[0].setAttribute(XLINK_HREF1, url)
    pendingAction[0].setAttribute(XLINK_HREF2, url)
    pendingAction[0].setAttribute("xlink:type", "simple")
    pendingAction[0].setAttribute("xlink:actuate", "onRequest")
    pendingAction[0].setAttribute("xlink:show", "new")
    if (title == null) {
      pendingAction[0].setAttribute(XLINK_TITLE1, url)
      pendingAction[0].setAttribute(XLINK_TITLE2, url)
    }
    else {
      title = title.replace("\\\\n".toRegex(), "\n")
      pendingAction[0].setAttribute(XLINK_TITLE1, title)
      pendingAction[0].setAttribute(XLINK_TITLE2, title)
    }
  }

  val g: Element
    get() = if (pendingAction.size == 0) gRoot else pendingAction.first()

  fun svgRectangle(
    x: Double,
    y: Double,
    width: Double,
    height: Double,
    rx: Double,
    ry: Double,
    deltaShadow: Double,
    id: String?
  ) {
    if (height <= 0 || width <= 0) {
      return
      // To be restored when Teoz will be finished
      // throw new IllegalArgumentException();
    }
    manageShadow(deltaShadow)
    if (!hidden) {
      val elt = createRectangleInternal(x, y, width, height, deltaShadow)
      if (rx > 0 && ry > 0) {
        elt.setAttribute("rx", format(rx))
        elt.setAttribute("ry", format(ry))
      }
      if (id != null) {
        elt.setAttribute("id", id)
      }
      g.appendChild(elt)
    }
    ensureVisible(x + width + 2 * deltaShadow, y + height + 2 * deltaShadow)
  }

  private fun createRectangleInternal(x: Double, y: Double, width: Double, height: Double, deltaShadow: Double = -1.0): Element {
    val element = document.createElement("rect") as Element
    element.setAttribute("x", format(x))
    element.setAttribute("y", format(y))
    element.setAttribute("width", format(width))
    element.setAttribute("height", format(height))
    applyStrokeStyle(element, fill, deltaShadow)
    return element
  }

  fun svgLine(
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
    deltaShadow: Double
  ) {
    manageShadow(deltaShadow)
    if (!hidden) {
      val elt = document.createElement("line") as Element
      elt.setAttribute("x1", format(x1))
      elt.setAttribute("y1", format(y1))
      elt.setAttribute("x2", format(x2))
      elt.setAttribute("y2", format(y2))
      applyStrokeStyle(elt, fill = null, deltaShadow = deltaShadow)
      g.appendChild(elt)
    }
    ensureVisible(x1 + 2 * deltaShadow, y1 + 2 * deltaShadow)
    ensureVisible(x2 + 2 * deltaShadow, y2 + 2 * deltaShadow)
  }

  private fun applyStrokeStyle(element: Element, fill: String? = null, deltaShadow: Double = -1.0) {
    val attributes = mutableMapOf("stroke" to stroke, "stroke-width" to strokeWidth!!)
    strokeDasharray?.let {
      attributes.put("stroke-dasharray", it)
    }

    if (deltaShadow > 0) {
      attributes.put("filter", "url(#$shadowId)")
    }

    var classNames = styleBuilder.add("l", attributes)
    if (fill != null) {
      classNames += " ${styleBuilder.add("b", mapOf("fill" to fill))}"
    }

    element.setAttribute("class", classNames)
  }

  fun svgPolygon(deltaShadow: Double, vararg points: Double) {
    assert(points.size % 2 == 0)
    manageShadow(deltaShadow)
    if (!hidden) {
      val elt = document.createElement("polygon") as Element
      val sb = StringBuilder()
      for (coord in points) {
        if (sb.isNotEmpty()) {
          sb.append(',')
        }
        sb.append(format(coord))
      }
      elt.setAttribute("points", sb.toString())
      applyStrokeStyle(elt, fill, deltaShadow)
      g.appendChild(elt)
    }
    var i = 0
    while (i < points.size) {
      ensureVisible(points[i] + 2 * deltaShadow, points[i + 1] + 2 * deltaShadow)
      i += 2
    }
  }

  fun text(
    text: String,
    x: Double,
    y: Double,
    fontFamily: String?,
    fontSize: Int,
    fontWeight: String?,
    fontStyle: String?,
    textDecoration: String?,
    textLength: Double,
    attributes: Map<String?, String?>,
    textBackColor: String?
  ) {
    var text = text
    var fontFamily = fontFamily
    if (!hidden) {
      val element = document.createElement("text") as Element
      element.setAttribute("x", format(x))
      element.setAttribute("y", format(y))
      val styleAttributes = linkedMapOf<String, String>()
      styleAttributes["fill"] = fill
      styleAttributes["font-size"] = format(fontSize.toDouble())
      element.setAttribute("lengthAdjust", "spacingAndGlyphs")
      element.setAttribute("textLength", format(textLength))
      if (fontWeight != null) {
        styleAttributes["font-weight"] = fontWeight
      }
      if (fontStyle != null) {
        styleAttributes["font-style"] = fontStyle
      }
      if (textDecoration != null) {
        styleAttributes["text-decoration"] = textDecoration
      }
      if (fontFamily != null) {
        // http://plantuml.sourceforge.net/qa/?qa=5432/svg-monospace-output-has-wrong-font-family
        if ("monospaced".equals(fontFamily, ignoreCase = true)) {
          fontFamily = "monospace"
        }
        styleAttributes["font-family"] = fontFamily
        if (fontFamily.equals("monospace", ignoreCase = true) || fontFamily.equals("courier", ignoreCase = true)) {
          text = text.replace(' ', 160.toChar())
        }
      }
      if (textBackColor != null) {
        element.setAttribute("filter", "url(#${getFilterBackColor(textBackColor)})")
      }
      for ((key, value) in attributes) {
        element.setAttribute(key, value)
      }

      val className = styleBuilder.add("t", styleAttributes)
      element.setAttribute("class", className)
      element.textContent = text
      g.appendChild(element)
    }
    ensureVisible(x, y)
    ensureVisible(x + textLength, y)
  }

  private val filterBackColor: MutableMap<String, String> = HashMap()
  private fun getIdFilterBackColor(color: String): String {
    var result = filterBackColor[color]
    if (result == null) {
      result = filterUid + filterBackColor.size
      filterBackColor[color] = result
    }
    return result
  }

  private fun getFilterBackColor(color: String): String? {
    var id = filterBackColor[color]
    if (id != null) {
      return id
    }
    id = getIdFilterBackColor(color)
    val filter = document.createElement("filter") as Element
    filter.setAttribute("id", id)
    filter.setAttribute("x", "0")
    filter.setAttribute("y", "0")
    filter.setAttribute("width", "1")
    filter.setAttribute("height", "1")
    addFilter(filter, "feFlood", "flood-color", color, "result", "flood")
    addFilter(filter, "feComposite", "in", "SourceGraphic", "in2", "flood", "operator", "over")
    defs!!.appendChild(filter)
    return id
  }

  private val transformer: Transformer
    get() {
      // Get a TransformerFactory object.
      val factory = TransformerFactory.newInstance()
      Log.info("TransformerFactory=${factory.javaClass}")

      // Get an XSL Transformer object.
      val transformer = factory.newTransformer()
      Log.info("Transformer=${transformer.javaClass}")

      // Sets the standalone property in the first line of the output file.
      transformer.setOutputProperty(OutputKeys.STANDALONE, "no")
      // transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, "SVG 1.1");
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
      transformer.setOutputProperty(OutputKeys.INDENT, "yes")
      transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
      return transformer
    }

  @Throws(TransformerException::class, IOException::class)
  fun createXml(os: OutputStream) {
    if (images.isEmpty()) {
      createXmlInternal(os)
      return
    }

    val byteOut = ByteArrayOutputStream()
    createXmlInternal(byteOut)
    var s = byteOut.toString(Charsets.UTF_8.name())
    for ((key, value) in images) {
      val k = "<$key/>"
      s = s.replace(k.toRegex(), value!!)
    }
    os.write(s.toByteArray())
  }

  @Throws(TransformerException::class)
  private fun createXmlInternal(outputStream: OutputStream) {
    // Get a DOMSource object that represents the Document object
    val source = DOMSource(document)
    val maxXscaled = (maxX * scale).toInt()
    val maxYscaled = (maxY * scale).toInt()
    var style = "width:" + maxXscaled + "px;height:" + maxYscaled + "px;"
    if (backcolor != null) {
      style += "background:$backcolor;"
    }
    if (svgDimensionStyle) {
      root.setAttribute("style", style)
      root.setAttribute("width", format(maxX.toDouble()) + "px")
      root.setAttribute("height", format(maxY.toDouble()) + "px")
    }
    root.setAttribute("viewBox", "0 0 $maxXscaled $maxYscaled")
    root.setAttribute("zoomAndPan", "magnify")
    root.setAttribute("preserveAspectRatio", preserveAspectRatio)
    root.setAttribute("contentScriptType", "application/ecmascript")
    root.setAttribute("contentStyleType", "text/css")
    if (pendingBackground != null) {
      pendingBackground!!.setAttribute("width", format(maxX.toDouble()))
      pendingBackground!!.setAttribute("height", format(maxY.toDouble()))
    }

    styleBuilder.applyStyle {
      val styleElement = simpleElement("style")
      defs!!.appendChild(styleElement)
      styleElement
    }

    // Get a StreamResult object that points to the
    // screen. Then transform the DOM sending XML to the screen.
    transformer.transform(source, StreamResult(outputStream))
  }

  fun svgPath(x: Double, y: Double, path: UPath, deltaShadow: Double) {
    manageShadow(deltaShadow)
    ensureVisible(x, y)
    val sb = StringBuilder()
    for (seg in path) {
      val type = seg.segmentType
      val coord = seg.coord
      if (type == USegmentType.SEG_MOVETO) {
        sb.append("M" + format(coord[0] + x) + "," + format(coord[1] + y) + " ")
        ensureVisible(coord[0] + x + 2 * deltaShadow, coord[1] + y + 2 * deltaShadow)
      }
      else if (type == USegmentType.SEG_LINETO) {
        sb.append("L" + format(coord[0] + x) + "," + format(coord[1] + y) + " ")
        ensureVisible(coord[0] + x + 2 * deltaShadow, coord[1] + y + 2 * deltaShadow)
      }
      else if (type == USegmentType.SEG_QUADTO) {
        sb.append(
          "Q" + format(coord[0] + x) + "," + format(coord[1] + y) + " " + format(coord[2] + x) + ","
          + format(coord[3] + y) + " "
        )
        ensureVisible(coord[0] + x + 2 * deltaShadow, coord[1] + y + 2 * deltaShadow)
        ensureVisible(coord[2] + x + 2 * deltaShadow, coord[3] + y + 2 * deltaShadow)
      }
      else if (type == USegmentType.SEG_CUBICTO) {
        sb.append(
          "C" + format(coord[0] + x) + "," + format(coord[1] + y) + " " + format(coord[2] + x) + ","
          + format(coord[3] + y) + " " + format(coord[4] + x) + "," + format(coord[5] + y) + " "
        )
        ensureVisible(coord[0] + x + 2 * deltaShadow, coord[1] + y + 2 * deltaShadow)
        ensureVisible(coord[2] + x + 2 * deltaShadow, coord[3] + y + 2 * deltaShadow)
        ensureVisible(coord[4] + x + 2 * deltaShadow, coord[5] + y + 2 * deltaShadow)
      }
      else if (type == USegmentType.SEG_ARCTO) {
        // A25,25 0,0 5,395,40
        sb.append(
          "A" + format(coord[0]) + "," + format(coord[1]) + " " + formatBoolean(coord[2]) + " "
          + formatBoolean(coord[3]) + " " + formatBoolean(coord[4]) + " " + format(coord[5] + x) + ","
          + format(coord[6] + y) + " "
        )
        ensureVisible(
          coord[5] + coord[0] + x + 2 * deltaShadow,
          coord[6] + coord[1] + y + 2 * deltaShadow
        )
      }
      else if (type == USegmentType.SEG_CLOSE) {
        // Nothing
      }
      else {
        Log.println("unknown3 $seg")
      }
    }
    if (!hidden) {
      val elt = document.createElement("path") as Element
      elt.setAttribute("d", sb.toString())
      applyStrokeStyle(elt, fill, deltaShadow)
      val id = path.comment
      if (id != null) {
        elt.setAttribute("id", id)
      }
      g.appendChild(elt)
    }
  }

  private var currentPath: StringBuilder? = null
  fun newpath() {
    currentPath = StringBuilder()
  }

  fun moveto(x: Double, y: Double) {
    currentPath!!.append("M" + format(x) + "," + format(y) + " ")
    ensureVisible(x, y)
  }

  fun lineto(x: Double, y: Double) {
    currentPath!!.append("L" + format(x) + "," + format(y) + " ")
    ensureVisible(x, y)
  }

  fun closepath() {
    currentPath!!.append("Z ")
  }

  fun curveto(
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
    x3: Double,
    y3: Double
  ) {
    currentPath!!.append(
      "C" + format(x1) + "," + format(y1) + " " + format(x2) + "," + format(y2) + " " + format(x3)
      + "," + format(y3) + " "
    )
    ensureVisible(x1, y1)
    ensureVisible(x2, y2)
    ensureVisible(x3, y3)
  }

  fun quadto(x1: Double, y1: Double, x2: Double, y2: Double) {
    currentPath!!.append("Q" + format(x1) + "," + format(y1) + " " + format(x2) + "," + format(y2) + " ")
    ensureVisible(x1, y1)
    ensureVisible(x2, y2)
  }

  private fun format(x: Double): String {
    return TikzGraphics.format(x * scale)
  }

  private fun formatBoolean(x: Double): String {
    return if (x == 0.0) "0" else "1"
  }

  fun fill(windingRule: Int) {
    if (!hidden) {
      val elt = document.createElement("path") as Element
      elt.setAttribute("d", currentPath.toString())
      // elt elt.setAttribute("style", getStyle());
      g.appendChild(elt)
    }
    currentPath = null
  }

  @Throws(IOException::class)
  fun svgImage(image: BufferedImage, x: Double, y: Double) {
    if (!hidden) {
      val elt = document.createElement("image") as Element
      elt.setAttribute("width", format(image.width.toDouble()))
      elt.setAttribute("height", format(image.height.toDouble()))
      elt.setAttribute("x", format(x))
      elt.setAttribute("y", format(y))
      val s = toBase64(image)
      elt.setAttribute("xlink:href", "data:image/png;base64,$s")
      g.appendChild(elt)
    }
    ensureVisible(x, y)
    ensureVisible(x + image.width, y + image.height)
  }

  private val images: MutableMap<String, String?> = HashMap()
  fun svgImage(image: SvgString, x: Double, y: Double) {
    if (!hidden) {
      var svg = manageScale(image)
      val pos = "<svg x=\"" + format(x) + "\" y=\"" + format(y) + "\">"
      svg = pos + svg.substring(5)
      val key = "imagesvginlined" + images.size
      val elt = document.createElement(key) as Element
      g.appendChild(elt)
      images[key] = svg
    }
    ensureVisible(x, y)
    ensureVisible(x + image.getData("width"), y + image.getData("height"))
  }

  private fun manageScale(svg: SvgString): String {
    val svgScale = svg.scale
    if (svgScale * scale == 1.0) {
      return svg.getSvg(false)
    }
    val s1 = "<g\\b"
    val s2 = "<g transform=\"scale(" + format(svgScale) + "," + format(svgScale) + ")\" "
    return svg.getSvg(false).replaceFirst(s1.toRegex(), s2)
  }

  @Throws(IOException::class)
  private fun toBase64(image: BufferedImage): String {
    val byteOut = ByteArrayOutputStream()
    ImageIO.write(image, "png", byteOut)
    return Base64.getEncoder().encodeToString(byteOut.toByteArray())
  }

  // Shadow
  private var withShadow = false
  private fun manageShadow(deltaShadow: Double) {
    if (deltaShadow == 0.0) {
      return
    }

    if (!withShadow) {
      // <filter id="f1" x="0" y="0" width="120%" height="120%">
      val filter = document.createElement("filter") as Element
      filter.setAttribute("id", shadowId)
      filter.setAttribute("x", "-1")
      filter.setAttribute("y", "-1")
      filter.setAttribute("width", "300%")
      filter.setAttribute("height", "300%")
      addFilter(filter, "feGaussianBlur", "result", "blurOut", "stdDeviation", "" + 2 * scale)
      addFilter(
        filter, "feColorMatrix", "type", "matrix", "in", "blurOut", "result", "blurOut2", "values",
        "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 .4 0"
      )
      addFilter(filter, "feOffset", "result", "blurOut3", "in", "blurOut2", "dx", "" + 4 * scale, "dy", "" + 4 * scale)
      addFilter(filter, "feBlend", "in", "SourceGraphic", "in2", "blurOut3", "mode", "normal")
      defs!!.appendChild(filter)
    }
    withShadow = true
  }

  private fun addFilter(filter: Element, name: String, vararg data: String) {
    assert(data.size % 2 == 0)
    val elt = document.createElement(name) as Element
    var i = 0
    while (i < data.size) {
      elt.setAttribute(data[i], data[i + 1])
      i += 2
    }
    filter.appendChild(elt)
  }

  private var hidden = false
  fun setHidden(hidden: Boolean) {
    this.hidden = hidden
  }

  fun addComment(comment: String) {
    val signature = getMD5Hex(comment)
    val result = "MD5=[$signature]\n$comment"
    g.appendChild(document.createComment(result))
  }
}