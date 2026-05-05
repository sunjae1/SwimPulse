package com.swimpulse.event;

import jakarta.validation.constraints.NotNull;

public record UpdateEventStatusRequest(@NotNull EventStatus status) {
}
