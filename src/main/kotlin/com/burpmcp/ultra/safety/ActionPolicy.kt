package com.burpmcp.ultra.safety

/**
 * Operator-controlled gate for destructive / irreversible MCP tools.
 *
 * The previous design let the agent self-authorize (e.g. `burp_shutdown`'s
 * `prompt_user` defaulted to false and was agent-set). Governance must live
 * server-side and be unsettable by the agent: these tools are blocked unless
 * the operator has set the Burp preference `mcp_allow_destructive=true`.
 */
object ActionPolicy {

    enum class Tier { READ, MUTATE, DESTRUCTIVE, EXEC }

    /** Tools that shut down Burp or overwrite its configuration. */
    val DESTRUCTIVE_TOOLS = setOf(
        "burp_shutdown",
        "burp_import_project_config",
        "burp_import_user_config",
        "proxy_index_clear",
        "proxy_index_persistence_clear",
        "events_clear",
        "repeater_manifest_clear",
        "scanner_task_delete",
        "scanner_unregister_check",
        "bcheck_remove",
        "scancheck_remove",
        "persistence_delete",
        "config_proxy_listener_remove",
        "config_match_replace_remove",
        "http_remove_traffic_rule",
        "proxy_remove_rule",
        "session_remove_rule"
    )

    val EXEC_TOOLS = setOf(
        "ai_prompt",
        "bambda_import",
        "bcheck_create",
        "bcheck_import",
        "intruder_register_payload_processor",
        "scanner_import_bcheck",
        "scanner_register_check",
        "scancheck_create_active",
        "scancheck_create_passive"
    )

    private val READ_SUFFIXES = setOf(
        "_status", "_list", "_get", "_info", "_summary", "_entry", "_history",
        "_search", "_issues", "_groups", "_templates", "_check", "_version", "_stats",
        "_search_bounded"
    )

    private val READ_TOOLS = setOf(
        "project_info", "extension_info", "proxy_intercept_status", "scope_get_config",
        "burp_command_line_args", "burp_export_project_config", "burp_export_user_config",
        "passive_intel", "analyze_request", "analyze_response", "analyze_diff",
        "analyze_extract_params", "analyze_find_reflected", "analyze_insertion_points",
        "analyze_response_body_search", "recon_js_endpoints", "recon_fingerprint"
    )

    fun isDestructive(toolName: String): Boolean = toolName in DESTRUCTIVE_TOOLS

    fun isExec(toolName: String): Boolean = toolName in EXEC_TOOLS

    fun classify(toolName: String): Tier = when {
        isExec(toolName) -> Tier.EXEC
        isDestructive(toolName) -> Tier.DESTRUCTIVE
        toolName in READ_TOOLS || READ_SUFFIXES.any(toolName::endsWith) -> Tier.READ
        else -> Tier.MUTATE
    }

    /**
     * @param allowDestructive the operator preference (`mcp_allow_destructive`); the agent cannot set it.
     * @return true if [toolName] may run.
     */
    fun isAllowed(toolName: String, allowDestructive: Boolean, allowExec: Boolean = false): Boolean = when (classify(toolName)) {
        Tier.DESTRUCTIVE -> allowDestructive
        Tier.EXEC -> allowExec
        Tier.READ, Tier.MUTATE -> true
    }
}
