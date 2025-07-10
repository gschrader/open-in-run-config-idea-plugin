package com.github.gschrader.openinrunconfig.actions

import com.github.gschrader.openinrunconfig.MyBundle
import com.intellij.execution.CommonProgramRunConfigurationParameters
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.application.ApplicationConfiguration
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
import javax.swing.Icon

class RunWithFilePathAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        
        val runManager = RunManager.getInstance(project)
        val allSettings = runManager.allSettings
        val configurations = allSettings
            .filter { (it.configuration.type.id == "Application" || it.configuration.type.id == "JetRunConfigurationType") && !it.isTemporary }
            .map { it.configuration }
        
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
        
        // Enable action only if we have a project
        e.presentation.isEnabledAndVisible = project != null && virtualFile != null
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
            if (source is ApplicationConfiguration && target is ApplicationConfiguration) {
                target.programParameters = source.programParameters
                target.workingDirectory = source.workingDirectory
                target.mainClassName = source.mainClassName
                target.envs = HashMap(source.envs)
                target.isPassParentEnvs = source.isPassParentEnvs
                target.configurationModule.module = source.configurationModule.module
            }
        }

        private fun addProgramArgument(configuration: RunConfiguration, argument: String): Boolean {
            if (configuration is CommonProgramRunConfigurationParameters) {
                val currentParams = configuration.programParameters
                val newParams = if (currentParams.isNullOrEmpty()) {
                    "\"$argument\""
                } else {
                    "$currentParams \"$argument\""
                }
                configuration.programParameters = newParams
                return true
            }
            return false
        }
    }
} 