package rlp.pages

import com.thoughtworks.binding.{Binding, dom}
import com.thoughtworks.binding.Binding.{Var, Vars}
import org.scalajs.dom.{Blob, Event, html}
import org.scalajs.dom.html.Div
import org.scalajs.dom.raw.{BlobPropertyBag, FileReader}
import rlp.environment.Environment
import rlp.models.{Model, ModelStore}
import rlp.utils.{BackgroundProcess, SelectHandler}
import rlp._

import scala.scalajs.js

class ModelTrainer[A](
  models: Vars[Model[A]],
  builders: List[Model.Builder[A]],
  trainStep: () => Unit,
) {

  val gameSpeedMultiplier = List(1, 2, 4, 6, -1)
  val gameSpeedToString = gameSpeedMultiplier.init.map("x"+_) ++ List("Max")

  val isTraining: Var[Boolean] = Var(false)
  val gameSpeed: Var[Int] = Var(0)

  val modelSelect = new SelectHandler("Model Select",
    models.mapBinding(m => Binding { m.agentName + " - " + m.modelName.bind })
  )

  val modelExists = Binding { models.bind.nonEmpty }

  val selectedModel: Binding[Option[Model[A]]] = Binding {
    if (modelExists.bind) {
      val model = models.bind(modelSelect.selectedIndex.bind)
      modelSelected(model)
      Some(model)
    } else {
      None
    }
  }

  private val trainingProcess = new BackgroundProcess(trainStep, "Training")

  def startTraining(): Unit = {
    trainingProcess.start(Environment.FPS * gameSpeedMultiplier(gameSpeed.get))
    isTraining := true
  }

  def pauseTraining(): Unit = {
    trainingProcess.stop()
    isTraining := false
  }

  private def modelSelected(model: Model[A]): Unit = {
    if (isTraining.get) pauseTraining()
    js.timers.setTimeout(100) { js.Dynamic.global.Materialize.updateTextFields() }
  }

  @dom
  private def resetTraining(): Unit = {
    selectedModel.bind.get.resetAgent()
  }

  private def fastForwardTraining(): Unit = {
    gameSpeed := (gameSpeed.get + 1) % gameSpeedMultiplier.length
    trainingProcess.stop()
    trainingProcess.start(Environment.FPS * gameSpeedMultiplier(gameSpeed.get))
  }

  @dom
  lazy val content: Binding[Div] = {

    <div class="row" id="model-trainer">

      <div class="row grey col s12 lighten-3" id="model-training">
        <div class="col s2">
          <span class="card-title">Model Training</span>
        </div>

        <div class="col s3"> { modelSelect.handler.bind } </div>

        <div class="col s3"> { trainingButtons.bind } </div>

        {
          val btnStyle = "btn waves-effect waves-light modal-trigger"
          val importError = Var("")

          def onImport(): Unit = {

            val fileElem = getElem[html.Input]("import-file")

            if (fileElem.files.length == 0) {
              importError := "Error - No file specified"
              return
            }

            try {
              val file = fileElem.files(0)

              val reader = new FileReader()
              reader.readAsText(file)

              reader.onload = { _ =>
                val result = reader.result.asInstanceOf[String]
                val store = upickle.default.read[ModelStore](result)

                builders.find(_._1 == store.agentName) match {

                  case Some((_, builder)) => {
                    val model = builder()
                    model.load(store)
                    models.get += model
                  }

                  case None => importError := s"Error reading data, invalid agent ${store.agentName}"
                }
              }

            } catch {
              case e: Exception => {
                importError := "Error importing: \n" + e.getMessage
              }
            }
          }

          initModal("import-modal")

          <div class="col s4" id="model-training-btns">
            <a class={btnStyle} href="#builder-modal">New</a>
            <a class={btnStyle} href="#import-modal">Import</a>

            <div class="modal" id="import-modal">

              <div class="modal-content">
                <h4 class="center-align">Import Model</h4>
                <form action="#">
                  <div class="file-field input-field">
                    <div class="btn">
                      <span>File</span>
                      <input type="file" id="import-file"/>
                    </div>
                    <div class="file-path-wrapper">
                      <input class="file-path validate" type="text" placeholder="Choose file" />
                    </div>
                  </div>
                </form>

                <h5 class="center-align red-text">{importError.bind}</h5>
              </div>

              <div class="modal-footer">
                <a class="btn waves-effect waves-light center-align" onclick={_:Event => onImport()}>Import</a>
              </div>
            </div>
          </div>
        }
      </div>

      <div class="col s10 offset-s1">
        {
          selectedModel.bind match {
            case Some(model) => {

              def onNameChange(): Unit = {
                val modelNames = models.get.map(_.modelName.get)
                val modelNameElem = getElem[html.Input]("model-name-train")

                if (modelNames contains modelNameElem.value) {
                  modelNameElem.setCustomValidity("Invalid")
                } else {
                  modelNameElem.setCustomValidity("")
                  model.modelName := modelNameElem.value
                }
              }

              def onDelete(): Unit = {
                modelSelect.selectedIndex := 0
                models.get.remove(models.get.indexOf(model))
              }

              def onExport(): Unit = {
                import upickle.default._
                val fileStore = write(model.store())
                val fileBlob = new Blob(js.Array(fileStore))

                js.Dynamic.global.saveAs(fileBlob, model.toString + ".json")
              }

              <div class="row">
                <div class="input-field col s3">
                  <input id="model-name-train" class="validate" type="text"
                         value={model.modelName.bind} onchange={_:Event => onNameChange()} required={true}/>
                  <label for="model-name-train" data:data-error="Model name empty or already exists">Model Name</label>
                </div>

                <h6 class="center-align col s3">{s"Games Played: ${model.gamesPlayed.bind}"}</h6>

                <a class="btn waves-effect waves-light col s3"
                   onclick={_:Event => onExport()}>Export</a>
                <a class="btn waves-effect waves-light col s3"
                   onclick={_:Event => onDelete() }>Delete</a>
              </div>
            }
            case None => <!-- -->
          }
        }
      </div>

      <div class="col s12">
        {
          selectedModel.bind match {
            case Some(model) => model.modelViewer.bind
            case None => <!-- -->
          }
        }
      </div>

    </div>
  }

  @dom
  private lazy val trainingButtons: Binding[Div] = {
    val buttonStyle =
      "center-align btn-floating waves-effect waves-circle " +
      (if (modelExists.bind) "" else "disabled ")
    val training = isTraining.bind

    <div class="center-align" id="buttons-container">
      <div class="valign-wrapper">
        <a class= {buttonStyle + "btn-medium orange"}
           onclick={ _:Event => resetTraining() }
        >
          <i class="material-icons">replay</i>
        </a>

        <a class={buttonStyle + "btn-large red"}
           onclick = { _:Event => if (training) pauseTraining() else startTraining() }
        >
          <i class="material-icons">
            { if (training) "pause" else "play_arrow" }
          </i>
        </a>

        <a class= {
           buttonStyle + "btn-medium orange " + (if (training) "" else "disabled")
           }
           onclick={ _:Event => fastForwardTraining() }
        >
          <i class="material-icons">fast_forward</i>
        </a>
        <span id="training-speed"> { if (isTraining.bind) gameSpeedToString(gameSpeed.bind) else "" } </span>
      </div>
    </div>
  }
}
