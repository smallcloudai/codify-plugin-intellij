package com.smallcloud.refactai.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import com.smallcloud.refactai.PluginState
import com.smallcloud.refactai.RefactAIBundle
import com.smallcloud.refactai.Resources
import com.smallcloud.refactai.account.AccountManagerChangedNotifier
import com.smallcloud.refactai.account.LoginStateService
import com.smallcloud.refactai.account.login
import com.smallcloud.refactai.io.InferenceGlobalContextChangedNotifier
import com.smallcloud.refactai.privacy.Privacy
import com.smallcloud.refactai.privacy.PrivacyChangesNotifier
import com.smallcloud.refactai.privacy.PrivacyService
import com.smallcloud.refactai.settings.renderer.PrivacyOverridesTable
import com.smallcloud.refactai.settings.renderer.privacyToString
import com.smallcloud.refactai.struct.DeploymentMode
import com.smallcloud.refactai.utils.makeLinksPanel
import java.awt.event.ItemEvent
import java.awt.event.ItemListener
import java.util.concurrent.TimeUnit
import javax.swing.*
import com.smallcloud.refactai.account.AccountManager.Companion.instance as AccountManager
import com.smallcloud.refactai.io.InferenceGlobalContext.Companion.instance as InferenceGlobalContext


private enum class SettingsState {
    SIGNED,
    UNSIGNED,
    WAITING
}


class AppRootComponent(private val project: Project) {
    private var currentState: SettingsState = SettingsState.UNSIGNED
    private val loginButton = JButton(RefactAIBundle.message("rootSettings.loginOrRegister"))
    private val logoutButton = JButton(RefactAIBundle.message("rootSettings.logout"))
    private val linksPanel: JPanel = makeLinksPanel()
    private val forceLoginButton = JButton(AllIcons.Actions.Refresh)
    private val waitLoginLabel = JBLabel()
    private val activePlanLabel = JBLabel("")

    private val privacyTitledSeparator = TitledSeparator(RefactAIBundle.message("rootSettings.yourPrivacyRules"))
    private val privacySettingDescription = JBLabel("${RefactAIBundle.message("rootSettings.globalDefaults")}:")
    private val privacySettingSelfHostedWarning =
            JBLabel("<html>${RefactAIBundle.message("privacy.selfhostedModeWarning")}</html>")

    private val privacyDefaultsRBGroup = ButtonGroup()
    private val privacyDefaultsRBDisabled = JBRadioButton(
        "${RefactAIBundle.message("privacy.level0Name")}: " +
                RefactAIBundle.message("privacy.level0ShortDescription")
    )
    private val privacyDefaultsRBDisabledDescription = JBLabel(
        RefactAIBundle.message("privacy.level0ExtraDescription"),
        UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER
    )
    private val privacyDefaultsRBRefactAI = JBRadioButton(
        "${RefactAIBundle.message("privacy.level1Name")}: " +
                RefactAIBundle.message("privacy.level1ShortDescription")
    )
    private val privacyDefaultsRBRefactAIDescription = JBLabel(
        RefactAIBundle.message("privacy.level1ExtraDescription"),
        UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER
    )
    private val privacyDefaultsRBRefactAIPlus = JBRadioButton(
        "${RefactAIBundle.message("privacy.level2Name")}: " +
                RefactAIBundle.message("privacy.level2ShortDescription")
    )
    private val privacyDefaultsRBRefactAIPlusDescription = JBLabel(
        RefactAIBundle.message("privacy.level2ExtraDescription"),
        UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER
    )

    private val privacyOverridesLabel = JBLabel(RefactAIBundle.message("rootSettings.globalPrivacyOverrides"))
    private val privacyOverridesTable = PrivacyOverridesTable()
    private val privacyOverridesScrollPane: JBScrollPane

    private fun askDialog(project: Project, newPrivacy: Privacy): Int {
        return if (MessageDialogBuilder.okCancel(
                Resources.titleStr, "Be careful! " +
                        "You are about to change global privacy default to:\n\n<b>${privacyToString[newPrivacy]}</b>\n\n" +
                        "Access settings for\n<b>${project.basePath}</b>\nwill remain at " +
                        "<b>${privacyToString[PrivacyService.instance.getPrivacy(project.basePath!!)]}</b>"
            )
                .yesText(Messages.getOkButton())
                .noText(Messages.getCancelButton())
                .icon(Messages.getQuestionIcon())
                .doNotAsk(object : DoNotAskOption.Adapter() {
                    override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                        if (isSelected && exitCode == Messages.OK) {
                            PrivacyState.instance.dontAskDefaultPrivacyChanged = true
                        }
                    }

                    override fun getDoNotShowMessage(): String {
                        return RefactAIBundle.message("rootSettings.dialog.dontShowAgain")
                    }
                })
                .ask(project)
        ) Messages.OK else Messages.CANCEL
    }

    init {
        privacyDefaultsRBGroup.add(privacyDefaultsRBDisabled)
        privacyDefaultsRBGroup.add(privacyDefaultsRBRefactAI)
        privacyDefaultsRBGroup.add(privacyDefaultsRBRefactAIPlus)

        val connectionTypeChangedListener =
            ItemListener { e: ItemEvent ->
                if (e.stateChange == ItemEvent.SELECTED) {
                    val newPrivacy: Privacy = when (e.source) {
                        privacyDefaultsRBDisabled -> Privacy.DISABLED
                        privacyDefaultsRBRefactAI -> Privacy.ENABLED
                        privacyDefaultsRBRefactAIPlus -> Privacy.THIRDPARTY
                        else -> Privacy.DISABLED
                    }
                    if (PrivacyState.instance.defaultPrivacy == newPrivacy) return@ItemListener

                    if (!PrivacyState.instance.dontAskDefaultPrivacyChanged) {
                        val okCancel = askDialog(project, newPrivacy)
                        if (okCancel != Messages.OK) {
                            when (PrivacyState.instance.defaultPrivacy) {
                                Privacy.DISABLED -> privacyDefaultsRBDisabled.isSelected = true
                                Privacy.ENABLED -> privacyDefaultsRBRefactAI.isSelected = true
                                Privacy.THIRDPARTY -> privacyDefaultsRBRefactAIPlus.isSelected = true
                            }
                            return@ItemListener
                        }
                    }

                    PrivacyState.instance.defaultPrivacy = newPrivacy
                    ApplicationManager.getApplication()
                        .messageBus
                        .syncPublisher(PrivacyChangesNotifier.TOPIC)
                        .privacyChanged()
                }
            }
        privacyDefaultsRBDisabled.addItemListener(connectionTypeChangedListener)
        privacyDefaultsRBRefactAI.addItemListener(connectionTypeChangedListener)
        privacyDefaultsRBRefactAIPlus.addItemListener(connectionTypeChangedListener)

        when (PrivacyState.instance.defaultPrivacy) {
            Privacy.DISABLED -> privacyDefaultsRBDisabled.isSelected = true
            Privacy.ENABLED -> privacyDefaultsRBRefactAI.isSelected = true
            Privacy.THIRDPARTY -> privacyDefaultsRBRefactAIPlus.isSelected = true
        }

        privacyOverridesScrollPane = JBScrollPane(
            privacyOverridesTable,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        )

        currentState = if (AccountManager.isLoggedIn || InferenceGlobalContext.isSelfHosted) {
            SettingsState.SIGNED
        } else if (AccountManager.ticket != null) {
            SettingsState.WAITING
        } else {
            SettingsState.UNSIGNED
        }
        ApplicationManager.getApplication()
            .messageBus
            .connect(PluginState.instance)
            .subscribe(AccountManagerChangedNotifier.TOPIC, object : AccountManagerChangedNotifier {
                override fun isLoggedInChanged(isLoggedIn: Boolean) {
                    currentState = if (isLoggedIn || InferenceGlobalContext.isSelfHosted)
                        SettingsState.SIGNED else SettingsState.UNSIGNED
                    revalidate()
                }

                override fun userChanged(newUser: String?) {
                    revalidate()
                }

                override fun planStatusChanged(newPlan: String?) {
                    revalidate()
                }

                override fun ticketChanged(newTicket: String?) {
                    if (newTicket != null) currentState = SettingsState.WAITING
                    revalidate()
                }
            })
        ApplicationManager.getApplication()
                .messageBus
                .connect(PluginState.instance)
                .subscribe(InferenceGlobalContextChangedNotifier.TOPIC, object : InferenceGlobalContextChangedNotifier {
                    override fun userInferenceUriChanged(newUrl: String?) {
                        revalidate()
                    }

                    override fun deploymentModeChanged(newMode: DeploymentMode) {
                        currentState = if (newMode == DeploymentMode.SELF_HOSTED || AccountManager.isLoggedIn)
                            SettingsState.SIGNED else SettingsState.UNSIGNED
                        revalidate()
                    }
                })

        loginButton.addActionListener {
            login()
        }
        logoutButton.addActionListener {
            AccountManager.logout()
        }
        forceLoginButton.addActionListener {
            ApplicationManager.getApplication().getService(LoginStateService::class.java).tryToWebsiteLogin(true)
        }
        forceLoginButton.addActionListener {
            activePlanLabel.text = "${RefactAIBundle.message("rootSettings.activePlan")}: ⏳"
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                {
                    activePlanLabel.text =
                        "${RefactAIBundle.message("rootSettings.activePlan")}: ${AccountManager.activePlan}"
                }, 500, TimeUnit.MILLISECONDS
            )
        }
    }

    private var myPanel: JPanel = recreatePanel()

    private fun revalidate() {
        setupProperties()
        myPanel.revalidate()
    }

    private fun setupProperties() {
        activePlanLabel.text = "${RefactAIBundle.message("rootSettings.activePlan")}: ${AccountManager.activePlan}"
        activePlanLabel.isVisible = currentState == SettingsState.SIGNED &&
                AccountManager.activePlan != null && InferenceGlobalContext.isCloud
        logoutButton.isVisible = AccountManager.isLoggedIn && AccountManager.user != "self-hosted"
        loginButton.isVisible = currentState != SettingsState.SIGNED
        forceLoginButton.isVisible = currentState != SettingsState.UNSIGNED
        waitLoginLabel.text = if (currentState == SettingsState.WAITING)
            RefactAIBundle.message("rootSettings.waitWebsiteLoginStr") else
            "${RefactAIBundle.message("rootSettings.loggedAs")} ${AccountManager.user}"
        waitLoginLabel.isVisible = currentState != SettingsState.UNSIGNED

        privacyTitledSeparator.isVisible = currentState == SettingsState.SIGNED
        privacySettingDescription.isVisible = currentState == SettingsState.SIGNED
        privacySettingDescription.isEnabled = InferenceGlobalContext.isCloud
        privacyDefaultsRBDisabled.isVisible = currentState == SettingsState.SIGNED
        privacyDefaultsRBDisabled.isEnabled = InferenceGlobalContext.isCloud
        privacyDefaultsRBRefactAI.isVisible = currentState == SettingsState.SIGNED
        privacyDefaultsRBRefactAI.isEnabled = InferenceGlobalContext.isCloud
        privacyDefaultsRBRefactAIPlus.isVisible = currentState == SettingsState.SIGNED
        privacyDefaultsRBRefactAIPlus.isEnabled = InferenceGlobalContext.isCloud
        privacyDefaultsRBDisabledDescription.isVisible = currentState == SettingsState.SIGNED
        privacyDefaultsRBDisabledDescription.isEnabled = InferenceGlobalContext.isCloud
        privacyDefaultsRBRefactAIDescription.isVisible = currentState == SettingsState.SIGNED
        privacyDefaultsRBRefactAIDescription.isEnabled = InferenceGlobalContext.isCloud
        privacyDefaultsRBRefactAIPlusDescription.isVisible = currentState == SettingsState.SIGNED
        privacyDefaultsRBRefactAIPlusDescription.isEnabled = InferenceGlobalContext.isCloud
        privacyOverridesLabel.isVisible = currentState == SettingsState.SIGNED
        privacyOverridesLabel.isEnabled = InferenceGlobalContext.isCloud
        privacyOverridesScrollPane.isVisible = currentState == SettingsState.SIGNED
        privacyOverridesScrollPane.isEnabled = InferenceGlobalContext.isCloud
        privacyOverridesTable.isVisible = currentState == SettingsState.SIGNED
        privacyOverridesTable.isEnabled = InferenceGlobalContext.isCloud

        privacySettingSelfHostedWarning.isVisible = currentState == SettingsState.SIGNED && !InferenceGlobalContext.isCloud
    }

    val preferredFocusedComponent: JComponent
        get() = if (AccountManager.isLoggedIn) forceLoginButton else loginButton

    private fun recreatePanel(): JPanel {
//        val description = JBLabel(pluginDescriptionStr)
        setupProperties()
        return FormBuilder.createFormBuilder().run {
            addComponent(TitledSeparator(RefactAIBundle.message("rootSettings.account")))
//            addComponent(description, UIUtil.LARGE_VGAP)
            addLabeledComponent(waitLoginLabel, forceLoginButton)
            addComponent(activePlanLabel)
            addComponent(logoutButton)
            addComponent(loginButton)

            addComponent(privacyTitledSeparator, UIUtil.LARGE_VGAP)
            addComponent(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(privacySettingSelfHostedWarning)
            }, UIUtil.LARGE_VGAP)
            addComponent(privacySettingDescription, UIUtil.LARGE_VGAP)
            addComponent(privacyDefaultsRBDisabled, (UIUtil.DEFAULT_VGAP * 1.5).toInt())
            addComponent(privacyDefaultsRBDisabledDescription, 0)
            addComponent(privacyDefaultsRBRefactAI, (UIUtil.DEFAULT_VGAP * 1.5).toInt())
            addComponent(privacyDefaultsRBRefactAIDescription, 0)
            addComponent(privacyDefaultsRBRefactAIPlus, (UIUtil.DEFAULT_VGAP * 1.5).toInt())
            addComponent(privacyDefaultsRBRefactAIPlusDescription, 0)
            addComponent(privacyOverridesLabel, UIUtil.LARGE_VGAP)
            addComponent(privacyOverridesScrollPane)
            addComponentFillVertically(JPanel(), 0)
            addComponent(linksPanel).panel
        }
    }

    val panel: JPanel
        get() {
            return myPanel
        }
}
