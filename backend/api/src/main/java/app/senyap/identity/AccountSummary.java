package app.senyap.identity;

import java.util.UUID;

public record AccountSummary(UUID publicId, AccountStatus status, boolean onboarded) {
}
