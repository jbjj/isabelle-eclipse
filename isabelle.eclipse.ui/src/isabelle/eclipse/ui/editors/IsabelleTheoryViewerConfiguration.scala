package isabelle.eclipse.ui.editors

import org.eclipse.jface.resource.ResourceManager
import org.eclipse.jface.text.ITextHover
import org.eclipse.jface.text.ITextViewerExtension2.DEFAULT_HOVER_STATE_MASK
import org.eclipse.jface.text.hyperlink.IHyperlinkDetector
import org.eclipse.jface.text.presentation.{IPresentationReconciler, PresentationReconciler}
import org.eclipse.jface.text.rules.ITokenScanner
import org.eclipse.jface.text.source.{Annotation, ISourceViewer}
import org.eclipse.swt.SWT
import org.eclipse.ui.editors.text.{EditorsUI, TextSourceViewerConfiguration}
import org.eclipse.ui.texteditor.ChainedPreferenceStore

import isabelle.{Outer_Syntax, Session}
import isabelle.Document.Snapshot
import isabelle.eclipse.ui.internal.IsabelleUIPlugin
import isabelle.eclipse.ui.preferences.{
  IsabelleMarkupToSyntaxClass,
  IsabellePartitionToSyntaxClass,
  IsabelleTokenToSyntaxClass
}
import isabelle.eclipse.ui.text.{
  AbstractIsabelleScanner,
  ChainedTokenScanner,
  IsabelleActionMarkupScanner,
  IsabelleMarkupScanner,
  IsabelleTokenScanner,
  SingleTokenScanner,
  TokenUtil
}
import isabelle.eclipse.ui.text.hover.IsabelleTextHover
import isabelle.eclipse.ui.text.hyperlink.{IsabelleActionHyperlinkDetector, IsabelleHyperlinkDetector}


/**
 * A SourceViewer configuration used for Isabelle viewers.
 * 
 * This configuration is intended to be used both in Editors and in Views.
 * See IsabelleTheoryConfiguration for Editors-specific extension.
 * 
 * @author Andrius Velykis
 */
class IsabelleTheoryViewerConfiguration(
  session: => Option[Session],
  snapshot: => Option[Snapshot],
  targetEditor: => Option[TheoryEditor],
  resourceManager: ResourceManager,
  annotationHoverMask: Int = DEFAULT_HOVER_STATE_MASK,
  isabelleHoverMask: Int = DEFAULT_HOVER_STATE_MASK)
  extends TextSourceViewerConfiguration(new ChainedPreferenceStore(Array(
      // chain the preference store to get default editor preference values as well as Isabelle-specific
      IsabelleUIPlugin.plugin.getPreferenceStore,
      EditorsUI.getPreferenceStore()))) {

    
  def preferenceStore() = fPreferenceStore
  
  override def getConfiguredDocumentPartitioning(sourceViewer: ISourceViewer) = 
    IsabellePartitions.ISABELLE_PARTITIONING

  override def getConfiguredContentTypes(sourceViewer: ISourceViewer): Array[String] =
    // add Isabelle content types
    super.getConfiguredContentTypes(sourceViewer) ++ IsabellePartitions.contentTypes
    

  override def getPresentationReconciler(sourceViewer: ISourceViewer): IPresentationReconciler = {
    val reconciler = super.getPresentationReconciler(sourceViewer).asInstanceOf[PresentationReconciler]

    /** Sets damager/repairer for the given partition type */
    def handlePartition(partitionType: String, scanner: Option[ITokenScanner] = None) {
      
      // always initialise a scanner for the whole partition
      val partScanner = partitionScanner(partitionType)
      
      // check if another scanner was given - if so, join it on top of the partition scanner
      val fullScanner = scanner match {
        case Some(sc) => join (sc, partScanner)
        case None => partScanner
      }
      
      val dr = new ExtendedStylesDamagerRepairer(fullScanner)
      reconciler.setDamager(dr, partitionType)
      reconciler.setRepairer(dr, partitionType)
    }

    // set damager/repairer for each content type
    val contentTypes = getConfiguredContentTypes(sourceViewer)
    
    import IsabellePartitions._
    contentTypes foreach {
      // for comments, only use the partition scanner - no need to display further scanning
      case ISABELLE_COMMENT => handlePartition(ISABELLE_COMMENT)
      // for other content types, use markup & token scanners in addition to partition scanner
      case contentType => handlePartition(contentType, 
          Some(join(markupScanner(), join(actionMarkupScanner(), tokenScanner()))))
    }

    reconciler
  }
  
  private val prefs = fPreferenceStore
  
  /** Resolve the color manager and preference store values for the abstract trait */
  private trait IsabelleScanner extends AbstractIsabelleScanner {
    def resourceManager = IsabelleTheoryViewerConfiguration.this.resourceManager
    
    // cannot reference parent fPreferenceStore directly here - Scala-IDE Juno crashes
    // bug reported: http://www.assembla.com/spaces/scala-ide/support/tickets/1001114-sbt-crash-for-mixed-scala-java-project
    def preferenceStore = prefs
  }

  /** Joins the scanners in a chained composite scanner */
  private def join(top: ITokenScanner, bottom: ITokenScanner): ITokenScanner =
    new ChainedTokenScanner(top, bottom, TokenUtil.Merge.mergeTextTokens)

  /** Creates a single-token partition scanner which provides tokens for different partition types */
  private def partitionScanner(partition: String): ITokenScanner =
    new SingleTokenScanner with IsabelleScanner {
      override def getToken() =
        getToken(IsabellePartitionToSyntaxClass(partition))
    }

  /** Creates a scanner for Isabelle tokens */
  private def tokenScanner(): ITokenScanner =
    new IsabelleTokenScanner(session) with IsabelleScanner {
      override def getToken(syntax: Outer_Syntax, token: isabelle.Token) =
        getToken(IsabelleTokenToSyntaxClass(syntax, token))
    }
  
  /** Creates a scanner for Isabelle markup information */
  private def markupScanner(): ITokenScanner =
    new IsabelleMarkupScanner(snapshot) with IsabelleScanner {
      override def getToken(markupType: String) =
        getToken(IsabelleMarkupToSyntaxClass(markupType))
    }
  
  /** Creates a scanner for Isabelle markup information for action links */
  private def actionMarkupScanner(): ITokenScanner =
    new IsabelleActionMarkupScanner(snapshot) with IsabelleScanner {
      override def getToken(markupType: String) =
        getToken(IsabelleMarkupToSyntaxClass(markupType))
    }


  override def getHyperlinkDetectors(sourceViewer: ISourceViewer): Array[IHyperlinkDetector] = {
    
    val detectors = Option(super.getHyperlinkDetectors(sourceViewer)) getOrElse Array()
    val isabelleHyperlinks = new IsabelleHyperlinkDetector(snapshot)
    val actionHyperlinks = new IsabelleActionHyperlinkDetector(session, snapshot, targetEditor)
    
    Array(actionHyperlinks, isabelleHyperlinks) ++ detectors
  }

  override def getConfiguredTextHoverStateMasks(sourceViewer: ISourceViewer,
                                                contentType: String): Array[Int] =
    Array(DEFAULT_HOVER_STATE_MASK, SWT.ALT)

  override def getTextHover(sourceViewer: ISourceViewer,
                            contentType: String,
                            stateMask: Int): ITextHover = {

    val showAnnTooltips = annotationHoverMask == stateMask
    val showIsaTooltips = isabelleHoverMask == stateMask

    new IsabelleTextHover(session, snapshot, sourceViewer, showAnnTooltips, showIsaTooltips) {
      // support annotation configuration for "show in text"
      override protected def isIncluded(annotation: Annotation): Boolean = isShownInText(annotation)
    }
  }

}