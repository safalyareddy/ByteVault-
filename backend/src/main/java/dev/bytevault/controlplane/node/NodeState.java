package dev.bytevault.controlplane.node;

/** Storage-node lifecycle (docs/state-machines.md). */
public enum NodeState { JOINING, HEALTHY, SUSPECT, OFFLINE, DRAINING }
