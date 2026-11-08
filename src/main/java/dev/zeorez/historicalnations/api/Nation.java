package dev.zeorez.historicalnations.api;

import java.util.UUID;

public record Nation(UUID id, String name, UUID capitalTown) {}
