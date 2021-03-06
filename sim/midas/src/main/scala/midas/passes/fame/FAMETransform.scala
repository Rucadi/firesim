// See LICENSE for license details.

package midas.passes.fame

import java.io.{PrintWriter, File}

import firrtl._
import ir._
import Mappers._
import firrtl.Utils.{BoolType, kind, one}
import firrtl.passes.MemPortUtils
import firrtl.transforms.DontTouchAnnotation
import annotations._
import scala.collection.mutable
import mutable.{LinkedHashSet, LinkedHashMap}

import midas.passes._

/**************
 PRECONDITIONS:
 **************
 1.) Ports do not have aggregate types (easy to support if necessary)
 2.) There are no collisions among input/output channel names
 */

trait FAME1Channel {
  def name: String
  def direction: Direction
  def ports: Seq[Port]
  def isValid: Expression
  def asHostModelPort: Option[Port] = None
  def replacePortRef(wr: WRef): Expression
}

trait InputChannel {
  this: FAME1Channel =>
  val direction = Input
  val portName = s"${name}_sink"
  def setReady(readyCond: Expression): Statement
}

trait HasModelPort {
  this: FAME1Channel =>
  override def isValid = WSubField(WRef(asHostModelPort.get), "valid", BoolType)
  def isReady = WSubField(WRef(asHostModelPort.get), "ready", BoolType)
  def isFiring: Expression = And(isReady, isValid)
  def setReady(advanceCycle: Expression): Statement = Connect(NoInfo, isReady, advanceCycle)

  override def asHostModelPort: Option[Port] = {
    val tpe = FAMEChannelAnalysis.getHostDecoupledChannelType(name, ports)
    direction match {
      case Input => Some(Port(NoInfo, s"${name}_sink", Input, tpe))
      case Output => Some(Port(NoInfo, s"${name}_source", Output, tpe))
    }
  }

  def replacePortRef(wr: WRef): Expression = {
    val payload = WSubField(WRef(asHostModelPort.get), "bits")
    if (ports.size == 1) payload else WSubField(payload, FAMEChannelAnalysis.removeCommonPrefix(wr.name, name)._1)
  }
}

trait FAME1DataChannel extends FAME1Channel with HasModelPort {
  def clockDomainEnable: Expression
  def firedReg: DefRegister
  def isFired = WRef(firedReg)
  def isFiredOrFiring = Or(isFired, isFiring)
  def updateFiredReg(finishing: WRef): Statement = {
    Connect(NoInfo, isFired, Mux(finishing, Negate(clockDomainEnable), isFiredOrFiring, BoolType))
  }
}

case class FAME1ClockChannel(name: String, ports: Seq[Port]) extends FAME1Channel with InputChannel with HasModelPort

case class VirtualClockChannel(targetClock: Port) extends FAME1Channel with InputChannel {
  val name = "VirtualClockChannel"
  val ports = Seq(targetClock)
  val isValid: Expression = UIntLiteral(1)
  def setReady(advanceCycle: Expression): Statement = EmptyStmt
  def replacePortRef(wr: WRef): Expression = UIntLiteral(1)
}

case class FAME1InputChannel(
  name: String,
  clockDomainEnable: Expression,
  ports: Seq[Port],
  firedReg: DefRegister) extends FAME1DataChannel with InputChannel {
  override def setReady(advanceCycle: Expression): Statement = {
    Connect(NoInfo, isReady, And(advanceCycle, Negate(isFired)))
  }
}

case class FAME1OutputChannel(
  name: String,
  clockDomainEnable: Expression,
  ports: Seq[Port],
  firedReg: DefRegister) extends FAME1DataChannel {
  val direction = Output
  val portName = s"${name}_source"
  def setValid(finishing: WRef, ccDeps: Iterable[FAME1InputChannel]): Statement = {
    Connect(NoInfo, isValid, And.reduce(ccDeps.map(_.isValid).toSeq :+ Negate(isFired)))
  }
}

// Multi-clock timestep:
// When finishing is high, dequeue token from clock channel
// - Use to initialize isFired for all channels (with negation)
// - Finishing is gated with clock channel valid
object FAMEModuleTransformer {
  def apply(m: Module, analysis: FAMEChannelAnalysis): Module = {
    // Step 0: Bookkeeping for port structure conventions
    implicit val ns = Namespace(m)
    val mTarget = ModuleTarget(analysis.circuit.main, m.name)
    val clocks: Seq[Port] = m.ports.filter(_.tpe == ClockType)
    val portsByName = m.ports.map(p => p.name -> p).toMap
    assert(clocks.length >= 1)

    // Multi-clock management step 1: Add host clock + reset ports, finishing wire
    // TODO: Should finishing be a WrappedComponent?
    // TODO: Avoid static naming convention.
    val hostReset = Port(NoInfo, WrapTop.hostResetName, Input, BoolType)
    val hostClock = Port(NoInfo, WrapTop.hostClockName, Input, ClockType)
    val finishing = DefWire(NoInfo, "targetCycleFinishing", BoolType)
    assert(ns.tryName(hostReset.name) && ns.tryName(hostClock.name) && ns.tryName(finishing.name))
    def hostFlagReg(suggestName: String, resetVal: UIntLiteral = UIntLiteral(0)): DefRegister = {
      DefRegister(NoInfo, ns.newName(suggestName), BoolType, WRef(hostClock), WRef(hostReset), resetVal)
    }

    // Multi-clock management step 2: Build clock flags and clock channel
    def isClockChannel(info: (String, (Option[Port], Seq[Port]))) = info match {
      case (_, (clk, ports)) => clk.isEmpty && ports.forall(_.tpe == ClockType)
    }


    val clockChannel = analysis.modelInputChannelPortMap(mTarget).find(isClockChannel) match {
      case Some((name, (None, ports))) => FAME1ClockChannel(name, ports)
      case Some(_) => ??? // Clock channel cannot have an associated clock domain
      case None => VirtualClockChannel(clocks.head) // Virtual clock channel for single-clock models
    }

    /*
     *  NB: Failing to keep the target clock-gated during FPGA initialization
     *  can lead to spurious updates or metastability in target state elements.
     *  Keeping all target-clocks gated through the latter stages of FPGA
     *  initialization and reset ensures all target state elements are
     *  initialized with a deterministic set of initial values.
     */
    val nReset = DoPrim(PrimOps.Not, Seq(WRef(hostReset)), Seq.empty, BoolType)

    // Multi-clock management step 4: Generate clock buffers for all target clocks
    val targetClockBufs: Seq[SignalInfo] = clockChannel.ports.map { en =>
      val enableReg = hostFlagReg(s"${en.name}_enabled", resetVal = UIntLiteral(1))
      val buf = WDefInstance(ns.newName(s"${en.name}_buffer"), DefineAbstractClockGate.blackbox.name)
      val clockFlag = DoPrim(PrimOps.AsUInt, Seq(clockChannel.replacePortRef(WRef(en))), Nil, BoolType)
      val connects = Block(Seq(
        Connect(NoInfo, WRef(enableReg), Mux(WRef(finishing), clockFlag, WRef(enableReg), BoolType)),
        Connect(NoInfo, WSubField(WRef(buf), "I"), WRef(hostClock)),
        Connect(NoInfo, WSubField(WRef(buf), "CE"), And(And(WRef(enableReg), WRef(finishing)), nReset))))
      SignalInfo(Block(Seq(enableReg, buf)), connects, WSubField(WRef(buf), "O", ClockType, SourceFlow))
    }

    // Multi-clock management step 5: Generate target clock substitution map
    def asWE(p: Port) = WrappedExpression.we(WRef(p))
    val replaceClocksMap = (clockChannel.ports.map(p => asWE(p)) zip targetClockBufs.map(_.ref)).toMap

    // LI-BDN transformation step 1: Build channels
    // TODO: get rid of the analysis calls; we just need connectivity & annotations
    val portDeps = analysis.connectivity(m.name)

    def genMetadata(info: (String, (Option[Port], Seq[Port]))) = info match {
      case (cName, (Some(clock), ports)) =>
        // must be driven by one clock input port
        // TODO: this should not include muxes in connectivity!
        val srcClockPorts = portDeps.getEdges(clock.name).map(portsByName(_))
        assert(srcClockPorts.size == 1)
        val clockRef = WRef(srcClockPorts.head)
        val clockFlag = DoPrim(PrimOps.AsUInt, Seq(clockChannel.replacePortRef(clockRef)), Nil, BoolType)
        val firedReg = hostFlagReg(suggestName = ns.newName(s"${cName}_fired"))
        (cName, clockFlag, ports, firedReg)
      case (cName, (None, ports)) => clockChannel match {
        case vc: VirtualClockChannel =>
          val firedReg = hostFlagReg(suggestName = ns.newName(s"${cName}_fired"))
          (cName, UIntLiteral(1), ports, firedReg)
        case _ =>
          throw new RuntimeException(s"Channel ${cName} has no associated clock.")
      }
    }

    // LinkedHashMap.from is 2.13-only :(
    def stableMap[K, V](contents: Iterable[(K, V)]) = new LinkedHashMap[K, V] ++= contents

    // Have to filter out the clock channel from the input channels
    val inChannelInfo = analysis.modelInputChannelPortMap(mTarget).filterNot(isClockChannel(_)).toSeq
    val inChannelMetadata = inChannelInfo.map(genMetadata(_))
    val inChannels = inChannelMetadata.map((FAME1InputChannel.apply _).tupled)
    val inChannelMap = stableMap(inChannels.flatMap(c => c.ports.map(p => p.name -> c)))

    val outChannelInfo = analysis.modelOutputChannelPortMap(mTarget).toSeq
    val outChannelMetadata = outChannelInfo.map(genMetadata(_))
    val outChannels = outChannelMetadata.map((FAME1OutputChannel.apply _).tupled)
    val outChannelMap = stableMap(outChannels.flatMap(c => c.ports.map(p => p.name -> c)))

    // LI-BDN transformation step 2: find combinational dependencies among channels
    val ccDeps = new LinkedHashMap[FAME1OutputChannel, LinkedHashSet[FAME1InputChannel]]
    portDeps.getEdgeMap.collect({ case (o, iSet) if outChannelMap.contains(o) =>
      // Only add input channels, since output might depend on output RHS ref
      ccDeps.getOrElseUpdate(outChannelMap(o), new LinkedHashSet[FAME1InputChannel]) ++= iSet.flatMap(inChannelMap.get(_))
    })

    // LI-BDN transformation step 3: transform ports (includes new clock ports)
    val transformedPorts = hostClock +: hostReset +: (clockChannel +: inChannels ++: outChannels).flatMap(_.asHostModelPort)

    // LI-BDN transformation step 4: replace port and clock references and gate state updates
    val clockChannelPortNames = clockChannel.ports.map(_.name).toSet
    def onExpr(expr: Expression): Expression = expr.map(onExpr) match {
      case iWR @ WRef(name, tpe, PortKind, SourceFlow) if tpe != ClockType =>
        // Generally SourceFlow references to ports will be input channels, but RTL may use
        // an assignment to an output port as something akin to a wire, so check output ports too.
        inChannelMap.getOrElse(name, outChannelMap(name)).replacePortRef(iWR)
      case oWR @ WRef(name, tpe, PortKind, SinkFlow) if tpe != ClockType =>
        outChannelMap(name).replacePortRef(oWR)
      case cWR @ WRef(name, ClockType, PortKind, SourceFlow) if clockChannelPortNames(name) =>
        replaceClocksMap(WrappedExpression.we(cWR))
      case e => e map onExpr
    }

    def onStmt(stmt: Statement): Statement = stmt match {
      case Connect(info, WRef(name, ClockType, PortKind, flow), rhs) =>
        // Don't substitute gated clock for LHS expressions
        Connect(info, WRef(name, ClockType, WireKind, flow), onExpr(rhs))
      case s => s map onStmt map onExpr
    }

    val updatedBody = onStmt(m.body)

    // LI-BDN transformation step 5: add firing rules for output channels, trigger end of cycle
    // This is modified for multi-clock, as each channel fires only when associated clock is enabled
    val allFiredOrFiring = And.reduce(outChannels.map(_.isFiredOrFiring) ++ inChannels.map(_.isValid))

    val channelStateRules = (inChannels ++ outChannels).map(c => c.updateFiredReg(WRef(finishing)))
    val inputRules = inChannels.map(i => i.setReady(WRef(finishing)))
    val outputRules = outChannels.map(o => o.setValid(WRef(finishing), ccDeps(o)))
    val topRules = Seq(clockChannel.setReady(allFiredOrFiring),
      Connect(NoInfo, WRef(finishing), And(allFiredOrFiring, clockChannel.isValid)))

    // Keep output clock ports around as wires just for convenience to keep connects legal
    val clockOutputsAsWires = m.ports.collect { case Port(i, n, Output, ClockType) => DefWire(i, n, ClockType) }

    // Statements have to be conservatively ordered to satisfy declaration order
    val decls = finishing +: clockOutputsAsWires ++: targetClockBufs.map(_.decl) ++: (inChannels ++ outChannels).map(_.firedReg)
    val assigns = targetClockBufs.map(_.assigns) ++ channelStateRules ++ inputRules ++ outputRules ++ topRules
    Module(m.info, m.name, transformedPorts, Block(decls ++: updatedBody +: assigns))
  }
}

class FAMETransform extends Transform {
  def inputForm = LowForm
  def outputForm = HighForm

  def updateNonChannelConnects(analysis: FAMEChannelAnalysis)(stmt: Statement): Statement = stmt.map(updateNonChannelConnects(analysis)) match {
    case wi: WDefInstance if (analysis.transformedModules.contains(analysis.moduleTarget(wi))) =>
      val clockConn = Connect(NoInfo, WSubField(WRef(wi), WrapTop.hostClockName), WRef(analysis.hostClock.ref, ClockType))
      val resetConn = Connect(NoInfo, WSubField(WRef(wi), WrapTop.hostResetName), WRef(analysis.hostReset.ref, BoolType))
      Block(Seq(wi, clockConn, resetConn))
    case Connect(_, lhs, rhs) if (lhs.tpe == ClockType) => EmptyStmt // drop ancillary clock connects
    case Connect(_, WRef(name, _, _, _), _) if (analysis.staleTopPorts.contains(analysis.topTarget.ref(name))) => EmptyStmt
    case Connect(_, _, WRef(name, _, _, _)) if (analysis.staleTopPorts.contains(analysis.topTarget.ref(name))) => EmptyStmt
    case s => s
  }

  def hostDecouplingRenames(analysis: FAMEChannelAnalysis): RenameMap = {
    // Handle renames at the top-level to new channelized names
    val renames = RenameMap()
    val sinkRenames = analysis.transformedSinks.flatMap({c =>
      if (analysis.sinkPorts(c).size == 1)
        analysis.sinkPorts(c).map(rt => (rt, rt.copy(ref = s"${c}_sink").field("bits")))
      else
        analysis.sinkPorts(c).map(rt => (rt, rt.copy(ref = s"${c}_sink").field("bits").field(FAMEChannelAnalysis.removeCommonPrefix(rt.ref, c)._1)))
    })
    val sourceRenames = analysis.transformedSources.flatMap({c =>
      if (analysis.sourcePorts(c).size == 1)
        analysis.sourcePorts(c).map(rt => (rt, rt.copy(ref = s"${c}_source").field("bits")))
      else
        analysis.sourcePorts(c).map(rt => (rt, rt.copy(ref = s"${c}_source").field("bits").field(FAMEChannelAnalysis.removeCommonPrefix(rt.ref, c)._1)))
    })

    def renamePorts(suffix: String, lookup: ModuleTarget => Map[String, (Option[Port], Seq[Port])])
                   (mT: ModuleTarget): Seq[(ReferenceTarget, ReferenceTarget)] = {
        lookup(mT).toSeq.flatMap({ case (cName, (clockOption, pList)) =>
          pList.map({ port =>
            val decoupledTarget = mT.ref(s"${cName}${suffix}").field("bits")
            if (pList.size == 1)
              (mT.ref(port.name), decoupledTarget)
            else
              (mT.ref(port.name), decoupledTarget.field(FAMEChannelAnalysis.removeCommonPrefix(port.name, cName)._1))
          })
          // TODO: rename clock to nothing, since it is deleted
        })
    }
    def renameModelInputs: ModuleTarget => Seq[(ReferenceTarget, ReferenceTarget)] = renamePorts("_sink", analysis.modelInputChannelPortMap)
    def renameModelOutputs: ModuleTarget => Seq[(ReferenceTarget, ReferenceTarget)] = renamePorts("_source", analysis.modelOutputChannelPortMap)

    val modelPortRenames = analysis.transformedModules.flatMap(renameModelInputs) ++
                           analysis.transformedModules.flatMap(renameModelOutputs)

    (sinkRenames ++ sourceRenames ++ modelPortRenames).foreach({ case (old, decoupled) => renames.record(old, decoupled) })
    renames
  }

  def staleTopPort(p: Port, analysis: FAMEChannelAnalysis): Boolean = p match {
    case Port(_, name, _, ClockType) => name != WrapTop.hostClockName
    case Port(_, name, _, _) => analysis.staleTopPorts.contains(analysis.topTarget.ref(name))
  }

  def transformTop(top: DefModule, analysis: FAMEChannelAnalysis): Module = top match {
    case Module(info, name, ports, body) =>
      val transformedPorts = ports.filterNot(p => staleTopPort(p, analysis)) ++ 
        analysis.transformedSinks.map(c => Port(NoInfo, s"${c}_sink", Input, analysis.getSinkHostDecoupledChannelType(c))) ++
        analysis.transformedSources.map(c => Port(NoInfo, s"${c}_source", Output, analysis.getSourceHostDecoupledChannelType(c)))
      val transformedStmts = Seq(body.map(updateNonChannelConnects(analysis))) ++
        analysis.transformedSinks.map({c => Connect(NoInfo, analysis.wsubToSinkPort(c), WRef(s"${c}_sink"))}) ++
        analysis.transformedSources.map({c => Connect(NoInfo, WRef(s"${c}_source"), analysis.wsubToSourcePort(c))})
      Module(info, name, transformedPorts, Block(transformedStmts))
  }

  override def execute(state: CircuitState): CircuitState = {
    val c = state.circuit
    val analysis = new FAMEChannelAnalysis(state, FAME1Transform)
    // TODO: pick a value that does not collide
    implicit val triggerName = "finishing"

    val toTransform = analysis.transformedModules
    val transformedModules = c.modules.map {
      case m: Module if (m.name == c.main) => transformTop(m, analysis)
      case m: Module if (toTransform.contains(ModuleTarget(c.main, m.name))) => FAMEModuleTransformer(m, analysis)
      case m => m // TODO (Albert): revisit this; currently, not transforming nested modules
    }

    val filteredAnnos = state.annotations.filter {
      case DontTouchAnnotation(rt) if toTransform.contains(rt.moduleTarget) => false
      case _ => true
    }

    val newCircuit = c.copy(modules = transformedModules)
    CircuitState(newCircuit, outputForm, filteredAnnos, Some(hostDecouplingRenames(analysis)))
  }
}
