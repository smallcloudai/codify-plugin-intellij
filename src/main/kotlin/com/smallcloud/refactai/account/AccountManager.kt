package com.smallcloud.refactai.account

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.smallcloud.refactai.settings.AppSettingsState
import com.smallcloud.refactai.io.InferenceGlobalContext.Companion.instance as InferenceGlobalContext

class AccountManager: Disposable {
    private var previousLoggedInState: Boolean = false

    var ticket: String?
        get() = AppSettingsState.instance.streamlinedLoginTicket
        set(newTicket) {
            if (newTicket == ticket) return
            ApplicationManager.getApplication()
                    .messageBus
                    .syncPublisher(AccountManagerChangedNotifier.TOPIC)
                    .ticketChanged(newTicket)
            AppSettingsState.instance.streamlinedLoginTicketWasCreatedTs = if (newTicket == null) null else
                System.currentTimeMillis()
            checkLoggedInAndNotifyIfNeed()
        }

    val ticketCreatedTs: Long?
        get() = AppSettingsState.instance.streamlinedLoginTicketWasCreatedTs

    var user: String?
        get() = AppSettingsState.instance.userLoggedIn
        set(newUser) {
            if (newUser == user) return
            ApplicationManager.getApplication()
                    .messageBus
                    .syncPublisher(AccountManagerChangedNotifier.TOPIC)
                    .userChanged(newUser)
            checkLoggedInAndNotifyIfNeed()
        }
    var apiKey: String?
        get() = AppSettingsState.instance.apiKey
        set(newApiKey) {
            if (newApiKey == apiKey) return
            ApplicationManager.getApplication()
                    .messageBus
                    .syncPublisher(AccountManagerChangedNotifier.TOPIC)
                    .apiKeyChanged(newApiKey)
            checkLoggedInAndNotifyIfNeed()
        }
    var activePlan: String? = null
        set(newPlan) {
            if (newPlan == field) return
            field = newPlan
            ApplicationManager.getApplication()
                    .messageBus
                    .syncPublisher(AccountManagerChangedNotifier.TOPIC)
                    .planStatusChanged(newPlan)
        }

    val isLoggedIn: Boolean
        get() {
//            return apiKey.isNullOrEmpty()
             return (InferenceGlobalContext.isCloud && !apiKey.isNullOrEmpty() && !user.isNullOrEmpty()) ||
                     (!InferenceGlobalContext.isCloud && !ticket.isNullOrEmpty())
        }

    var meteringBalance: Int? = null
        set(newValue) {
            if (newValue == field) return
            field = newValue
            ApplicationManager.getApplication()
                    .messageBus
                    .syncPublisher(AccountManagerChangedNotifier.TOPIC)
                    .meteringBalanceChanged(newValue)
        }

    private fun loadFromSettings() {
        previousLoggedInState = isLoggedIn
    }

    fun startup() {
        loadFromSettings()
    }

    private fun checkLoggedInAndNotifyIfNeed() {
        if (previousLoggedInState == isLoggedIn) return
        previousLoggedInState = isLoggedIn
        loginChangedNotify(isLoggedIn)
    }

    private fun loginChangedNotify(isLoggedIn: Boolean) {
        ApplicationManager.getApplication()
                .messageBus
                .syncPublisher(AccountManagerChangedNotifier.TOPIC)
                .isLoggedInChanged(isLoggedIn)
    }

    fun logout() {
        apiKey = null
        user = null
        meteringBalance = null
    }

    override fun dispose() {}

    companion object {
        @JvmStatic
        val instance: AccountManager
            get() = ApplicationManager.getApplication().getService(AccountManager::class.java)
    }
}
