package barstools.tapeout.transforms

import firrtl._
import firrtl.ir._
import firrtl.annotations._
import firrtl.passes.Pass

import java.io.File
import firrtl.annotations.AnnotationYamlProtocol._
import firrtl.passes.memlib.ReplSeqMemAnnotation
import firrtl.transforms.BlackBoxResourceFileNameAnno
import net.jcazevedo.moultingyaml._
import com.typesafe.scalalogging.LazyLogging

trait HasTapeoutOptions { self: ExecutionOptionsManager with HasFirrtlOptions =>
  var tapeoutOptions = TapeoutOptions()

  parser.note("tapeout options")

  parser.opt[String]("harness-o")
    .abbr("tho")
    .valueName("<harness-output>")
    .foreach { x =>
      tapeoutOptions = tapeoutOptions.copy(
        harnessOutput = Some(x)
      )
    }.text {
      "use this to generate a harness at <harness-output>"
    }

  parser.opt[String]("syn-top")
    .abbr("tst")
    .valueName("<syn-top>")
    .foreach { x =>
      tapeoutOptions = tapeoutOptions.copy(
        synTop = Some(x)
      )
    }.text {
      "use this to set synTop"
    }

  parser.opt[String]("top-fir")
    .abbr("tsf")
    .valueName("<top-fir>")
    .foreach { x =>
      tapeoutOptions = tapeoutOptions.copy(
        topFir = Some(x)
      )
    }.text {
      "use this to set topFir"
    }

  parser.opt[String]("top-anno-out")
    .abbr("tsaof")
    .valueName("<top-anno-out>")
    .foreach { x =>
      tapeoutOptions = tapeoutOptions.copy(
        topAnnoOut = Some(x)
      )
    }.text {
      "use this to set topAnnoOut"
    }

  parser.opt[String]("top-dotf-out")
    .abbr("tdf")
    .valueName("<top-dotf-out>")
    .foreach { x =>
      tapeoutOptions = tapeoutOptions.copy(
        topDotfOut = Some(x)
      )
    }.text {
      "use this to set the filename for the top resource .f file"
    }

  parser.opt[String]("harness-top")
    .abbr("tht")
    .valueName("<harness-top>")
    .foreach { x =>
      tapeoutOptions = tapeoutOptions.copy(
        harnessTop = Some(x)
      )
    }.text {
      "use this to set harnessTop"
    }

  parser.opt[String]("harness-fir")
    .abbr("thf")
    .valueName("<harness-fir>")
    .foreach { x =>
      tapeoutOptions = tapeoutOptions.copy(
        harnessFir = Some(x)
      )
    }.text {
      "use this to set harnessFir"
    }

  parser.opt[String]("harness-anno-out")
    .abbr("thaof")
    .valueName("<harness-anno-out>")
    .foreach { x =>
      tapeoutOptions = tapeoutOptions.copy(
        harnessAnnoOut = Some(x)
      )
    }.text {
      "use this to set harnessAnnoOut"
    }

  parser.opt[String]("harness-dotf-out")
    .abbr("hdf")
    .valueName("<harness-dotf-out>")
    .foreach { x =>
      tapeoutOptions = tapeoutOptions.copy(
        harnessDotfOut = Some(x)
      )
    }.text {
      "use this to set the filename for the harness resource .f file"
    }

  parser.opt[String]("harness-conf")
    .abbr("thconf")
    .valueName ("<harness-conf-file>")
    .foreach { x =>
      tapeoutOptions = tapeoutOptions.copy(
        harnessConf = Some(x)
      )
    }.text {
      "use this to set the harness conf file location"
    }

}

case class TapeoutOptions(
  harnessOutput: Option[String] = None,
  synTop: Option[String] = None,
  topFir: Option[String] = None,
  topAnnoOut: Option[String] = None,
  topDotfOut: Option[String] = None,
  harnessTop: Option[String] = None,
  harnessFir: Option[String] = None,
  harnessAnnoOut: Option[String] = None,
  harnessDotfOut: Option[String] = None,
  harnessConf: Option[String] = None
) extends LazyLogging

// Requires two phases, one to collect modules below synTop in the hierarchy
// and a second to remove those modules to generate the test harness
sealed trait GenerateTopAndHarnessApp extends LazyLogging { this: App =>
  lazy val optionsManager = {
    val optionsManager = new ExecutionOptionsManager("tapeout") with HasFirrtlOptions with HasTapeoutOptions
    if (!optionsManager.parse(args)) {
      throw new Exception("Error parsing options!")
    }
    optionsManager
  }
  lazy val tapeoutOptions = optionsManager.tapeoutOptions
  // Tapeout options
  lazy val synTop = tapeoutOptions.synTop
  lazy val harnessTop = tapeoutOptions.harnessTop
  lazy val firrtlOptions = optionsManager.firrtlOptions
  // FIRRTL options
  lazy val annoFiles = firrtlOptions.annotationFileNames

  lazy val topTransforms: Seq[Transform] = {
    Seq(
      new ReParentCircuit(synTop.get),
      new RemoveUnusedModules
    )
  }

  lazy val topOptions = firrtlOptions.copy(
    customTransforms = firrtlOptions.customTransforms ++ topTransforms,
    annotations = firrtlOptions.annotations ++ tapeoutOptions.topDotfOut.map(BlackBoxResourceFileNameAnno(_))
  )

  class AvoidExtModuleCollisions(mustLink: Seq[ExtModule]) extends Transform {
    def inputForm = HighForm
    def outputForm = HighForm
    def execute(state: CircuitState): CircuitState = {
      state.copy(circuit = state.circuit.copy(modules = state.circuit.modules ++ mustLink))
    }
  }

  private def harnessTransforms(topExtModules: Seq[ExtModule]): Seq[Transform] = {
    // XXX this is a hack, we really should be checking the masters to see if they are ExtModules
    val externals = Set(harnessTop.get, synTop.get, "SimSerial", "SimDTM")
    Seq(
      new ConvertToExtMod((m) => m.name == synTop.get),
      new RemoveUnusedModules,
      new AvoidExtModuleCollisions(topExtModules),
      new RenameModulesAndInstances((old) => if (externals contains old) old else (old + "_in" + harnessTop.get))
    )
  }

  // Dump firrtl and annotation files
  protected def dump(res: FirrtlExecutionSuccess, firFile: Option[String], annoFile: Option[String]): Unit = {
    firFile.foreach { firPath =>
      val outputFile = new java.io.PrintWriter(firPath)
      outputFile.write(res.circuitState.circuit.serialize)
      outputFile.close()
    }
    annoFile.foreach { annoPath =>
      val outputFile = new java.io.PrintWriter(annoPath)
      outputFile.write(JsonProtocol.serialize(res.circuitState.annotations.filter(_ match {
        case EmittedVerilogCircuitAnnotation(_) => false
        case _ => true
      })))
      outputFile.close()
    }
  }

  // Top Generation
  protected def executeTop(): Seq[ExtModule] = {
    optionsManager.firrtlOptions = topOptions
    val result = firrtl.Driver.execute(optionsManager)
    result match {
      case x: FirrtlExecutionSuccess =>
        dump(x, tapeoutOptions.topFir, tapeoutOptions.topAnnoOut)
        x.circuitState.circuit.modules.collect{ case e: ExtModule => e }
      case _ =>
        throw new Exception("executeTop failed on illegal FIRRTL input!")
    }
  }

  // Top and harness generation
  protected def executeTopAndHarness(): Unit = {
    // Execute top and get list of ExtModules to avoid collisions
    val topExtModules = executeTop()

    // For harness run, change some firrtlOptions (below) for harness phase
    // customTransforms: setup harness transforms, add AvoidExtModuleCollisions
    // outputFileNameOverride: change to harnessOutput
    // conf file must change to harnessConf by mapping annotations
    optionsManager.firrtlOptions = firrtlOptions.copy(
      customTransforms = firrtlOptions.customTransforms ++ harnessTransforms(topExtModules),
      outputFileNameOverride = tapeoutOptions.harnessOutput.get,
      annotations = firrtlOptions.annotations.map({
        case ReplSeqMemAnnotation(i, o) => ReplSeqMemAnnotation(i, tapeoutOptions.harnessConf.get)
        case a => a
      }) ++ tapeoutOptions.harnessDotfOut.map(BlackBoxResourceFileNameAnno(_))
    )

    val harnessResult = firrtl.Driver.execute(optionsManager)
    harnessResult match {
      case x: FirrtlExecutionSuccess => dump(x, tapeoutOptions.harnessFir, tapeoutOptions.harnessAnnoOut)
      case _ =>
    }
  }
}

object GenerateTop extends App with GenerateTopAndHarnessApp {
  // Only need a single phase to generate the top module
  executeTop()
}

object GenerateTopAndHarness extends App with GenerateTopAndHarnessApp {
  executeTopAndHarness()
}
