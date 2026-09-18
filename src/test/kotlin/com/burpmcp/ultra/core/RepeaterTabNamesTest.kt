package com.burpmcp.ultra.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RepeaterTabNamesTest {
    @Test fun `logical groups receive stable numbered captions`() {
        assertEquals("Auth · 03 · Refresh token", RepeaterTabNames.compose("Auth", "Refresh token", 3))
    }

    @Test fun `unsafe caption whitespace is normalized`() {
        assertEquals("Auth group · Login request", RepeaterTabNames.compose(" Auth\ngroup ", " Login\trequest "))
    }

    @Test fun `ungrouped unnamed tab remains automatic`() {
        assertEquals(null, RepeaterTabNames.compose(null, null))
    }
}
