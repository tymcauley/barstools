// See LICENSE for license details
package barstools.floorplan.firrtl

import barstools.floorplan.{FloorplanSerialization, FloorplanElementRecord, FloorplanState}
import firrtl.{CircuitState, Transform, DependencyAPIMigration, VerilogEmitter, AnnotationSeq}
import firrtl.options.{Dependency, RegisteredTransform, ShellOption}
import firrtl.analyses.{IRLookup}
import firrtl.annotations.{InstanceTarget, ReferenceTarget, ModuleTarget, Target, IsComponent}
import firrtl.annotations.TargetToken.{Instance, OfModule}

// NOTE: If you rename/add this transform, don't forget to update META-INF
// See the @note in the RegisteredTransform documentation
class GenerateFloorplanIRPass extends Transform with RegisteredTransform with DependencyAPIMigration {

  override def prerequisites = Seq(Dependency[VerilogEmitter])
  override def optionalPrerequisites = Nil
  override def dependents = Nil
  override def invalidates(xform: Transform) = false

  val options = Seq(
    new ShellOption[String](
      longOption = "floorplan-ir-file",
      toAnnotationSeq = (a: String) => Seq(FloorplanIRFileAnnotation(a)),
      helpText = s"Set the floorplan IR file name"
    )
  )

  def execute(state: CircuitState): CircuitState = {

    def getInstancePath(t: Option[InstanceTarget]): String = t map { it =>
      (Seq(it.module) ++ it.asPath.toList.map(_._1.value)).mkString("/")
    } getOrElse state.circuit.main

    def getRelativePath(root: Option[InstanceTarget], inst: Option[IsComponent]): String = {
      val rootPath = root.map(_.asPath).getOrElse(Seq())
      val instPath = inst.map(_.asPath).getOrElse(Seq())
      assert(instPath.take(rootPath.length) == rootPath, s"InstanceTarget ${instPath} must be inside ${rootPath}")
      val pathStr = instPath.drop(rootPath.length).toList.map(_._1.value).mkString("/")
      inst.map(_ match {
        case x: InstanceTarget => pathStr
        case x: ReferenceTarget => pathStr + "." + x.ref
        case _ => ??? // Shouldn't exist
      }) getOrElse ""
    }

    def newRecord(path: String, ref: Option[String], ofModule: Option[String], anno: FloorplanAnnotation) =
      FloorplanElementRecord(path, ref, ofModule, FloorplanSerialization.deserialize(anno.fpir))

    val list = state.annotations.collect({
      case x: NoReferenceFloorplanAnnotation =>
        val (rootTarget, ofModule) = x.target match {
          case y: InstanceTarget => (Some(y), Some(y.ofModule))
          case y: ModuleTarget =>
            assert(y.module == state.circuit.main, "ModuleTarget is only supported for the top module")
            (Option.empty[InstanceTarget], Some(y.module))
          case _ => ???
        }
        newRecord(getInstancePath(rootTarget), None, ofModule, x)
      case x: InstanceFloorplanAnnotation if x.targets.flatten.length == 2 =>
        val rootTarget = x.targets(0)(0) match {
          case y: InstanceTarget => Some(y)
          case y: ModuleTarget =>
            assert(y.module == state.circuit.main, "ModuleTarget is only supported for the top module")
            Option.empty[InstanceTarget]
          case _ => ???
        }
        val (instTarget, ofModule) = x.targets(1)(0) match {
          case y: InstanceTarget => (Some(y), Some(y.ofModule))
          case y: ModuleTarget =>
            assert(y.module == state.circuit.main, "ModuleTarget is only supported for the top module")
            (Option.empty[InstanceTarget], Some(y.module))
          case _ => ???
        }
        newRecord(getInstancePath(rootTarget), Some(getRelativePath(rootTarget, instTarget)), ofModule, x)
      case x: MemFloorplanAnnotation if x.targets.flatten.length == 2 =>
        val rootTarget = x.targets(0)(0) match {
          case y: InstanceTarget => Some(y)
          case y: ModuleTarget =>
            assert(y.module == state.circuit.main, "ModuleTarget is only supported for the top module")
            Option.empty[InstanceTarget]
          case _ => ???
        }
        val refTarget = x.targets(1)(0).asInstanceOf[ReferenceTarget]
        // Note: This assumes specific behavior from ReplSeqMem, namely that it replaces the Mem reference with
        // a wrapper instance named ${ext} that instantiates an external bbox named ${ext}_ext
        val mem = IRLookup(state.circuit).declaration(refTarget) match {
          case m: firrtl.ir.DefInstance => m.module
          case _ => throw new Exception("Something went wrong, Mems should become ExtModule instances")
        }
        val ext = mem+"_ext"
        // TODO do we want to replace this in the output annotation file... ?
        val newTarget = InstanceTarget(
          circuit=refTarget.circuit,
          module=refTarget.module,
          instance=ext,
          ofModule=ext,
          path=refTarget.path :+ (Instance(refTarget.ref), OfModule(mem)))
        newRecord(getInstancePath(rootTarget), Some(getRelativePath(rootTarget, Some(newTarget))), Some(ext), x)
    })

    val filename = state.annotations.collectFirst({
      case x: FloorplanIRFileAnnotation => x.value
    }).getOrElse {
      val opt = options.head.longOption
      throw new Exception(s"Did not specify a filename for GenerateFloorplanIRPass. Please provide a FloorplanIRFileAnnotation or use the --${opt} option.")
    }
    val writer = new java.io.FileWriter(filename)
    writer.write(FloorplanState.serialize(list))
    writer.close()

    state
  }
}

