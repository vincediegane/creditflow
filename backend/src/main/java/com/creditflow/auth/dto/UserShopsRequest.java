package com.creditflow.auth.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UserShopsRequest(@NotNull List<Long> shopIds) {
}
