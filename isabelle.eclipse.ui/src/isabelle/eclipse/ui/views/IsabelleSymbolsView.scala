package isabelle.eclipse.ui.views

import scala.actors.Actor._

import org.eclipse.jface.layout.TreeColumnLayout
import org.eclipse.jface.resource.JFaceResources
import org.eclipse.jface.util.{IPropertyChangeListener, PropertyChangeEvent}
import org.eclipse.jface.viewers.{
  AbstractTreeViewer,
  ColumnViewerToolTipSupport,
  ColumnWeightData,
  DoubleClickEvent,
  IDoubleClickListener,
  IStructuredSelection,
  ITreeContentProvider,
  StyledCellLabelProvider,
  TreeViewerColumn,
  Viewer,
  ViewerCell
}
import org.eclipse.swt.SWT
import org.eclipse.swt.custom.StyleRange
import org.eclipse.swt.graphics.{Font, Image}
import org.eclipse.swt.widgets.Composite
import org.eclipse.ui.{ISharedImages, PlatformUI}
import org.eclipse.ui.dialogs.{FilteredTree, PatternFilter}
import org.eclipse.ui.part.ViewPart
import org.eclipse.ui.texteditor.ITextEditor

import isabelle.Symbol
import isabelle.eclipse.core.IsabelleCore
import isabelle.eclipse.core.app.Isabelle
import isabelle.eclipse.core.util.LoggingActor
import isabelle.eclipse.ui.editors.EditorUtil2.replaceSelected
import isabelle.eclipse.ui.editors.TheoryEditor
import isabelle.eclipse.ui.preferences.IsabelleUIPreferences
import isabelle.eclipse.ui.util.{SWTUtil, TreeRowHeight}


/**
 * A view that lists all available Isabelle symbols.
 *
 * Supports search and uses a tree view to display symbol groups.
 *
 * @author Andrius Velykis
 */
class IsabelleSymbolsView extends ViewPart {
  
  /** a listener for system init event  */
  private val systemListener = LoggingActor {
    loop {
      react {
        case Isabelle.SystemInit =>
          SWTUtil.asyncUnlessDisposed(Option(viewer)) { initSymbols() }
        case _ =>
      }
    }
  }

  /** listener for font changes in preferences */
  private val fontChangeListener = new IPropertyChangeListener {

    override def propertyChange(event: PropertyChangeEvent) =
      if (event.getProperty().equals(isabelleFontKey)) {
        updateFont()
      }
  }


  private var viewer: SymbolFilteredTree = _
  
  override def createPartControl(parent: Composite) {
    
    viewer = new SymbolFilteredTree(parent, SWT.SINGLE | SWT.FULL_SELECTION)
    
    // add listener to the isabelle app to react to session init
    val isabelle = IsabelleCore.isabelle
    isabelle.systemEvents += systemListener
    
    // listen to font changes
    JFaceResources.getFontRegistry.addListener(fontChangeListener)
    
    if (isabelle.isInit) {
      initSymbols()
    }
  }
  
  override def dispose() {
    JFaceResources.getFontRegistry.removeListener(fontChangeListener)
    IsabelleCore.isabelle.systemEvents -= systemListener
  }

  override def setFocus() = viewer.setFocus
  
  private def initSymbols() {
    val input = symbolsGroups
    viewer.getViewer.setInput(input)
  }

  private def symbolsGroups: List[SymbolGroup] =
    Symbol.groups map { case (name, symbols) => SymbolGroup(name, symbols) }


  private def updateFont() {
    viewer.updateRowHeight()
    viewer.getViewer.refresh()
  }


  private def isabelleFontKey: String = IsabelleUIPreferences.ISABELLE_FONT

  private def isabelleFont: Font = JFaceResources.getFontRegistry.get(isabelleFontKey)



  /**
   * On symbol action (double-click), replace the editor selection with the given symbol
   * (add symbol to editor)
   * 
   * TODO support control styles properly
   * (see Token_Markup#edit_control_style and Symbols_Dockable)
   */
  private def symbolAction(s: Symbol.Symbol) = Option(getSite.getPage.getActiveEditor) match {
    // for theory editor, use Unicode symbols if initialised
    case Some(editor: TheoryEditor) if editor.isabelleModel.isDefined =>
      replaceInEditor(editor, Symbol.decode(s))

    // for any text editor, insert non-decoded (ASCII) symbol
    case Some(editor: ITextEditor) => replaceInEditor(editor, s)

    case _ =>
  }
  
  private def replaceInEditor(editor: ITextEditor, text: String) {
    replaceSelected(editor, text)
    editor.getSite.getPage.activate(editor)
  }


  private case class SymbolGroup(name: String, symbols: List[Symbol.Symbol])


  /**
   * A customised FilteredTree with Symbol renderers and content providers
   */
  private class SymbolFilteredTree(parent: Composite, treeStyle: Int)
      extends FilteredTree(parent, treeStyle, new SymbolPatternFilter, true)
      with TreeRowHeight {

    getViewer.setContentProvider(new SymbolTreeContentProvider)
    val defaultRowHeight = getViewer.getTree.getItemHeight
    updateRowHeight()

    val treeLayout = new TreeColumnLayout
    treeComposite.setLayout(treeLayout)
    
    val symbolColumn = {
      val col = new TreeViewerColumn(treeViewer, SWT.LEFT)
      col.getColumn.setAlignment(SWT.LEFT)
      col.setLabelProvider(new SymbolLabelProvider)
      treeLayout.setColumnData(col.getColumn, new ColumnWeightData(100, false))
      col
    }
    
    // expand the groups
    getViewer.setAutoExpandLevel(AbstractTreeViewer.ALL_LEVELS)
    
    // enable tooltips
    ColumnViewerToolTipSupport.enableFor(getViewer)

    // on double-click, replace in editor
    getViewer.addDoubleClickListener(new IDoubleClickListener {
      override def doubleClick(event: DoubleClickEvent) = event.getSelection match {
        case ss: IStructuredSelection => ss.getFirstElement match {
          case s: Symbol.Symbol => symbolAction(s)
          case _ =>
        }
        case _ =>
      } 
    })

    def tree = getViewer.getTree

    def updateRowHeight() {
      val fontMetrics = SWTUtil.initializeFontMetrics(getViewer.getTree, isabelleFont)
      val fontRowHeight = fontMetrics.getHeight// + 2
      
      val rowHeight = fontRowHeight max defaultRowHeight
      
      setItemHeight(rowHeight)
    }
  }


  private class SymbolLabelProvider extends StyledCellLabelProvider {

    lazy val groupImage = PlatformUI.getWorkbench.getSharedImages.getImage(
      ISharedImages.IMG_OBJ_FOLDER)


    def styledText(element: Any): (String, List[StyleRange]) = element match {

      case s: Symbol.Symbol => {
        val decoded = Symbol.decode(s)
        val name = (Symbol.names.get(s) map ("  " + _)) getOrElse ""

        val symLength = decoded.length
        val symStyle = new StyleRange(0, symLength, null, null)
        symStyle.font = isabelleFont

        (decoded + name, List(symStyle))
      }

      case SymbolGroup(name, _) => {
        val prettyName = name.split("\\_").map(_.capitalize).mkString(" ")
        (prettyName, Nil)
      }

      case e => (String.valueOf(e), Nil)
    }
    
    def image(element: Any): Image = element match {
      case SymbolGroup(_, _) => groupImage
      case _ => null
    }

    override def update(cell: ViewerCell) {

      val element = cell.getElement
      val (text, style) = styledText(element)
      val img = image(element)

      cell.setText(text)
      cell.setStyleRanges(style.toArray)
      cell.setImage(img)

      super.update(cell)
    }

    override def getToolTipText(element: Any): String = element match {
      case s: Symbol.Symbol =>
        s + "\n" +
          (Symbol.abbrevs.get(s) map ("abbrev: " + _ + "\n")).getOrElse("") +
          "\n(Double-click to insert)"

      case _ => null
    }
  }


  private class SymbolTreeContentProvider extends ITreeContentProvider {

    override def getElements(parentElement: AnyRef): Array[AnyRef] = parentElement match {
      case list: TraversableOnce[_] => list.asInstanceOf[TraversableOnce[AnyRef]].toArray
      case _ => getChildren(parentElement)
    }

    override def getChildren(parentElement: AnyRef): Array[AnyRef] = parentElement match {
      case SymbolGroup(_, symbols) => symbols.toArray
      case _ => Array()
    }

    override def getParent(element: AnyRef): AnyRef = null

    override def hasChildren(element: AnyRef): Boolean = !getChildren(element).isEmpty

    override def inputChanged(viewer: Viewer, oldInput: AnyRef, newInput: AnyRef) {}

    override def dispose() {}
  }


  /**
   * A special symbol filter that checks various symbol representations when searching
   */
  private class SymbolPatternFilter extends PatternFilter {

    override def isLeafMatch(viewer: Viewer, element: Any): Boolean = element match {

      case SymbolGroup(name, _) => wordMatches(name)

      // check symbol, its contents and abbreviation to allow for various searches
      case s: Symbol.Symbol => 
        wordMatches(Symbol.decode(s)) ||
        wordMatchesValue(Symbol.names, s) ||
        wordMatches(s) ||
        wordMatchesValue(Symbol.abbrevs, s)

      case e => super.isLeafMatch(viewer, e)

    }

    private def wordMatchesValue(map: Map[Symbol.Symbol, String], s: Symbol.Symbol): Boolean =
      map.get(s) exists wordMatches
  }
  
}
