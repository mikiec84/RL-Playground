package rlp.pages

import com.thoughtworks.binding.Binding.{Constants, Var, Vars}
import com.thoughtworks.binding.{Binding, dom}
import org.scalajs.dom.{Event, window}
import org.scalajs.dom.html.{Canvas, Div}
import org.scalajs.dom.raw.CanvasRenderingContext2D
import rlp.environment.Environment
import rlp._
import rlp.dao.LocalAgentDAO
import rlp.presenters.{AgentPresenter, AgentStore}
import rlp.utils.{BackgroundProcess, KeyboardHandler, Logger}
import rlp.views.{AgentBuildView, AgentComparisonView, AgentTrainView}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.timers

/**
  * The main base class for each game, providing a common
  * layout and functionality for handling pages built
  * on agents and environments
  *
  * @tparam S
  * @tparam A
  */
abstract class GamePage[S, A] extends Page {

  /* Abstract constants / functions which specify the environment functionality */

  // A builder for each agent type
  protected val presenterBuilders: List[AgentPresenter.Builder[A]]

  /** Given a built agent presenter, provide an environment */
  protected def createEnvironment(agentPresenter: AgentPresenter[A]): Environment[S]

  // Render the current environment state onto the rendering context
  protected def render(ctx: CanvasRenderingContext2D): Unit

  // Measure the agent performance
  protected def agentPerformance(presenter: AgentPresenter[A]): Double

  // The number of games between each performance entry
  protected val performanceEntryGap: Int = 100

  protected val renderTraining: Var[Boolean] = Var(true)

  protected val presenters: Vars[AgentPresenter[A]] = Vars()
  private var presenter: AgentPresenter[A] = _

  // Views for the separate sections of the page
  lazy val buildView = new AgentBuildView(presenterBuilders, presenters)
  lazy val trainView = new AgentTrainView(presenters, presenterBuilders, agentPerformance, trainStep)
  lazy val comparisonView = new AgentComparisonView(presenters, performanceEntryGap)

  protected val aspectRatio: Double = 3.0/4
  protected val targetGameWidth = 800

  private var canvas: Canvas = _
  private var ctx: CanvasRenderingContext2D = _
  protected val keyboardHandler = new KeyboardHandler()

  // Handling the number of steps within an episode
  protected val MAX_EPISODE_LENGTH = 1000
  private var episodeLength = 0

  protected var trainingEnvironment: Environment[S] = _

  private val renderProcess = new BackgroundProcess(() => render(ctx), "Rendering")

  // Descriptions to show within the environment info section
  val gameDescription: String
  val inputDescription: String
  val actionDescription: String
  val rewardDescription: String

  private var initialised: Boolean = false

  override def show(): Unit = {
    renderProcess.start(Environment.FPS)

    // Register resize and keyboard handlers
    window.onresize = { _:Event => pageResized() }
    pageResized()

    val game = getElem[Div]("game-row")
    game.tabIndex = 0
    keyboardHandler.register(game)

    if (!initialised) {
      initialised = true

      // Read agents in from the database
      LocalAgentDAO.getAll() map { agentStores =>

        Logger.log("GamePage", s"Loading ${agentStores.length} agent stores from database")

        agentStores
            .filter(_.environmentName == name) // Filter agents for this environment
            .foreach(loadAgentStore) // Load each one

      } recover {

        case error:Throwable => {
          Logger.log("GamePage", "DB access error - " + error.getMessage)
        }
      }
    }

  }

  private def loadAgentStore(agentStore: AgentStore): Unit = {
    try {

      // Find the agent presenter than handles this agent type
      presenterBuilders.find(_._1 == agentStore.agentName) match {

        case Some((_, builder)) => {

          // Create a new agent and add to the presenters
          val agent = builder()
          agent.load(agentStore)
          presenters.get += agent
        }

        case None => {
          Logger.log("GamePage", s"Error loading agentStore ${agentStore.id} invalid agent type")
        }
      }

    } catch {
      case e: Exception =>
        Logger.log("GamePage", s"Error loading agentStore ${agentStore.id} - " + e.getMessage)
    }
  }

  override def hide(): Unit = {
    renderProcess.stop()
    if (trainView.isTraining.get) trainView.pauseTraining()
  }

  protected def toggleRenderTraining(): Unit = {
    renderTraining := !renderTraining.get
  }

  // Rescale the canvas element when the page is resized
  protected def pageResized(): Unit = {
    val container = getElem[Div]("canvas-container")
    val width = Math.min(targetGameWidth, container.offsetWidth - 50)

    canvas.width = width.toInt
    canvas.height = (aspectRatio * width).toInt
  }

  /**
    * Run a single iteration of the current environment
    */
  protected def trainStep(): Unit = {

    episodeLength += 1

    // Step and check for episode end or episode timeout
    if (trainingEnvironment.step() || episodeLength > MAX_EPISODE_LENGTH) {

      // If not timed out
      if (episodeLength <= MAX_EPISODE_LENGTH) {

        // Asynchronously perform performance check
        if (presenter.gamesPlayed.get % performanceEntryGap == 0) {
          timers.setTimeout(20) {
            presenter.logPerformance(agentPerformance(presenter))
          }
        }

        presenter.gamesPlayed := presenter.gamesPlayed.get + 1
      }

      // Reset and begin a new episode
      trainingEnvironment.reset()
      episodeLength = 0
    }
  }

  /**
    * The HTML element containing the full page content
    */
  @dom
  override lazy val content: Binding[Div] = {

    <div class="row page-container">

      <!-- Description of this page -->
      <div class="col s12">
        <div class="description card-panel">
          <span class="flow-text">{gameDescription}</span>
          <br />
          <br />
          {
            val descriptions = List(
              "Input" -> inputDescription,
              "Actions" -> actionDescription,
              "Rewards" -> rewardDescription
            )

            for ((name, desc) <- Constants(descriptions :_*)) yield {
              <div class="description-item row">
                <h6 class="col s1 offset-s1"><strong>{name}</strong></h6>
                <h6 class="col s7">{desc}</h6>
              </div>
            }
          }
        </div>
      </div>

      <!-- Game preview with controls -->
      <div class="col s12">
        <div class="card">
          <div class="row vertical-stretch-row" id="game-row">
            <div class="col s8">
              { gameContainer.bind }
            </div>
            <div class="col s4 teal lighten-5">
              { controlsSection.bind }
            </div>
          </div>
        </div>
      </div>

      <!-- Model training and building section -->
      <div class="col s12">
        <div class="card" id="agent-select">
          <div class="card-content">
            { trainView.content.bind }
          </div>

          { buildView.content.bind }
        </div>
      </div>

      <!-- Graph comparison -->
      <div class="col s12">
        { comparisonView.content.bind }
      </div>

      {
        trainView.selectedAgent.bind match {
          case Some(presenter : AgentPresenter[A]) => {
            this.presenter = presenter
            trainingEnvironment = createEnvironment(presenter)
          }
          case None => /* Do nothing */
        }
        ""
      }
    </div>
  }

  /**
    * The default control section with a render
    * toggle and a title
    */
  @dom
  protected lazy val controlsSection: Binding[Div] = {
    <div id="control-section">
      <div class="center-align">
        <span class="card-title">Game Options</span>
      </div>
      <br />
      <br />

      <div class="switch center-align">
        <label>
          Render Training
          <input type="checkbox" checked={!renderTraining.bind} onchange={ _:Event => toggleRenderTraining() } />
          <span class="lever"></span>
          Play Game
        </label>
      </div>

      { gameOptions.bind }
    </div>
  }

  /**
    * Sub-section for more custom controls for each page
    */
  @dom
  protected lazy val gameOptions: Binding[Div] = {
    <div>Empty!</div>
  }

  /**
    * The container for the game view
    */
  @dom
  protected lazy val gameContainer: Binding[Div] = {
    canvas =
      <canvas class="center-align" width={targetGameWidth}
        height={(targetGameWidth * aspectRatio).toInt}>
      </canvas>
    ctx = canvas.getContext("2d").asInstanceOf[CanvasRenderingContext2D]

    <div id="canvas-container" class="center-align valign-wrapper">
      { canvas }
    </div>
  }
}