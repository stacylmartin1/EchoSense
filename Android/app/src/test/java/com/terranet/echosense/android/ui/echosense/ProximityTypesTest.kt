/*
 * Copyright 2026 TerraNet Technologies LLC
 * Licensed under the Apache License, Version 2.0.
 */

package com.terranet.echosense.android.ui.echosense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityTypesTest {
  @Test
  fun `metric severity uses safety distance bands`() {
    assertEquals(ProximitySeverity.URGENT, severityForMetric(1.0f))
    assertEquals(ProximitySeverity.WARNING, severityForMetric(1.01f))
    assertEquals(ProximitySeverity.WARNING, severityForMetric(2.0f))
    assertEquals(ProximitySeverity.INFO, severityForMetric(2.01f))
  }

  @Test
  fun `bearing keeps center dead zone stable`() {
    assertEquals(Bearing.LEFT, bearingFromCenterXNorm(0.30f))
    assertEquals(Bearing.CENTER, bearingFromCenterXNorm(0.50f))
    assertEquals(Bearing.RIGHT, bearingFromCenterXNorm(0.70f))
  }

  @Test
  fun `walking corridor widens toward bottom of view`() {
    assertFalse(isInsideCorridor(0.75f, 0.30f))
    assertTrue(isInsideCorridor(0.75f, 0.90f))
  }
}
