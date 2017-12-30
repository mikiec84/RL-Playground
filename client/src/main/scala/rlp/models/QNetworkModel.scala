package rlp.models

import com.thoughtworks.binding.Binding.Var
import com.thoughtworks.binding.{Binding, dom}
import org.scalajs.dom.html
import rlp.agent.QNetworkAgent.QNetworkSpace
import rlp.agent.{Agent, QNetworkAgent}
import rlp.ai.NeuralNetwork
import rlp._
import rlp.ai.optimizers.NetworkOptimizer
import rlp.utils.NumericInputHandler
import upickle.Js

class QNetworkModel[S,A](
  environment: String,
  numActions: Int,
  actionMap: Int => A,
  params: Array[ModelParam[QNetworkSpace[S]]]
) extends NetworkModel[S, A, QNetworkSpace[S]](
  environment, QNetworkModel.name,
  params,
  { p => p.size }, numActions
) {

  private var qNetwork: QNetworkAgent = _

  private val replayBufferSize: Var[Int] = Var(1000)

  @dom
  override lazy val modelBuilder: Binding[html.Div] = {
    <div class="row">

      <h5 class="col s11 offset-s1">Experience Replay</h5>

      <div class="col s4 offset-s4">
        { new NumericInputHandler("Replay Buffer Size", replayBufferSize, 1, 10000).content.bind }
      </div>

      <div class="col s12">{networkBuilder.bind}</div>
    </div>
  }

  override def buildAgent(): Agent[S, A] = {
    val inputs = for ((param, enabled) <- paramBindings.get; if enabled) yield param.value
    val network = buildNetwork()

    qNetwork = new QNetworkAgent(network, replayBufferSize.get)
    qNetwork.reset()

    explorationEpsilon := qNetwork.explorationEpsilon
    discountFactor := qNetwork.discountFactor
    miniBatchSize := qNetwork.miniBatchSize
    updateStepInterval := qNetwork.updateStepInterval

    QNetworkAgent.build(qNetwork, actionMap, inputs)
  }

  override def cloneBuildFrom(that: Model[Agent[S, A]]): Unit = {
    super.cloneBuildFrom(that)

    val controller = that.asInstanceOf[QNetworkModel[S, A]]
    replayBufferSize := controller.replayBufferSize.get
  }

  override def storeBuild(): Js.Value = {
    val networkStore = super.storeBuild()

    Js.Obj(
      "networkStore" -> networkStore,
      "replayBufferSize" -> Js.Num(replayBufferSize.get)
    )
  }

  override def loadBuild(build: Js.Value): Unit = {
    val keyMap = build.obj

    super.loadBuild(keyMap("networkStore"))
    replayBufferSize := keyMap("replayBufferSize").num.toInt
  }

  private val explorationEpsilon: Var[Double] = Var(0.1)
  private val discountFactor: Var[Double] = Var(0.99)
  private val miniBatchSize: Var[Int] = Var(10)
  private val updateStepInterval: Var[Int] = Var(50)

  private def paramsChanged(epsilon: Double, discount: Double, batchSize: Int, updateInterval: Int): Unit = {
    qNetwork.explorationEpsilon = epsilon
    qNetwork.discountFactor = discount
    qNetwork.miniBatchSize = batchSize
    qNetwork.updateStepInterval = updateInterval
  }

  @dom
  override lazy val modelViewer: Binding[html.Div] = {
    <div class="row">

      <h5 class="col s11 offset-s1">Q Learning Parameters</h5>

      <div class="col s3 offset-s2">
        { new NumericInputHandler("Exploration Epsilon", explorationEpsilon, 0, 1).content.bind }
      </div>
      <div class="col s3 offset-s2">
        { new NumericInputHandler("Discount Factor", discountFactor, 0, 1).content.bind }
      </div>

      <h5 class="col s11 offset-s1">Experience Replay</h5>

      <div class="col s2 offset-s2">
        <h6>Buffer Size: {qNetwork.replayBufferSize.toString}</h6>
      </div>

      <div class="col s2 offset-s1">
        { new NumericInputHandler("Mini-batch Size", miniBatchSize, 1, qNetwork.replayBufferSize).content.bind }
      </div>
      <div class="col s2 offset-s1">
        { new NumericInputHandler("Update Step Interval", updateStepInterval, 1, 10000).content.bind }
      </div>

      <div class="col s10 offset-s1">
        { paramSelector.viewer.bind }
      </div>

      <div class="col s12">{networkViewer.bind}</div>

      {
        paramsChanged(explorationEpsilon.bind, discountFactor.bind, miniBatchSize.bind, updateStepInterval.bind)
        ""
      }
    </div>
  }

  override def load(modelStore: ModelStore): Unit = {
    super.load(modelStore)

    explorationEpsilon := qNetwork.explorationEpsilon
    discountFactor := qNetwork.discountFactor
    miniBatchSize := qNetwork.miniBatchSize
    updateStepInterval := qNetwork.updateStepInterval
  }

  override def resetAgent(): Unit = {
    super.resetAgent()
    qNetwork.reset()
  }

  override protected def getNetwork(): NeuralNetwork = qNetwork.network

  override protected def getOptimiser(): NetworkOptimizer = qNetwork.optimiser

  override protected def setOptimiser(optimiser: NetworkOptimizer): Unit = {
    qNetwork.optimiser = optimiser
  }

  override protected def storeAgent(): Js.Value = qNetwork.store()

  override protected def loadAgent(data: Js.Value): Unit = qNetwork.load(data)
}

object QNetworkModel {

  val name = "Q Network"

  def builder[S,A](
    environment: String,
    numActions: Int, actionMap: Int => A,
    params: ModelParam[QNetworkSpace[S]]*): Model.Builder[Agent[S,A]] = {

    name -> (() => new QNetworkModel(environment, numActions, actionMap, params.toArray))
  }
}
