package com.exambank.common.dto;

import jakarta.validation.constraints.*;
import java.util.List;

/** A location, never an authority: the server resolves all content from MySQL. */
public record ContextRef(@NotNull @Positive Long fileId,
                         @NotBlank @Size(max = 64) String generationId,
                         @Min(1) int page,
                         @Pattern(regexp = "page|selection|region") String kind,
                         @Size(max = 4000) String quote,
                         @Size(min = 4, max = 4) List<@NotNull @DecimalMin("0") @DecimalMax("1") Double> bbox) {}
