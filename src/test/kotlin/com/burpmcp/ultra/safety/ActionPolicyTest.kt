package com.burpmcp.ultra.safety

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionPolicyTest {

    @Test
    fun `classifies the destructive tools`() {
        assertTrue(ActionPolicy.isDestructive("burp_shutdown"))
        assertTrue(ActionPolicy.isDestructive("burp_import_project_config"))
        assertTrue(ActionPolicy.isDestructive("burp_import_user_config"))
        assertFalse(ActionPolicy.isDestructive("http_send_request"))
        assertTrue(ActionPolicy.isDestructive("proxy_index_persistence_clear"))
        assertTrue(ActionPolicy.isDestructive("scanner_task_delete"))
    }

    @Test
    fun `classifies operator supplied code separately`() {
        assertTrue(ActionPolicy.isExec("bambda_import"))
        assertTrue(ActionPolicy.isExec("bcheck_import"))
        assertTrue(ActionPolicy.isExec("scanner_import_bcheck"))
        assertTrue(ActionPolicy.isExec("scancheck_create_active"))
        assertFalse(ActionPolicy.isAllowed("bambda_import", allowDestructive = true, allowExec = false))
        assertTrue(ActionPolicy.isAllowed("bambda_import", allowDestructive = false, allowExec = true))
        assertTrue(ActionPolicy.isDestructive("scanner_unregister_check"))
    }

    @Test
    fun `classifies observational tools as read only`() {
        assertTrue(ActionPolicy.classify("proxy_index_stats") == ActionPolicy.Tier.READ)
        assertTrue(ActionPolicy.classify("proxy_history_summary") == ActionPolicy.Tier.READ)
        assertTrue(ActionPolicy.classify("http_send_request") == ActionPolicy.Tier.MUTATE)
    }

    @Test
    fun `non-destructive tools are always allowed`() {
        assertTrue(ActionPolicy.isAllowed("http_send_request", allowDestructive = false))
        assertTrue(ActionPolicy.isAllowed("proxy_history", allowDestructive = false))
    }

    @Test
    fun `destructive tools are allowed only when the operator opts in`() {
        assertFalse(ActionPolicy.isAllowed("burp_shutdown", allowDestructive = false))
        assertTrue(ActionPolicy.isAllowed("burp_shutdown", allowDestructive = true))
        assertFalse(ActionPolicy.isAllowed("burp_import_user_config", allowDestructive = false))
    }
}
