package isabelle.eclipse.editors;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import isabelle.Document.Snapshot;
import isabelle.Exn.Result;
import isabelle.Session;
import isabelle.Thy_Header;
import isabelle.Thy_Info;
import isabelle.eclipse.IsabelleEclipsePlugin;
import isabelle.eclipse.core.IsabelleCorePlugin;
import isabelle.eclipse.core.app.IIsabelleSessionListener;
import isabelle.eclipse.core.app.Isabelle;
import isabelle.eclipse.core.resource.URIThyLoad;
import isabelle.eclipse.core.resource.URIPathEncoder;
import isabelle.eclipse.core.text.DocumentModel;
import isabelle.eclipse.views.TheoryOutlinePage;
import isabelle.scala.DocumentRef;
import isabelle.scala.TheoryInfoUtil;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.filesystem.URIUtil;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.IViewportListener;
import org.eclipse.jface.text.JFaceTextUtil;
import org.eclipse.jface.text.source.ILineRange;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.editors.text.ILocationProvider;
import org.eclipse.ui.editors.text.ILocationProviderExtension;
import org.eclipse.ui.editors.text.TextEditor;
import org.eclipse.ui.ide.ResourceUtil;
import org.eclipse.ui.progress.UIJob;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.views.contentoutline.IContentOutlinePage;

import scala.Option;
import scala.Tuple2;


public class TheoryEditor extends TextEditor {

	public static final String EDITOR_ID = "isabelle.eclipse.editors.TheoryEditor";
	public static final String EDITOR_SCOPE = "isabelle.eclipse.editor.theoryEditorScope";
	
	private ColorManager colorManager;
	
	private final IIsabelleSessionListener sessionListener;
	
	private Session isabelleSession;
	
	private DocumentRef documentRef;
	private DocumentModel isabelleModel;
	
	/**
	 * A listener for scrolling events in the editor. Updates the active
	 * perspective upon scrolling.
	 */
	private final IViewportListener viewerViewportListener;
	
	/**
	 * A listener for resize events in the editor. Updates the active
	 * perspective upon editor resize.
	 */
	private final ControlListener viewerControlListener;
	
	private TheoryOutlinePage outlinePage = null;
	
	private final TheoryAnnotations markers = new TheoryAnnotations(this);
	
//	private int submittedOffset = 0;
	
	public TheoryEditor() {
		super();
		colorManager = new ColorManager();
		setSourceViewerConfiguration(new IsabelleTheoryConfiguration(this, colorManager));
//		setSourceViewerConfiguration(new TextSourceViewerConfiguration(EditorsUI.getPreferenceStore()));
		setDocumentProvider(new IsabelleFileDocumentProvider());
		
		Isabelle isabelle = IsabelleCorePlugin.getIsabelle();
		
		// create listeners for perspective change (scrolling & resize)
		this.viewerViewportListener = new IViewportListener() {
			
			@Override
			public void viewportChanged(int verticalOffset) {
				updateActivePerspective();
			}
		};
		
		this.viewerControlListener = new ControlAdapter() {

			@Override
			public void controlResized(ControlEvent e) {
				updateActivePerspective();
			}
		};
		
		isabelle.addSessionListener(sessionListener = new IIsabelleSessionListener() {
			
			@Override
			public void systemInit() {
				initIsabelleSystem();
			}

			@Override
			public void sessionInit(Session session) {
				initSession(session, getEditorInput());
			}
			
			@Override
			public void sessionShutdown(Session session) {
				shutdownSession(session, false);
			}
		});
	}
	
	@Override
	public void init(IEditorSite site, IEditorInput input) throws PartInitException {
		
		// init Isabelle from core plugin
		Isabelle isabelle = IsabelleCorePlugin.getIsabelle();
		Session session = isabelle.getSession();
		if (session != null) {
			initSession(session, input);
		}
		
		super.init(site, input);
		
		activateContext();
	}
	
	/**
	 * Activate a context that this view uses. It will be tied to this editor
	 * activation events and will be removed when the editor is disposed.
	 */
	private void activateContext() {
		IContextService service = (IContextService) getSite().getService(IContextService.class);
		service.activateContext(EDITOR_SCOPE);
	}

	@Override
	public void createPartControl(Composite parent) {
		super.createPartControl(parent);
		
		// initialise perspective listeners after creating the control
		initPerspectiveListeners();
		updateActivePerspective();
	}

	private void initIsabelleSystem() {
		
		if (getEditorInput() != null) {
			// reload editor
			// TODO ask for save?
			reload();
		}
	}

	private void reload() {
		// reload input in the UI thread
		UIJob reloadJob = new UIJob("Reloading editor") {
			@Override
			public IStatus runInUIThread(IProgressMonitor monitor) {
				setInput(getEditorInput());
				return Status.OK_STATUS;
			}
		};
		reloadJob.schedule();
	}
	
	private void reloadOutline() {
		if (outlinePage == null) {
			return;
		}
		
		// reload outline in the UI thread
		UIJob reloadJob = new UIJob("Reloading outline") {
			@Override
			public IStatus runInUIThread(IProgressMonitor monitor) {
				outlinePage.reload();
				return Status.OK_STATUS;
			}
		};
		reloadJob.schedule();
	}

	@Override
	public void dispose() {
		
		if (isabelleSession != null) {
			shutdownSession(isabelleSession, true);
		}
		
		disposePerspectiveListeners();
		
		// TODO review what happens if a second editor is opened for the same input
		Isabelle isabelle = IsabelleCorePlugin.getIsabelle();
		isabelle.removeSessionListener(sessionListener);
		
		markers.dispose();
		
		colorManager.dispose();
		super.dispose();
	}
	
	private void initSession(Session session, IEditorInput input) {
		
		Assert.isTrue(this.isabelleSession == null);
		
		this.isabelleSession = session;
		initIsabelleModel();
		
		reloadOutline();
	}

	private void shutdownSession(Session session, boolean dispose) {
		
		if (this.isabelleSession == null) {
			return;
		}
		
		Assert.isTrue(this.isabelleSession == session);
		
		disposeIsabelleModel();
		this.isabelleSession = null;
		
		if (!dispose) {
			// not disposing, just shutting down the session
			reloadOutline();
		}
	}
	
	@Override
	protected void doSetInput(IEditorInput input) throws CoreException {
		
		// disconnect old model
		disposeIsabelleModel();
		
		super.doSetInput(input);
		
		this.documentRef = createDocumentRef(input);
		
		// connect to the new document
		initIsabelleModel();
		
		markers.updateAllAnnotations();
		
		// TODO need to reload?
		reloadOutline();
	}
	
	private void initIsabelleModel() {
		IDocument document = getDocument();
		if (document != null && documentRef != null && isabelleSession != null) {
			isabelleModel = DocumentModel.create(isabelleSession, document, documentRef);
			
			// update active perspective in the UI thread
			getSite().getWorkbenchWindow().getWorkbench().getDisplay()
					.asyncExec(new Runnable() {

						@Override
						public void run() {
							updateActivePerspective();
						}
					});
//			isabelleModel.setSubmitOffset(submittedOffset);
			
			loadTheoryImports();
		}
	}
	
	private void initPerspectiveListeners() {
		// listen to scroll and resize events
		getSourceViewer().addViewportListener(viewerViewportListener);
		getSourceViewer().getTextWidget().addControlListener(viewerControlListener);
	}
	
	private void disposePerspectiveListeners() {
		ITextViewer viewer = getSourceViewer();
		if (viewer != null && viewer.getTextWidget() != null) {
			viewer.removeViewportListener(viewerViewportListener);
			viewer.getTextWidget().removeControlListener(viewerControlListener);
		}
	}
	
	/**
	 * Loads imported theories to Isabelle. Initialises document models for each of the dependency
	 * but does not open new editors.
	 * 
	 * TODO ask to open new editors as in jEdit?
	 */
	private void loadTheoryImports() {
		
		Isabelle isabelle = IsabelleCorePlugin.getIsabelle();
		Thy_Info theoryInfo = isabelle.getTheoryInfo();
		List<Tuple2<DocumentRef, Result<Thy_Header>>> deps = TheoryInfoUtil.getDependencies(
				theoryInfo, Collections.singletonList(documentRef));
		
		List<DocumentRef> depRefs = new ArrayList<DocumentRef>();
		for (Tuple2<DocumentRef, Result<Thy_Header>> dep : deps) {
			depRefs.add(dep._1());
		}
		
		// remove this editor
		depRefs.remove(documentRef);
		
		// check open editors which have been loaded
		for (IEditorPart editor : EditorUtil.getOpenEditors()) {
			if (editor instanceof TheoryEditor) {
				// TODO more generic instead of TheoryEditor, e.g. via IAdaptable?
				TheoryEditor theoryEditor = (TheoryEditor) editor;
//				if (theoryEditor.isabelleModel != null) {
//					// model is init
					depRefs.remove(theoryEditor.documentRef);
//				}
			}
		}
		
		if (depRefs.isEmpty()) {
			// no dependencies
			return;
		}
		
		IsabelleFileDocumentProvider docProvider = new IsabelleFileDocumentProvider();
		
		for (DocumentRef ref : depRefs) {

			// resolve document URI to load the file contents
			URI uri = URIThyLoad.resolveDocumentUri(ref);
			
			IFileStore fileStore;
			try {
				fileStore = EFS.getStore(uri);
			} catch (CoreException e) {
				// cannot load parent - skip
				IsabelleCorePlugin.log(e);
				continue;
			}
			
			// create the editor input to feed to document provider
			IEditorInput input = EditorUtil.getEditorInput(fileStore);
			
			try {
				docProvider.connect(input);
				IDocument document = docProvider.getDocument(input);
				// init document model
				DocumentModel model = DocumentModel.create(isabelleSession, document, ref);
				// dispose immediately after initialisation
				model.dispose();
				docProvider.disconnect(input);
			} catch (CoreException e) {
				IsabelleEclipsePlugin.log(e.getMessage(), e);
			}
		}
	}
	
	private void disposeIsabelleModel() {
		if (isabelleModel != null) {
			isabelleModel.dispose();
		}
		isabelleModel = null;
	}

	public int getCaretPosition() {
		return getSourceViewer().getTextWidget().getCaretOffset();
	}
	
	public Snapshot getSnapshot() {
		
		if (isabelleModel == null) {
			return null;
		}
		
		return isabelleModel.getSnapshot();
	}
	
	/**
	 * Resolves a URI for the given editor input. For workspace files, the {@code platform:} URI
	 * scheme is used, for the rest it is resolved through IFileStore. Local filesystem files have
	 * {@code file:} URIs.
	 * 
	 * @param input
	 * @return the URI corresponding to the given editor input, or {@code null} if one cannot be
	 *         resolved.
	 * @throws URISyntaxException
	 */
	private static URI getInputURI(IEditorInput input) throws URISyntaxException {
		
		if (input == null) {
			return null;
		}
		
		// first check if the input is on a workspace file
		IFile file = ResourceUtil.getFile(input);
		if (file != null) {
			// encode the path as "platform:" URI
			return getResourceURI(file);
		}
		
		// otherwise try to resolve the URI or IPath
		ILocationProvider provider = (ILocationProvider) input.getAdapter(ILocationProvider.class);
		if (provider instanceof ILocationProviderExtension) {
			// get the URI, if available, and use it
			URI uri = ((ILocationProviderExtension) provider).getURI(input);
			if (uri != null) {
				return uri;
			}
		}
		
		if (provider != null) {
			IPath path = provider.getPath(input);
			if (path != null) {
				// the path can be either workspace, or file system
				// check if it is in the workspace first
				IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(path);
				if (resource != null) {
					// found in workspace - use workspace path
					return getResourceURI(resource);
				} else {
					// absolute file system path
					return URIUtil.toURI(path);
				}
			}
		}
		
		return null;
	}
	
	/**
	 * Creates a workspace resource URI using {@code platform:} scheme.
	 * 
	 * @param resource
	 * @return
	 * @throws URISyntaxException
	 */
	private static URI getResourceURI(IResource resource) throws URISyntaxException {
		IPath path = resource.getFullPath();
		return URIThyLoad.createPlatformUri(path.toString());
	}
	
	private static DocumentRef createDocumentRef(IEditorInput input) {
		Assert.isNotNull(input);
		
		URI uri;
		try {
			uri = getInputURI(input);
		} catch (URISyntaxException e) {
			IsabelleEclipsePlugin.log(e.getMessage(), e);
			return null;
		}
		
		if (uri == null) {
			return null;
		}
		
//		String uriStr = uri.toString();
		String uriStr = URIPathEncoder.encodeAsPath(uri);
		
		Option<String> theoryNameOpt = Thy_Header.thy_name(uriStr);
		if (theoryNameOpt.isEmpty()) {
			IsabelleEclipsePlugin.log("Invalid theory name for URI: " + uriStr, null);
			return null;
		}
		
		String theoryName = theoryNameOpt.get();
		
		URI parentUri = getParentURI(uri);
//		String parentStr = parentUri.toString();
		String parentStr = URIPathEncoder.encodeAsPath(parentUri);
		
		return DocumentRef.create(uriStr, parentStr, theoryName);
	}
	
	/**
	 * Resolves parent URI for the given one. As proposed in
	 * http://stackoverflow.com/questions/10159186/how-to-get-parent-url-in-java
	 * 
	 * @param uri
	 * @return
	 */
	private static URI getParentURI(URI uri) {
		return uri.getPath().endsWith("/") ? uri.resolve("..") : uri.resolve(".");
	}
	
	public Session getIsabelleSession() {
		return isabelleSession;
	}
	
	public DocumentModel getIsabelleModel() {
		return isabelleModel;
	}
	
	public void submitToCaret() {
		
		if (isabelleModel == null) {
			// TODO show a message that model is not available?
//			System.out.println("Isabelle model is not available for " + getPathURI(getEditorInput()));
			return;
		}
		
		MessageDialog.openInformation(getSite().getShell(), "Functionality Disabled", 
				"Submitting to selection is currently disabled in favour of submitting active view.");
		// TODO remove altogether?
		
//		int caretOffset = getCaretPosition();
//		
//		IDocument document = getDocument();
//		
//		try {
//			
//			int caretLine = document.getLineOfOffset(caretOffset);
//			int caretLineEnd = getLineEndOffset(document, caretLine);
//			
//			System.out.println("Go to Command: " + getTitle() + ":[" + caretLine + "," + caretLineEnd + "]");
//			
//			isabelleModel.setSubmitOffset(caretLineEnd);
//			isabelleModel.updatePerspective();
//			
//			this.submittedOffset = caretLineEnd;
//			
//			// TODO review
//			reloadOutline();
//			
//		} catch (BadLocationException ex) {
//			IsabelleEclipsePlugin.log("Bad location in the document", ex);
//		}
	}
	
//	private static int getLineEndOffset(IDocument document, int line) throws BadLocationException {
//		return document.getLineOffset(line) + document.getLineLength(line);
//	}
	
	/**
	 * Updated the active perspective in the model. Finds the region currently
	 * visible in the editor and marks that in the model as its perspective -
	 * the area that should be submitted to the prover.
	 */
	private void updateActivePerspective() {
		
		if (getSourceViewer() == null) {
			return;
		}
		
		if (isabelleModel == null) {
			return;
		}
		
		IDocument document = getDocument();
		if (document == null) {
			return;
		}
		
		ILineRange visibleLines = JFaceTextUtil.getVisibleModelLines(getSourceViewer());
		
		int start = 0;
		int end = 0;
		if (visibleLines.getNumberOfLines() > 0 && visibleLines.getStartLine() >= 0) {
			// something is visible
			try {
				start = document.getLineOffset(visibleLines.getStartLine());
				int endLine = visibleLines.getStartLine() + visibleLines.getNumberOfLines();
				if (endLine >= document.getNumberOfLines() - 1) {
					end = document.getLength();
				} else {
					end = document.getLineOffset(endLine) + document.getLineLength(endLine);
				}
			} catch (BadLocationException e) {
				IsabelleEclipsePlugin.log(e.getMessage(), e);
			}
		}
		
		if (end < start) {
			end = start;
		}
		
		isabelleModel.setActivePerspective(start, end - start);
	}
	
	public IDocument getDocument() {
		IDocumentProvider provider = getDocumentProvider();
		if (provider == null) {
			return null;
		}
		
		return provider.getDocument(getEditorInput());
	}

	@Override
	public Object getAdapter(@SuppressWarnings("rawtypes") Class adapter) {
		if (IContentOutlinePage.class.equals(adapter)) {
			if (outlinePage == null) {
				outlinePage = new TheoryOutlinePage(this);
			}
			return outlinePage;
		}
		
		// expose the Isabelle model via IAdaptable
		if (DocumentModel.class.equals(adapter)) {
			return isabelleModel;
		}
		
		return super.getAdapter(adapter);
	}
	
}
