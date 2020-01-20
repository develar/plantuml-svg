package org.develar.plantuml

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import net.sourceforge.plantuml.*
import net.sourceforge.plantuml.error.PSystemError
import net.sourceforge.plantuml.preproc.Defines
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

fun main(args: Array<String>) {
  BuildSvg().main(args)
}

private class BuildSvg : CliktCommand() {
  private val file: String by option(help = "Path to dir or file.").prompt("PlantUML file")
  private val outDir: String by option(help = "Path to output directory.").default("out")

  override fun run() {
    buildSvg(file, outDir)
  }
}

fun buildSvg(inputFileOrDir: String, outDir: String) {
  val outDirectory = Paths.get(outDir).toAbsolutePath()
  Files.createDirectories(outDirectory)

  val f = Paths.get(inputFileOrDir).toAbsolutePath()
  if (Files.isDirectory(f)) {
    Files.newDirectoryStream(f) {
      val name = it.fileName.toString()
      name.endsWith(".puml", ignoreCase = true)
    }.use {
      for (path in it) {
        transform(path, outDirectory)
      }
    }
  }
  else {
    transform(f, outDirectory)
  }
}

val svgFileFormat = FileFormatOption(FileFormat.SVG, /* withMetadata = */ false)
var extraSvgStyleLines = listOf<String>()

private fun transform(file: Path, outDirectory: Path) {
  @Suppress("SpellCheckingInspection")
  val defines = Defines.createWithMap(mapOf(
    "filedate" to Files.getLastModifiedTime(file).toString(),
    "dirpath" to file.parent.toString().replace('\\', '/')
  ))
  val fileName = file.fileName.toString()
  defines.overrideFilename(fileName)

  val builder = BlockUmlBuilder(emptyList(), Charsets.UTF_8.name(), defines, Files.newBufferedReader(file), /* newCurrentDir = */ null, fileName)

  for (blockUml in builder.blockUmls) {
    val diagram = blockUml.diagram

    if (diagram is PSystemError) {
      System.err.println("status=ERROR")
      System.err.println("lineNumber=" + diagram.lineLocation.position)
      for (error in diagram.errorsUml) {
        System.err.println("label=" + error.error)
      }
      continue
    }

    val warnOrError = diagram.warningOrError
    if (warnOrError != null) {
      val out = System.err
      out.println("Start of $fileName")
      out.println(warnOrError)
      out.println("End of $fileName")
      out.println()
    }

    //val output = ByteArrayOutputStream()
    val outFile = outDirectory.resolve("${fileName.substring(0, fileName.lastIndexOf('.'))}.svg")
    println("Write $outFile")
    val output = Files.newOutputStream(outFile)
    output.use {
      diagram.exportDiagram(output, 0, svgFileFormat)
    }
  }
}