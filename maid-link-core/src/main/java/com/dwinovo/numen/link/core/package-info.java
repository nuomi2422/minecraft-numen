/**
 * Optional host-neutral integration contract core (033/052, 2026-11-10).
 * CURRENT CONSUMER: MaidLinkCoreContractTest adapter fixture; no production plugin registration.
 * Does not replace RDD TaskChain, TaskOwnership, inventory reservations, memory or world facts.
 * Policy checks must execute with real effects in the host's authoritative serial domain;
 * returned tickets/leases are NOT network credentials. Adapters authenticate and bind identity.
 * Stop acknowledgment must follow a real stop, and success evidence must follow business verification.
 * One arbiter per server-session owns all affected resources, not one arbiter per brain/pair.
 * Restart needs host reconciliation before new grants; these intentionally in-memory contracts
 * neither recover old physical work nor promise durable exactly-once delivery.
 */
package com.dwinovo.numen.link.core;
