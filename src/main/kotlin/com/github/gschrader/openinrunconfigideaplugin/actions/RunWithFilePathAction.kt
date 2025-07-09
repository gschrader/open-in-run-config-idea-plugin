package com.github.gschrader.openinrunconfigideaplugin.actions

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
import com.github.gschrader.openinrunconfigideaplugin.MyBundle
import javax.swing.Icon

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
                MyBundle.message("runWithFilePath.default")
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
        
        // Update the action text to show the file name
        if (virtualFile != null) {
            e.presentation.text = MyBundle.message("runWithFilePath", virtualFile.name)
        } else {
            e.presentation.text = MyBundle.message("runWithFilePath.default")
        }
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
            val settings = runManager.findConfigurationByName(configuration.name)
            
            if (settings != null) {
                try {
                    // Store original parameters
                    val originalParams = getCurrentProgramParameters(configuration)
                    
                    // Add the file path as a program argument
                    val filePath = file.path
                    val success = addProgramArgument(configuration, filePath)
                    
                    if (!success) {
                        Messages.showWarningDialog(
                            project,
                            MyBundle.message("configurationNotSupported", configuration.name),
                            MyBundle.message("runWithFilePath.default")
                        )
                        return
                    }
                    
                    // Execute the configuration
                    val executor = DefaultRunExecutor.getRunExecutorInstance()
                    ProgramRunnerUtil.executeConfiguration(settings, executor)
                    
                    // Restore original parameters
                    restoreProgramParameters(configuration, originalParams)
                    
                } catch (e: Exception) {
                    Messages.showErrorDialog(
                        project,
                        MyBundle.message("executionError", e.message ?: "Unknown error"),
                        MyBundle.message("runWithFilePath.default")
                    )
                }
            }
        }
        
        private fun getCurrentProgramParameters(configuration: RunConfiguration): String? {
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
        
        private fun restoreProgramParameters(configuration: RunConfiguration, originalParams: String?) {
            try {
                val setMethod = configuration.javaClass.getMethod("setProgramParameters", String::class.java)
                setMethod.invoke(configuration, originalParams ?: "")
            } catch (e: Exception) {
                // Try alternative approach
                try {
                    val optionsMethod = configuration.javaClass.getMethod("getOptions")
                    val options = optionsMethod.invoke(configuration)
                    val programParamsField = options.javaClass.getDeclaredField("programParameters")
                    programParamsField.isAccessible = true
                    programParamsField.set(options, originalParams ?: "")
                } catch (e2: Exception) {
                    // Unable to restore, but that's okay for temporary modification
                }
            }
        }
        
        private fun addProgramArgument(configuration: RunConfiguration, argument: String): Boolean {
            // Handle different configuration types
            
            // Try common application configuration types
            try {
                // For Application configurations (Java, Kotlin, etc.)
                val method = configuration.javaClass.getMethod("getProgramParameters")
                val currentParams = method.invoke(configuration) as String?
                
                val newParams = if (currentParams.isNullOrEmpty()) {
                    "\"$argument\""
                } else {
                    "$currentParams \"$argument\""
                }
                
                val setMethod = configuration.javaClass.getMethod("setProgramParameters", String::class.java)
                setMethod.invoke(configuration, newParams)
                return true
            } catch (e: Exception) {
                // This configuration type doesn't support program parameters in this way
            }
            
            // Try for other configuration types that might have different parameter methods
            try {
                // Some configurations might use "getOptions" or similar
                val optionsMethod = configuration.javaClass.getMethod("getOptions")
                val options = optionsMethod.invoke(configuration)
                
                // Try to find a program parameters field in the options
                val programParamsField = options.javaClass.getDeclaredField("programParameters")
                programParamsField.isAccessible = true
                val currentParams = programParamsField.get(options) as String?
                
                val newParams = if (currentParams.isNullOrEmpty()) {
                    "\"$argument\""
                } else {
                    "$currentParams \"$argument\""
                }
                
                programParamsField.set(options, newParams)
                return true
            } catch (e: Exception) {
                // This approach also didn't work
            }
            
            // If none of the above work, this configuration type is not supported
            return false
        }
    }
} 