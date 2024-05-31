package com.smallcloud.refactai.lsp

import com.smallcloud.refactai.struct.DeploymentMode

data class LSPConfig(
    val address: String? = null,
    var port: Int? = null,
    var apiKey: String? = null,
    var clientVersion: String? = null,
    var useTelemetry: Boolean = false,
    var deployment: DeploymentMode = DeploymentMode.CLOUD,
    var ast: Boolean = false,
    var astFileLimit: Int? = null,
    var vecdb: Boolean = false
) {
    fun toArgs(): List<String> {
        val params = mutableListOf<String>()
        if (address != null) {
            params.add("--address-url")
            params.add("$address")
        }
        if (port != null) {
            params.add("--http-port")
            params.add("$port")
        }
        if (apiKey != null) {
            params.add("--api-key")
            params.add("$apiKey")
        }
        if (clientVersion != null) {
            params.add("--enduser-client-version")
            params.add("$clientVersion")
        }
        if (useTelemetry) {
            params.add("--basic-telemetry")
        }
        if (ast) {
            params.add("--ast")
        }
        if (astFileLimit != null) {
            params.add("--ast-index-max-files")
            params.add("$astFileLimit")
        }
        if (vecdb) {
            params.add("--vecdb")
        }
        return params
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LSPConfig

        if (address != other.address) return false
        if (apiKey != other.apiKey) return false
        if (clientVersion != other.clientVersion) return false
        if (useTelemetry != other.useTelemetry) return false
        if (deployment != other.deployment) return false
        if (ast != other.ast) return false
        if (vecdb != other.vecdb) return false
        if (astFileLimit != other.astFileLimit) return false

        return true
    }

    val isValid: Boolean
        get() {
            return address != null
                && port != null
                && clientVersion != null
                && (astFileLimit != null && astFileLimit!! > 0)
                // token must be if we are not selfhosted
                && (deployment == DeploymentMode.SELF_HOSTED ||
                (apiKey != null && (deployment == DeploymentMode.CLOUD || deployment == DeploymentMode.HF)))
        }
}
