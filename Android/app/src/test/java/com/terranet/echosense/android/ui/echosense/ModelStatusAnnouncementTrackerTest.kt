/*
 * Copyright 2026 TerraNet Technologies LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.terranet.echosense.android.ui.echosense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelStatusAnnouncementTrackerTest {
    @Test
    fun `ready is not followed by a stale loading announcement`() {
        val tracker = ModelStatusAnnouncementTracker()

        assertEquals(
            ModelStatusAnnouncement.READY,
            tracker.nextAnnouncement("model", true, false, true),
        )
        assertNull(tracker.nextAnnouncement("model", true, false, false))
    }

    @Test
    fun `normal lifecycle stages announce once`() {
        val tracker = ModelStatusAnnouncementTracker()

        assertEquals(
            ModelStatusAnnouncement.DOWNLOADING,
            tracker.nextAnnouncement("model", false, true, false),
        )
        assertNull(tracker.nextAnnouncement("model", false, true, false))
        assertEquals(
            ModelStatusAnnouncement.LOADING,
            tracker.nextAnnouncement("model", true, false, false),
        )
        assertEquals(
            ModelStatusAnnouncement.READY,
            tracker.nextAnnouncement("model", true, false, true),
        )
    }

    @Test
    fun `selecting another model starts a fresh lifecycle`() {
        val tracker = ModelStatusAnnouncementTracker()

        assertEquals(
            ModelStatusAnnouncement.READY,
            tracker.nextAnnouncement("first", true, false, true),
        )
        assertEquals(
            ModelStatusAnnouncement.LOADING,
            tracker.nextAnnouncement("second", true, false, false),
        )
    }
}
