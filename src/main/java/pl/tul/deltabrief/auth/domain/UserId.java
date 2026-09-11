package pl.tul.deltabrief.auth.domain;

/**
 * Opaque identifier for a {@link User}. Other modules reference a user only
 * by this value — never by importing {@link User} itself.
 */
public record UserId(Long value) {
}
