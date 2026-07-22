package io.forgetdm.sync;

/**
 * (Retired holder.) The sync repositories are now top-level interfaces so Spring Data JPA
 * repository scanning detects them: {@link SyncSetRepository}, {@link SyncSetMemberRepository},
 * {@link SyncRunRepository}, {@link SyncRunMemberRepository}.
 */
final class SyncRepositories {
    private SyncRepositories() {}
}
