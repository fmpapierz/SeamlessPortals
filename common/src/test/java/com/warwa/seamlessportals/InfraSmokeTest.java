package com.warwa.seamlessportals;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S0 test-infrastructure smoke test (entity-portal migration —
 * migration/EXECUTION_PLAN.md §3 S0(a): ":common JUnit test infrastructure").
 * Kept PERMANENTLY as the harness canary: if {@code :common:test} ever reports
 * green with zero executed tests, the harness has gone silently vacuous (the
 * exact failure mode the D1.3 test-task held-paths list guards against).
 */
class InfraSmokeTest {

    @Test
    void junitPlatformRuns() {
        assertTrue(true, "the :common JUnit platform is wired (S0 infra smoke)");
    }
}
