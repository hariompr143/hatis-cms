package com.hatis.platform.authorization.domain;

/**
 * What a role binding applies to.
 *
 * <p>Owned by the domain and used unchanged by the application layer. A duplicate
 * enum in {@code AuthorizationService} once existed alongside this one with the same
 * constants; two identical enums are not interchangeable, so every comparison
 * between a binding's scope and a request's scope failed to compile, and any that
 * had been bridged by name would have compared silently unequal at runtime.
 *
 * <p>The order is the containment order: an {@code ORGANIZATION} binding covers
 * everything beneath it, which is what lets authorization resolve hierarchy without
 * walking a tree.
 */
public enum ScopeType {
    ORGANIZATION,
    WORKSPACE,
    PROJECT,
    ENVIRONMENT,
    RESOURCE
}
