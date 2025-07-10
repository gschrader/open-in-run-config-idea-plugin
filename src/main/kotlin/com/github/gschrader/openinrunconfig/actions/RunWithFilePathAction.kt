package com.github.gschrader.openinrunconfig.actions

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.VirtualFile
import com.github.gschrader.openinrunconfig.MyBundle
import javax.swing.Icon
import org.jdom.Element

class RunWithFilePathAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        
        val runManager = RunManager.getInstance(project)
        val allConfigurations = runManager.allConfigurationsList
        
        // Filter to only show Application configurations
        val configurations = allConfigurations.filter { configuration ->
            configuration.type.id == "Application"
        }
        
        if (configurations.isEmpty()) {
            Messages.showInfoMessage(
                project, 
                MyBundle.message("noConfigurationsAvailable"),
                MyBundle.message("runWithFilePath")
            )
            return
        }
        
        val popup = JBPopupFactory.getInstance().createListPopup(
            ConfigurationListPopupStep(configurations, virtualFile, project)
        )
        
        popup.showInBestPositionFor(e.dataContext)
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        
        // Enable action only if we have a project and a selected file
        e.presentation.isEnabledAndVisible = project != null && virtualFile != null && !virtualFile.isDirectory
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    
    private class ConfigurationListPopupStep(
        private val configurations: List<RunConfiguration>,
        private val virtualFile: VirtualFile,
        private val project: Project
    ) : BaseListPopupStep<RunConfiguration>(MyBundle.message("selectConfiguration"), configurations) {
        
        override fun getTextFor(value: RunConfiguration): String = value.name
        
        override fun getIconFor(value: RunConfiguration): Icon? = value.icon
        
        override fun onChosen(selectedValue: RunConfiguration, finalChoice: Boolean): PopupStep<*>? {
            if (finalChoice) {
                runConfigurationWithFilePath(selectedValue, virtualFile, project)
            }
            return PopupStep.FINAL_CHOICE
        }
        
        private fun runConfigurationWithFilePath(configuration: RunConfiguration, file: VirtualFile, project: Project) {
            val runManager = RunManager.getInstance(project)
            
            try {
                // Create a temporary copy of the configuration
                val factory = configuration.factory ?: run {
                    Messages.showErrorDialog(
                        project,
                        MyBundle.message("configurationNotSupported", configuration.name),
                        MyBundle.message("runWithFilePath")
                    )
                    return
                }
                
                val tempSettings = runManager.createConfiguration(
                    "${configuration.name} (with ${file.name})",
                    factory
                )
                
                val tempConfiguration = tempSettings.configuration
                
                // Copy settings from original configuration to temporary one
                copyConfigurationSettings(configuration, tempConfiguration)
                
                // Add the file path as a program argument to the temporary configuration
                val filePath = file.path
                val success = addProgramArgument(tempConfiguration, filePath)
                
                if (!success) {
                    Messages.showWarningDialog(
                        project,
                        MyBundle.message("configurationNotSupported", configuration.name),
                        MyBundle.message("runWithFilePath")
                    )
                    return
                }
                
                // Set the configuration as temporary
                tempSettings.isTemporary = true
                
                // Add the temporary configuration to the run manager so it persists
                runManager.addConfiguration(tempSettings)
                
                // Set it as the selected configuration
                runManager.selectedConfiguration = tempSettings
                
                // Execute the temporary configuration
                val executor = DefaultRunExecutor.getRunExecutorInstance()
                ProgramRunnerUtil.executeConfiguration(tempSettings, executor)
                
            } catch (e: Exception) {
                Messages.showErrorDialog(
                    project,
                    MyBundle.message("executionError", e.message ?: "Unknown error"),
                    MyBundle.message("runWithFilePath")
                )
            }
        }
        
        private fun copyConfigurationSettings(source: RunConfiguration, target: RunConfiguration) {
            try {
                // Use the configuration's built-in serialization to copy all settings
                val element = Element("configuration")
                source.writeExternal(element)
                target.readExternal(element)
                
                // The above copies ALL settings including main class, module, working directory, etc.
                // This is the most reliable way to ensure complete configuration transfer
                
            } catch (e: Exception) {
                // If serialization fails, fall back to manual copying
                try {
                    copySettingsManually(source, target)
                } catch (e2: Exception) {
                    // If all copying fails, the target will use default settings
                    // This is acceptable for our use case
                }
            }
        }
        
        private fun copySettingsManually(source: RunConfiguration, target: RunConfiguration) {
            // Copy program parameters first
            try {
                val sourceParams = getProgramParameters(source)
                setProgramParameters(target, sourceParams ?: "")
            } catch (e: Exception) {
                // Program parameters not available or copy failed, continue
            }
            
            // Try various common configuration methods
            val methodPairs = listOf(
                "getWorkingDirectory" to "setWorkingDirectory",
                "getMainClass" to "setMainClass", 
                "getRunClass" to "setRunClass",
                "getMainClassName" to "setMainClassName",
                "getClassName" to "setClassName"
            )
            
            for ((getMethod, setMethod) in methodPairs) {
                try {
                    val getValue = source.javaClass.getMethod(getMethod)
                    val value = getValue.invoke(source) as String?
                    if (value != null && value.isNotEmpty()) {
                        val setValue = target.javaClass.getMethod(setMethod, String::class.java)
                        setValue.invoke(target, value)
                    }
                } catch (e: Exception) {
                    // Method not available or copy failed, continue
                }
            }
            
            // Try to copy environment variables
            try {
                val envVarsMethod = source.javaClass.getMethod("getEnvs")
                val envVars = envVarsMethod.invoke(source) as Map<String, String>?
                if (envVars != null) {
                    val setEnvVarsMethod = target.javaClass.getMethod("setEnvs", Map::class.java)
                    setEnvVarsMethod.invoke(target, envVars)
                }
            } catch (e: Exception) {
                // Environment variables not available or copy failed, continue
            }
            
            // Try to copy module using various possible methods
            val moduleGetMethods = listOf("getModule", "getConfigurationModule")
            val moduleSetMethods = listOf("setModule", "setConfigurationModule")
            
            for (getMethod in moduleGetMethods) {
                try {
                    val getValue = source.javaClass.getMethod(getMethod)
                    val module = getValue.invoke(source)
                    if (module != null) {
                        for (setMethod in moduleSetMethods) {
                            try {
                                val setValue = target.javaClass.getMethod(setMethod, module.javaClass)
                                setValue.invoke(target, module)
                                break // If successful, stop trying other set methods
                            } catch (e: Exception) {
                                // Try next set method
                            }
                        }
                        break // If we found a module, stop trying other get methods
                    }
                } catch (e: Exception) {
                    // Try next get method
                }
            }
        }
        
        private fun getProgramParameters(configuration: RunConfiguration): String? {
            return try {
                val method = configuration.javaClass.getMethod("getProgramParameters")
                method.invoke(configuration) as String?
            } catch (e: Exception) {
                // Try alternative approach
                try {
                    val optionsMethod = configuration.javaClass.getMethod("getOptions")
                    val options = optionsMethod.invoke(configuration)
                    val programParamsField = options.javaClass.getDeclaredField("programParameters")
                    programParamsField.isAccessible = true
                    programParamsField.get(options) as String?
                } catch (e2: Exception) {
                    null
                }
            }
        }
        
        private fun setProgramParameters(configuration: RunConfiguration, params: String) {
            try {
                val setMethod = configuration.javaClass.getMethod("setProgramParameters", String::class.java)
                setMethod.invoke(configuration, params)
            } catch (e: Exception) {
                // Try alternative approach
                try {
                    val optionsMethod = configuration.javaClass.getMethod("getOptions")
                    val options = optionsMethod.invoke(configuration)
                    val programParamsField = options.javaClass.getDeclaredField("programParameters")
                    programParamsField.isAccessible = true
                    programParamsField.set(options, params)
                } catch (e2: Exception) {
                    // Unable to set parameters
                    throw RuntimeException("Unable to set program parameters", e2)
                }
            }
        }
        
        private fun addProgramArgument(configuration: RunConfiguration, argument: String): Boolean {
            try {
                // Get current parameters
                val currentParams = getProgramParameters(configuration)
                
                // Add the new argument
                val newParams = if (currentParams.isNullOrEmpty()) {
                    "\"$argument\""
                } else {
                    "$currentParams \"$argument\""
                }
                
                // Set the new parameters
                setProgramParameters(configuration, newParams)
                return true
                
            } catch (e: Exception) {
                // If we can't set parameters, this configuration type is not supported
                return false
            }
        }
    }
} 