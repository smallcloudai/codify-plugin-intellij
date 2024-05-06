package com.smallcloud.refactai.listeners

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginStateListener
import com.smallcloud.refactai.Resources
import com.smallcloud.refactai.Resources.defaultCloudUrl
import com.smallcloud.refactai.statistic.UsageStatistic
import com.smallcloud.refactai.account.AccountManager.Companion.instance as AccountManager
import com.smallcloud.refactai.statistic.UsageStats.Companion.instance as UsageStats

private var SINGLE_TIME_UNINSTALL = 0

class UninstallListener: PluginStateListener {
    override fun install(descriptor: IdeaPluginDescriptor) {}

    override fun uninstall(descriptor: IdeaPluginDescriptor) {
        if (descriptor.pluginId != Resources.pluginId) {
            return
        }

        if (Thread.currentThread().stackTrace.any { it.methodName == "uninstallAndUpdateUi" }
                && SINGLE_TIME_UNINSTALL == 0) {
            SINGLE_TIME_UNINSTALL++
            UsageStats?.addStatistic(true, UsageStatistic("uninstall"), defaultCloudUrl.toString(), "")
            BrowserUtil.browse("https://refact.ai/feedback?ide=${Resources.client}&tenant=${AccountManager.user}")
        }
    }
}