package isabelle.eclipse.launch.config

import isabelle.eclipse.launch.IsabelleLaunchPlugin

/**
 * Constants used in Isabelle launch configurations.
 *
 * @author Andrius Velykis
 */
object IsabelleLaunchConstants {

  private def pluginId = IsabelleLaunchPlugin.plugin.pluginId

  /**
   * String attribute identifying the location of Isabelle installation.
   */
  def ATTR_LOCATION = pluginId + ".ATTR_LOCATION"

  /**
   * String attribute identifying the Isabelle session.
   */
  def ATTR_SESSION = pluginId + ".ATTR_SESSION"


  /**
   * List<String> attribute identifying additional directories for Isabelle sessions.
   */
  def ATTR_SESSION_DIRS = pluginId + ".ATTR_SESSION_DIRS"

  
  /**
   * A property to save dialog settings for last external directory
   */
  def DIALOG_LAST_EXT_DIR = pluginId + ".LAST_EXT_DIR"
  
}
