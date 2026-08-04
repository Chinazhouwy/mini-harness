package com.chinazhouwy.miniharness.finance.market.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LeverageDashboardControllerTest {

    @Test
    void returnsTheSameDeterministicSnapshotUsedByTheDashboard() {
        LeverageDashboardView view = new LeverageDashboardController().getDashboard();

        assertEquals("A_SHARE_PUBLIC_LEVERAGE_FIXTURE", view.market());
        assertEquals(31, view.history().size());
        assertEquals(view.to(), view.latest().tradeDate());
        assertEquals("POTENTIAL_CLEARANCE", view.score().regime().name());
        assertEquals(17L, Math.round(view.score().heatScore()));
        assertEquals(74L, Math.round(view.score().clearScore()));
        assertEquals(67L, Math.round(view.score().confirmationScore()));
        assertFalse(view.sources().isEmpty());
    }
}
