package site.siyoung.beforeaibe.dto.project;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record ProjectOwnersUpdateRequest(@NotNull List<UUID> ownerIds) {
}
