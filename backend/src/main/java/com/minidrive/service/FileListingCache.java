package com.minidrive.service;

import com.minidrive.dto.file.FileResponse;
import com.minidrive.entity.File;
import com.minidrive.mapper.FileMapper;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.FileVersionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * First Redis metadata cache: the owner-scoped file listing (Phase 10.3).
 *
 * <p>{@link FileService#listInFolder} and {@link FileService#listInOwnedFolder}
 * answer "the files I own in this folder" with the same owner-scoped query, so
 * they share this one cache. The cache stores only the already-authorized
 * result: the exact {@link FileResponse} summaries a database read would have
 * produced.</p>
 *
 * <p>This component deliberately performs <em>no</em> authentication or
 * authorization. Every caller resolves the acting user and validates folder
 * ownership <em>before</em> invoking it, so a cache hit can never skip an
 * access check: the caller reaches this cache at all only after the current
 * authorization has already run. Keys are built from the authenticated user's
 * internal id, never from a request-supplied identity, and carry every query
 * dimension (owner id and folder id).</p>
 *
 * <p>The listing is strictly owner-scoped: {@link #load} selects only files
 * whose {@code owner} is the caller, so a file that is merely shared with the
 * caller never appears in their listing. Granting or revoking a
 * {@code Permission}, and creating or revoking a share link, therefore cannot
 * change which files any user's listing contains, and those paths deliberately
 * leave this cache untouched instead of evicting it. This follows from the
 * query itself, not from the absence of permission fields on
 * {@link FileResponse}: there is no accessible-files or shared-with-me listing,
 * and no repository query joins permissions or share links.</p>
 *
 * <p>Values are the mutable {@code ArrayList} shape Phase 10.2 verified for
 * this serializer: a root-level immutable collection is not reconstitutable by
 * Spring Data Redis 4.1.1 / Jackson 3.1.5, so the summaries are copied into an
 * explicit {@code ArrayList} before being stored. The stored JSON is the same
 * DTO shape the endpoint returns.</p>
 *
 * <p>Invalidation is generation-based (Phase 10.5). A request resolves the
 * current generation token from {@link ListingCacheGeneration} and embeds it in
 * its key, so a request that began under generation G keeps writing under G
 * while later requests read G+1 and never see that write. Committed mutations
 * advance the token instead of clearing the cache, and the token is shared
 * through Redis, so the guarantee holds across application instances.</p>
 *
 * <p>Redis is never the source of truth. Every cache interaction is fail-open:
 * a generation read, a cache read, a cache write or a generation advance that
 * fails is logged and the database answer is used instead, so an unavailable
 * Redis degrades throughput, not correctness. When the generation cannot be
 * resolved at all, no key can be formed safely, so the request is served from
 * the database and nothing is cached. Cache failures are never turned into
 * responses, and no in-memory cache stands in for Redis.</p>
 */
@Service
public class FileListingCache {

    /**
     * Cache name. Combined with {@link com.minidrive.config.CacheConfig}'s
     * prefix, an entry lives at {@code minidrive:cache:file-listings::<key>}.
     */
    public static final String CACHE_NAME = "file-listings";

    /** Key component for an owner's root listing (a request with no folder id). */
    static final String ROOT_KEY = "root";

    private static final Logger log = LoggerFactory.getLogger(FileListingCache.class);

    private final CacheManager cacheManager;
    private final FileRepository fileRepository;
    private final FileVersionRepository fileVersionRepository;

    /**
     * Shared Redis-backed generation token. It stores no entries of its own; it
     * only namespaces the keys this cache reads and writes.
     */
    private final ListingCacheGeneration generationStore;

    public FileListingCache(
            CacheManager cacheManager,
            FileRepository fileRepository,
            FileVersionRepository fileVersionRepository,
            ListingCacheGeneration generationStore) {
        this.cacheManager = cacheManager;
        this.fileRepository = fileRepository;
        this.fileVersionRepository = fileVersionRepository;
        this.generationStore = generationStore;
    }

    /**
     * Returns the owner's file summaries for one folder, or for the owner's root
     * when {@code folderId} is {@code null}, from the cache when a usable entry
     * exists and from the database otherwise. The caller has already
     * authenticated the request and, for a folder, verified ownership.
     */
    public List<FileResponse> findOwnedFileSummaries(UUID ownerId, UUID folderId) {

        // Resolve the generation once and reuse it for both the read and the
        // write. Re-resolving before the write would let a mutation that
        // committed mid-request expose this request's old result under the new
        // generation, which is exactly the race this namespacing prevents.
        String generation = currentGenerationOrNull();
        if (generation == null) {
            return load(ownerId, folderId);
        }

        String key = cacheKey(ownerId, folderId, generation);

        List<FileResponse> cached = read(key);
        if (cached != null) {
            return cached;
        }

        List<FileResponse> summaries = load(ownerId, folderId);
        write(key, summaries);
        return summaries;
    }

    /**
     * The current generation token, or {@code null} when Redis cannot provide
     * one. A {@code null} generation means no key can be formed safely, so the
     * caller serves the request from the database without touching the cache.
     */
    private String currentGenerationOrNull() {

        try {
            return generationStore.current();
        } catch (RuntimeException generationFailure) {
            log.warn("Redis generation for cache {} could not be read; file listings are"
                    + " served from the database", CACHE_NAME, generationFailure);
            return null;
        }
    }

    /**
     * Cache key for one owner's listing of one folder under one generation: the
     * owner's internal id, either the folder id or the root marker, and the
     * generation token. UUIDs and generation tokens contain no separator, so
     * distinct owners, folders and generations can never collide on one key.
     */
    static String cacheKey(UUID ownerId, UUID folderId, String generation) {

        return ownerId + ":" + (folderId == null ? ROOT_KEY : folderId) + ":" + generation;
    }

    /**
     * Advances the listing cache generation once the caller's transaction
     * commits, making every entry written under the previous generation
     * unreachable.
     *
     * <p>Registering from inside the mutation's transaction and advancing in its
     * {@code afterCommit} callback is what keeps the invalidation from being
     * undone: advancing while the transaction is still open would name a new
     * generation before the mutation is durable, letting a reader that observed
     * the pre-commit snapshot repopulate the very generation later requests
     * read. A rolled-back mutation leaves the generation untouched, because its
     * callback never runs. Without an active transaction there is nothing to
     * wait for and the token is advanced immediately.</p>
     *
     * <p>Unlike clear-based eviction, a reader that began before the commit and
     * finishes afterwards can no longer expose its result: it wrote under the
     * generation it read, which no current request consults. Old entries are left
     * in Redis and expire on the 60-second TTL rather than being deleted, so
     * invalidation is a single atomic key write.</p>
     *
     * <p>Invalidation is deliberately coarse: the token is global rather than per
     * (owner, folder), because a rename or move changes two folders at once and a
     * single shared token cannot miss one of them. A failed advance never
     * propagates, so a cache problem cannot turn a committed mutation into an
     * error response; the affected entries simply stay reachable until the TTL
     * expires.</p>
     */
    public void evictAfterCommit() {

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            advanceQuietly("no active transaction");
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {

                    @Override
                    public void afterCommit() {
                        advanceQuietly("after commit");
                    }
                });
    }

    /**
     * Advances the shared generation, logging and swallowing any Redis failure.
     * On failure the previous generation stays current, so entries written under
     * it remain readable until the 60-second TTL expires; this is the documented
     * stale-data window while Redis cannot accept the invalidation.
     */
    private void advanceQuietly(String reason) {

        try {
            generationStore.advance();
        } catch (RuntimeException generationFailure) {
            log.warn("Redis generation advance for cache {} failed ({}); entries written under"
                    + " the previous generation stay reachable until the configured TTL expires",
                    CACHE_NAME, reason, generationFailure);
        }
    }

    private Cache resolveCache() {

        try {
            Cache cache = cacheManager.getCache(CACHE_NAME);
            if (cache == null) {
                log.warn("Cache {} is not configured; file listings are served from the database",
                        CACHE_NAME);
            }
            return cache;
        } catch (RuntimeException cacheFailure) {
            log.warn("Cache {} could not be resolved; file listings are served from the database",
                    CACHE_NAME, cacheFailure);
            return null;
        }
    }

    private List<FileResponse> read(String key) {

        Cache cache = resolveCache();
        if (cache == null) {
            return null;
        }

        try {
            Cache.ValueWrapper wrapper = cache.get(key);
            if (wrapper == null) {
                return null;
            }
            if (wrapper.get() instanceof List<?> summaries) {
                return asFileResponses(summaries);
            }
            log.warn("Ignoring cached file listing under key {} because it is not a list;"
                    + " reading from the database instead", key);
            return null;
        } catch (RuntimeException cacheFailure) {
            log.warn("Redis read failed for file listing key {}; reading from the database instead",
                    key, cacheFailure);
            return null;
        }
    }

    private void write(String key, List<FileResponse> summaries) {

        Cache cache = resolveCache();
        if (cache == null) {
            return;
        }

        try {
            cache.put(key, summaries);
        } catch (RuntimeException cacheFailure) {
            log.warn("Redis write failed for file listing key {}; the database answer was"
                    + " already produced and is still returned", key, cacheFailure);
        }
    }

    @SuppressWarnings("unchecked")
    private List<FileResponse> asFileResponses(List<?> summaries) {

        return (List<FileResponse>) summaries;
    }

    /**
     * The uncached query plus its batched version resolution, copied into a
     * mutable root so the value round-trips through the configured JSON
     * serializer.
     */
    private List<FileResponse> load(UUID ownerId, UUID folderId) {

        List<File> files = folderId == null
                ? fileRepository.findByOwnerIdAndFolderIdIsNull(ownerId)
                : fileRepository.findByOwnerIdAndFolderId(ownerId, folderId);

        return new ArrayList<>(toSummaries(files));
    }

    /**
     * Maps files to list summaries, resolving every file's current version in a
     * single batch query instead of one lookup per file.
     */
    private List<FileResponse> toSummaries(List<File> files) {

        if (files.isEmpty()) {
            return List.of();
        }

        Map<UUID, Integer> currentVersions = fileVersionRepository
                .findCurrentVersions(files.stream().map(File::getId).toList())
                .stream()
                .collect(Collectors.toMap(
                        FileVersionRepository.CurrentVersionProjection::getFileId,
                        FileVersionRepository.CurrentVersionProjection::getCurrentVersion));

        return files.stream()
                .map(file -> FileMapper.toResponse(
                        file, currentVersions.getOrDefault(file.getId(), 0)))
                .toList();
    }
}
