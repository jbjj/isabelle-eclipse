package isabelle.eclipse.launch.tabs

import org.eclipse.jface.resource.{JFaceResources, LocalResourceManager}

import isabelle.eclipse.launch.{IsabelleLaunchImages, IsabelleLaunchPlugin}


/**
 * Tab to configure additional directories for Session lookup.
 * 
 * Sets branding options only - the actual tab content is provided via LaunchComponents.
 * 
 * @author Andrius Velykis
 */
class SessionDirsTab(components: List[LaunchComponent[_]])
    extends LaunchComponentTab(components) {

  override def getName = "Session Source"
  
  override def getId = IsabelleLaunchPlugin.plugin.pluginId + ".sessionDirsTab"

  // cannot access a Control here, so dispose manually in #dispose()
  private val resourceManager = new LocalResourceManager(JFaceResources.getResources)

  override def getImage = resourceManager.createImageWithDefault(
      IsabelleLaunchImages.TAB_SESSION_DIRS)
  
  override def dispose() {
    resourceManager.dispose()
    super.dispose()
  }

}
