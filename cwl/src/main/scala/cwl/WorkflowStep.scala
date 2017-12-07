package cwl

import cats.Monoid
import cats.data.NonEmptyList
import cats.data.Validated._
import cats.instances.list._
import cats.syntax.either._
import cats.syntax.foldable._
import cats.syntax.monoid._
import cats.syntax.validated._
import common.Checked
import common.validation.Checked._
import common.validation.ErrorOr.ErrorOr
import cwl.ScatterLogic.ScatterVariablesPoly
import cwl.ScatterMethod._
import cwl.WorkflowStep.{WorkflowStepInputFold, _}
import shapeless._
import wom.callable.Callable._
import wom.graph.CallNode._
import wom.graph.GraphNodePort.{GraphNodeOutputPort, OutputPort}
import wom.graph._
import wom.graph.expression.ExpressionNode
import wom.types.WomArrayType
import wom.values.WomValue

import scala.language.postfixOps
import scala.util.Try

/**
  * An individual job to run.
  *
  * @see <a href="http://www.commonwl.org/v1.0/Workflow.html#WorkflowStep">CWL Spec | Workflow Step</a>
  * @param run Purposefully not defaulted as it's required in the specification and it is unreasonable to not have something to run.
  */
case class WorkflowStep(
                         id: String,
                         in: Array[WorkflowStepInput] = Array.empty,
                         out: Outputs,
                         run: Run,
                         requirements: Option[Array[Requirement]] = None,
                         hints: Option[Array[CwlAny]] = None,
                         label: Option[String] = None,
                         doc: Option[String] = None,
                         scatter: Option[String :+: Array[String] :+: CNil] = None,
                         scatterMethod: Option[ScatterMethod] = None) {

  private def isScattered: Boolean = scatter.isDefined

  def typedOutputs: WdlTypeMap = run.fold(RunOutputsToTypeMap)

  def fileName: Option[String] = run.select[String]

  val unqualifiedStepId = WomIdentifier(Try(FullyQualifiedName(id)).map(_.id).getOrElse(id))

  /**
    * Generates all GraphNodes necessary to represent call nodes and input nodes
    * Recursive because dependencies are discovered as we iterate through inputs and corresponding
    * upstream nodes need to be generated on the fly.
    */
  def callWithInputs(typeMap: WdlTypeMap,
                     workflow: Workflow,
                     knownNodes: Set[GraphNode],
                     workflowInputs: Map[String, GraphNodeOutputPort]): Checked[Set[GraphNode]] = {

    // To avoid duplicating nodes, return immediately if we've already covered this node
    val haveWeSeenThisStep: Boolean = knownNodes.collect { case TaskCallNode(identifier, _, _, _) => identifier }.contains(unqualifiedStepId)

    if (haveWeSeenThisStep) Right(knownNodes)
    else {
      // Create a task definition for the underlying run.
      // For sub workflows, we'll need to handle the case where this could be a workflow definition
      //TODO: turn this select into a fold that supports other types of runnables
      val taskDefinition = run.select[CommandLineTool].map { _.taskDefinition } get

      val callNodeBuilder = new CallNode.CallNodeBuilder()

      /*
       * Method used to fold over the list of inputs declared by this step.
       * Note that because we work on saladed CWL, all ids are fully qualified at this point (e.g: file:///path/to/file/three_step.cwl#cgrep/pattern
       * The goal of this method is two fold (pardon the pun):
       *   1) link each input of the step to an output port (which at this point can be from a different step or from a workflow input)
       *   2) accumulate the nodes created along the way to achieve 1)
       */
      def foldStepInput(currentFold: Checked[WorkflowStepInputFold], workflowStepInput: WorkflowStepInput): Checked[WorkflowStepInputFold] = currentFold flatMap {
        fold =>
          // The source from which we expect to satisfy this input (output from other step or workflow input)
          // TODO: this can be None, a single source, or multiple sources. Currently assuming it's a single one
          val inputSource: String = workflowStepInput.source.flatMap(_.select[String]).get

          // Name of the step input

          val accumulatedNodes = fold.generatedNodes ++ knownNodes

          /*
            * Try to find in the given set an output port named stepOutputId in a call node named stepId
            * This is useful when we've determined that the input points to an output of a different step and we want
            * to get the corresponding output port.
           */
          def findThisInputInSet(set: Set[GraphNode], stepId: String, stepOutputId: String): Checked[OutputPort] = {
            for {
            // We only care for outputPorts of call nodes
              call <- set.collectFirst { case callNode: CallNode if callNode.localName == stepId => callNode }.
                toRight(NonEmptyList.one(s"stepId $stepId not found in known Nodes $set"))
              output <- call.outputPorts.find(_.name == stepOutputId).
                toRight(NonEmptyList.one(s"step output id $stepOutputId not found in ${call.outputPorts}"))
            } yield output
          }

          /*
           * Build a wom node for the given step and return the newly created nodes
           * This is useful when we've determined that the input belongs to an upstream step that we haven't covered yet
           */
          def buildUpstreamNodes(upstreamStepId: String): Checked[Set[GraphNode]] =
          // Find the step corresponding to this upstreamStepId in the set of all the steps of this workflow
            for {
              step <- workflow.steps.find { step => FullyQualifiedName(step.id).id == upstreamStepId }.
                toRight(NonEmptyList.one(s"no step of id $upstreamStepId found in ${workflow.steps.map(_.id)}"))
              call <- step.callWithInputs(typeMap, workflow, accumulatedNodes, workflowInputs)
            } yield call

          def fromWorkflowInput(inputName: String): Checked[WorkflowStepInputFold] = {
            // Try to find it in the workflow inputs map, if we can't it's an error
            workflowInputs.collectFirst {
              case (inputId, port) if inputName == inputId => updateFold(port)
            } getOrElse s"Can't find workflow input for $inputName".invalidNelCheck[WorkflowStepInputFold]
          }

          def fromStepOutput(stepId: String, stepOutputId: String): Checked[WorkflowStepInputFold] = {
            // First check if we've already built the WOM node for this step, and if so return the associated output port
            findThisInputInSet(accumulatedNodes, stepId, stepOutputId).flatMap(updateFold(_))
              .orElse {
                // Otherwise build the upstream nodes and look again in those newly created nodes
                for {
                  newNodes <- buildUpstreamNodes(stepId)
                  outputPort <- findThisInputInSet(newNodes, stepId, stepOutputId)
                  newFold <- updateFold(outputPort, newNodes)
                } yield newFold
              }
          }

          def updateFold(outputPort: OutputPort, newNodes: Set[GraphNode] = Set.empty): Checked[WorkflowStepInputFold] = {
            val inputSourceId = FullyQualifiedName(inputSource).id
            
            // TODO for now we only handle a single input source, but there may be several
            workflowStepInput.toExpressionNode(Map(inputSourceId -> outputPort), typeMap, Set(inputSourceId)).map({ expressionNode =>
              fold |+| WorkflowStepInputFold(
                stepInputMapping = Map(FullyQualifiedName(workflowStepInput.id).id -> expressionNode),
                generatedNodes = newNodes + expressionNode
              )
            }).toEither
          }

          /*
           * Parse the inputSource (what this input is pointing to)
           * 2 cases:
           *   - points to a workflow input
           *   - points to an upstream step
           */
          FullyQualifiedName(inputSource) match {
            // The source points to a workflow input, which means it should be in the workflowInputs map
            case FileAndId(_, inputId) => fromWorkflowInput(inputId)
            // The source points to an output from a different step
            case FileStepAndId(_, stepId, stepOutputId) => fromStepOutput(stepId, stepOutputId)
          }
      }

      /*
       * Folds over input definitions and build an InputDefinitionFold
       */
      def foldInputDefinition(expressionNodes: Map[String, ExpressionNode], scatterMappings: ScatterMappings)
                             (inputDefinition: InputDefinition): ErrorOr[InputDefinitionFold] = {
        
        // Build a fold when the input definition references a step input
        def buildFoldFromStepInput(expressionNode: ExpressionNode): ErrorOr[InputDefinitionFold] = {
          val (mapping, outputPort, newNode) = if (isScattered) {
            // mapping = Where does this input definition get its value from (in this method it'll always be an output port, but wrapped in a Coproduct[InputDefinitionPointer]
            // outputPort = output port from which the input definition will get its values
            // newNode = potentially newly created node to be added to the fold
            scatterMappings.get(expressionNode) match {
              // This input is being scattered over
              case Some(scatterVariableNode) =>
                // Point the input definition to the scatter variable
                val mapping = List(inputDefinition -> Coproduct[InputDefinitionPointer](scatterVariableNode.singleOutputPort: OutputPort))
                val outputPort = scatterVariableNode.singleOutputPort
                (mapping, outputPort, None)
              // This input is NOT being scattered over
              case None =>
                /*
                  * If this input is not being scattered, we need to point at the step input expression node.
                  * However the call node will be in the scatter inner graph, whereas the input expression node is outside of it.
                  * So we create an OuterGraphInputNode to link them together.
                 */
                val ogin = OuterGraphInputNode(WomIdentifier(inputDefinition.name), expressionNode.singleExpressionOutputPort, preserveScatterIndex = false)
                val mapping = List(inputDefinition -> Coproduct[InputDefinitionPointer](ogin.singleOutputPort: OutputPort))

                (mapping, ogin.singleOutputPort, Option(ogin))
            }
          } else {
            val mapping = List(inputDefinition -> expressionNode.inputDefinitionPointer)
            val outputPort = expressionNode.singleExpressionOutputPort
            (mapping, outputPort, None)
          }

          InputDefinitionFold(
            mappings = mapping,
            callInputPorts = Set(callNodeBuilder.makeInputPort(inputDefinition, outputPort)),
            newExpressionNodes = Set(expressionNode),
            usedOuterGraphInputNodes = newNode.toSet
          ).validNel
        }
        
        inputDefinition match {
          // We got an expression node, meaning there was a workflow step input for this input definition
          // Add the mapping, create an input port from the expression node and add the expression node to the fold
          case _ if expressionNodes.contains(inputDefinition.name) =>
            buildFoldFromStepInput(expressionNodes(inputDefinition.name))

          // No expression node mapping, use the default
          case withDefault @ InputDefinitionWithDefault(_, _, expression) =>
            InputDefinitionFold(
              mappings = List(withDefault -> Coproduct[InputDefinitionPointer](expression))
            ).validNel

          // Required input without default value and without mapping, this is a validation error
          case RequiredInputDefinition(requiredName, _) =>
            s"Input $requiredName is required and is not bound to any value".invalidNel

          // Optional input without mapping, defaults to empty value
          case optional: OptionalInputDefinition =>
            InputDefinitionFold(
              mappings = List(optional -> Coproduct[InputDefinitionPointer](optional.womType.none: WomValue))
            ).validNel
        }
      }

      // WorkflowStepInputFold contains the mappings from step input to ExpressionNode as well as all created nodes
      val stepInputFoldCheck: Checked[WorkflowStepInputFold] = in.foldLeft(WorkflowStepInputFold.emptyRight)(foldStepInput)

      // Validate that the scatter expression is an ArrayLike and return the member type
      def scatterExpressionItemType(expressionNode: ExpressionNode) = {
        expressionNode.womType match {
          case WomArrayType(itemType) => itemType.validNelCheck // Covers maps because this is a custom unapply (see WdlArrayType)
          case other => s"Cannot scatter over a non-traversable type ${other.toDisplayString}".invalidNelCheck
        }
      }

      // Build a list (potentially empty) of scatter helpers containing the expression node and scatter variable node
      // for each input being scattered over
      def buildScatterHelpers(stepInputFold: WorkflowStepInputFold): Checked[ScatterMappings] = {
        import cats.implicits._

        def buildScatterHelperFor(scatterVariableName: String): Checked[(ExpressionNode, ScatterVariableNode)] = {
          // Assume the variable is a step input (is that always true ??). Find the corresponding expression node
          val parsedScatterVariable = FileStepAndId(scatterVariableName)

          stepInputFold.stepInputMapping.get(parsedScatterVariable.id) match {
            case Some(expressionNode) =>
              // find the item type
              scatterExpressionItemType(expressionNode) map { itemType =>
                // create a scatter variable node for other scattered nodes to point to
                val scatterVariableNode = ScatterVariableNode(WomIdentifier(scatterVariableName), expressionNode.singleExpressionOutputPort, itemType)
                (expressionNode, scatterVariableNode)
              }
            case None => s"Could not find a variable ${parsedScatterVariable.id} in the workflow step input to scatter over. Please make sure ${parsedScatterVariable.id} is an input of step $unqualifiedStepId".invalidNelCheck
          }
        }
        // Take the scatter field defining the (list of) input(s) to be scattered over
        scatter
          // Fold them so we have a List[String] no matter what (a single scattered input becomes a single element list)
          .map(_.fold(ScatterVariablesPoly))
          // If there's no scatter make it an empty list
          .getOrElse(List.empty)
          // Traverse the list to create ScatterHelpers that will be used later on to create the ScatterNode
          .traverse[Checked, (ExpressionNode, ScatterVariableNode)](buildScatterHelperFor)
          // Transform the List of tuples to a Map
          .map(_.toMap)
      }

      // Prepare the nodes to be returned if this call is being scattered
      def prepareNodesForScatteredCall(callNodeAndNewNodes: CallNodeAndNewNodes, stepInputFold: WorkflowStepInputFold, scatterMappings: ScatterMappings): Checked[Set[GraphNode]] = {
        val callNode = callNodeAndNewNodes.node

        // We need to generate PBGONs for every output port of the call, so that they can be linked outside the scatter graph
        val portBasedGraphOutputNodes = callNode.outputPorts.map(op => {
          PortBasedGraphOutputNode(op.identifier, op.womType, op)
        })

        // Build the scatter inner graph using the callNode, the outerGraphInputNodes, the scatterVariableNodes, and the PBGONs
        Graph.validateAndConstruct(Set(callNodeAndNewNodes.node) ++ callNodeAndNewNodes.usedOuterGraphInputNodes ++ scatterMappings.values ++ portBasedGraphOutputNodes).toEither map { innerGraph =>
          // Build the ScatterNode - only with single scatter variable for now
          val scatterNodeWithNewNodes = ScatterNode.scatterOverGraph(innerGraph, scatterMappings.head._1, scatterMappings.head._2)
          knownNodes ++ scatterNodeWithNewNodes.nodes ++ stepInputFold.generatedNodes
        }
      }
      
      def prepareNodesForNonScatteredCall(callNodeAndNewNodes: CallNodeAndNewNodes, stepInputFold: WorkflowStepInputFold): Checked[Set[GraphNode]] = {
        (knownNodes ++ callNodeAndNewNodes.nodes ++ stepInputFold.generatedNodes).validNelCheck
      }

      /*
        1) Fold over the workflow step inputs:
          - Create an expression node for each input
          - recursively generates unseen call nodes as we discover them going through step input sources
          - accumulate all that in the WorkflowStepInputFold
        2) Fold over the callable input definition using the expression node map from 1):
          - determine the correct mapping for the input definition based on the expression node map
          and the type of input definition
          - accumulate those mappings, along with potentially newly created graph input nodes as well as call input ports
          in an InputDefinitionFold
        3) Use the InputDefinitionFold to build a new call node
       */
      for {
        stepInputFold <- stepInputFoldCheck
        scatterMappings <- buildScatterHelpers(stepInputFold)
        inputDefinitionFold <- taskDefinition.inputs.foldMap(foldInputDefinition(stepInputFold.stepInputMapping, scatterMappings)).toEither
        callAndNodes = callNodeBuilder.build(unqualifiedStepId, taskDefinition, inputDefinitionFold)
        allNodes <- if(isScattered) prepareNodesForScatteredCall(callAndNodes, stepInputFold, scatterMappings) else prepareNodesForNonScatteredCall(callAndNodes, stepInputFold)
      } yield allNodes
    }
  }
}

/**
  * @see <a href="http://www.commonwl.org/v1.0/Workflow.html#WorkflowStepOutput">WorkflowstepOutput</a>
  */
case class WorkflowStepOutput(id: String)

object WorkflowStep {

  // A monoid can't be derived automatically for this class because it contains a Map[String, ExpressionNode],
  // and there's no monoid defined over ExpressionNode
  implicit val workflowStepInputFoldMonoid: Monoid[WorkflowStepInputFold] = new Monoid[WorkflowStepInputFold] {
    override def empty: WorkflowStepInputFold = WorkflowStepInputFold()
    override def combine(x: WorkflowStepInputFold, y: WorkflowStepInputFold): WorkflowStepInputFold = {
      WorkflowStepInputFold(
        stepInputMapping = x.stepInputMapping ++ y.stepInputMapping,
        generatedNodes = x.generatedNodes ++ y.generatedNodes
      )
    }
  }

  private [cwl] object WorkflowStepInputFold {
    private [cwl] def emptyRight = workflowStepInputFoldMonoid.empty.asRight[NonEmptyList[String]]
  }
  private [cwl] case class WorkflowStepInputFold(stepInputMapping: Map[String, ExpressionNode] = Map.empty,
                                                 generatedNodes: Set[GraphNode] = Set.empty)

  /**
    * Maps input variable (to be scattered over) to their scatter variable node
    */
  type ScatterMappings = Map[ExpressionNode, ScatterVariableNode]

  val emptyOutputs: Outputs = Coproduct[Outputs](Array.empty[String])

  type Run =
    String :+:
      CommandLineTool :+:
      ExpressionTool :+:
      Workflow :+:
      CNil

  type Outputs =
    Array[String] :+:
      Array[WorkflowStepOutput] :+:
      CNil
}