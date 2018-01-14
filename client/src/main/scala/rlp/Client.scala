package rlp

import com.thoughtworks.binding.Binding.Var
import com.thoughtworks.binding.{Binding, dom}
import org.scalajs.dom.{Event, document, html, window}
import rlp.dao.LocalModelStore
import rlp.pages.{FlappyBirdPage, Page, PongPage}
import rlp.ui.{SelectHandler, TabSelectHandler}

import scala.scalajs.js.Dynamic

object Client {

  val pages: List[Page] = List(
    new PongPage(),
    new FlappyBirdPage()
  )

  @dom
  lazy val app: Binding[html.Div] = {

    val pageSelect = new TabSelectHandler(pages.map(_.name))
    val currentPage = Var[Page](null)

    pages foreach { _ setModelDAO LocalModelStore }

    def pageChanged(idx: Int): Unit = {
      if (currentPage.get != null) {
        currentPage.get.stop()
        currentPage := pages(idx)
        currentPage.get.start()
      } else {
        window.onload = { _:Event =>
          currentPage := pages(idx)
          currentPage.get.start()
        }
      }

      Dynamic.global.$("select").material_select()
    }

    <div id="app" class="grey lighten-5">
      <div class="row page-container"> { pageSelect.handler.bind } </div>

      {
        val page = currentPage.bind
        if (page != null) page.content.bind else <!-- -->
      }

      {
        pageChanged(pageSelect.selectedIndex.bind)
        ""
      }
    </div>
  }

  def main(args: Array[String]): Unit = {
    dom.render(document.getElementById("clientContainer"), app)
    SelectHandler.init()

    /* Hide pre-loader */
    getElem[html.Div]("loader").classList.remove("active")
  }
}
