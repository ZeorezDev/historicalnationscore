package dev.zeorez.historicalnations.api;

import java.util.UUID;

/** Immutable town identity and its current mayor at the time of the query. */
public record Town(UUID id, String name, UUID mayor) {}
