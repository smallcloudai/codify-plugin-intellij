package com.smallcloud.refactai.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.smallcloud.refactai.PluginState
import com.smallcloud.refactai.account.AccountManagerChangedNotifier
import org.jetbrains.annotations.Nls
import javax.swing.JComponent
import com.smallcloud.refactai.account.AccountManager.Companion.instance as AccountManager
import com.smallcloud.refactai.io.InferenceGlobalContext.Companion.instance as InferenceGlobalContext

/**
 * Provides controller functionality for application settings.
 */
class AppSettingsConfigurable : Configurable {
    private var mySettingsComponent: AppSettingsComponent? = null

    init {
        ApplicationManager.getApplication().messageBus.connect(PluginState.instance)
            .subscribe(AccountManagerChangedNotifier.TOPIC, object : AccountManagerChangedNotifier {
                override fun apiKeyChanged(newApiKey: String?) {
                    mySettingsComponent?.myTokenText?.let { it.text = newApiKey }
                    mySettingsComponent?.splitter?.revalidate()
                }
            })
        val project = ProjectManager.getInstance().openProjects.first()
    }

    // A default constructor with no arguments is required because this implementation
    // is registered as an applicationConfigurable EP
    override fun getDisplayName(): @Nls(capitalization = Nls.Capitalization.Title) String {
        return "Settings"
    }

    override fun getPreferredFocusedComponent(): JComponent {
        return mySettingsComponent!!.preferredFocusedComponent
    }

    override fun createComponent(): JComponent {
        mySettingsComponent = AppSettingsComponent()
        return mySettingsComponent!!.splitter
    }

    private fun makeRightUrl(url: String?): String? {
        var res = url
        if (res != null && !res.startsWith("http")) {
            res = "http://${res}"
        }
        return res
    }

    override fun isModified(): Boolean {
        var modified =
            (mySettingsComponent!!.tokenText.isNotEmpty() && (AccountManager.apiKey == null ||
                    mySettingsComponent!!.tokenText.trim() != AccountManager.apiKey))
        modified = modified || (mySettingsComponent!!.tokenText.isEmpty() && AccountManager.apiKey != null)

        modified =
            modified || (mySettingsComponent!!.contrastUrlText.isNotEmpty() &&
                    mySettingsComponent!!.contrastUrlText != InferenceGlobalContext.inferenceUri)
        modified =
            modified || (mySettingsComponent!!.contrastUrlText.isEmpty() && !InferenceGlobalContext.isCloud)

        modified = modified || mySettingsComponent!!.useDeveloperMode != InferenceGlobalContext.developerModeEnabled

        modified = modified || mySettingsComponent!!.xDebugLSPPort != InferenceGlobalContext.xDebugLSPPort

        modified = modified || mySettingsComponent!!.stagingVersion != InferenceGlobalContext.stagingVersion
        modified = modified || mySettingsComponent!!.defaultSystemPrompt != AppSettingsState.instance.defaultSystemPrompt

        modified = modified || mySettingsComponent!!.astIsEnabled != InferenceGlobalContext.astIsEnabled
        modified = modified || mySettingsComponent!!.vecdbIsEnabled != InferenceGlobalContext.vecdbIsEnabled

        modified = modified || mySettingsComponent!!.inferenceModel?.trim()?.ifEmpty { null } != InferenceGlobalContext.model

        return modified
    }

    override fun apply() {
        AccountManager.apiKey = mySettingsComponent!!.tokenText.trim().ifEmpty { null }
        InferenceGlobalContext.inferenceUri =
            makeRightUrl(mySettingsComponent!!.contrastUrlText.ifEmpty { null })
        mySettingsComponent!!.contrastUrlText = InferenceGlobalContext.inferenceUri ?: ""
        InferenceGlobalContext.developerModeEnabled = mySettingsComponent!!.useDeveloperMode
        InferenceGlobalContext.stagingVersion = mySettingsComponent!!.stagingVersion
        InferenceGlobalContext.xDebugLSPPort = mySettingsComponent!!.xDebugLSPPort
        AppSettingsState.instance.defaultSystemPrompt = mySettingsComponent!!.defaultSystemPrompt
        InferenceGlobalContext.astIsEnabled = mySettingsComponent!!.astIsEnabled
        InferenceGlobalContext.vecdbIsEnabled = mySettingsComponent!!.vecdbIsEnabled
        InferenceGlobalContext.model = mySettingsComponent!!.inferenceModel?.trim()?.ifEmpty { null }
    }

    override fun reset() {
        mySettingsComponent!!.tokenText = AccountManager.apiKey ?: ""
        mySettingsComponent!!.contrastUrlText = InferenceGlobalContext.inferenceUri ?: ""
        mySettingsComponent!!.useDeveloperMode = InferenceGlobalContext.developerModeEnabled
        mySettingsComponent!!.stagingVersion = InferenceGlobalContext.stagingVersion
        mySettingsComponent!!.xDebugLSPPort = InferenceGlobalContext.xDebugLSPPort
        mySettingsComponent!!.defaultSystemPrompt = AppSettingsState.instance.defaultSystemPrompt
        mySettingsComponent!!.astIsEnabled = InferenceGlobalContext.astIsEnabled
        mySettingsComponent!!.vecdbIsEnabled = InferenceGlobalContext.vecdbIsEnabled
        mySettingsComponent!!.inferenceModel = InferenceGlobalContext.model
    }

    override fun disposeUIResources() {
        mySettingsComponent = null
    }


}
