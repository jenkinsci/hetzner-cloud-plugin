/*
 *     Copyright 2026 Percona, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 */
package cloud.dnation.jenkins.plugins.hetzner;

import cloud.dnation.hetznerclient.ServerDetail;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link HetznerCloudResourceManager#dedupServersByName(List)} against the
 * upstream {@code PagedResourceHelper.getAllServers()} pagination bug
 * (1-based vs 0-based) that inflated {@code hetzner_running_servers} by ~1.7x
 * on cloud.cd (35 unique servers came back as 60 raw entries).
 *
 * <p>Any future regression that bypasses dedup -- a new upstream library that
 * returns duplicates differently, a refactor that drops the LinkedHashMap
 * wrapper, or another callsite that touches {@code PagedResourceHelper}
 * directly -- must continue to satisfy these invariants.
 */
class HetznerCloudResourceManagerDedupTest {

    private static ServerDetail server(String name) {
        return new ServerDetail().name(name);
    }

    @Test
    void productionFixture_60raw_35unique() {
        // Reproduces the cloud.cd field observation: 35 unique names came back
        // as 60 entries from the buggy upstream loop. The pattern is
        // [first 25 returned twice, then last 10 once] which is what
        // pages [1,1,2] of (per_page=25, total=35) yields.
        List<ServerDetail> page1 = IntStream.range(0, 25)
                .mapToObj(i -> server("hcloud-server-" + i))
                .collect(Collectors.toList());
        List<ServerDetail> page2 = IntStream.range(25, 35)
                .mapToObj(i -> server("hcloud-server-" + i))
                .collect(Collectors.toList());
        List<ServerDetail> raw = new ArrayList<>();
        raw.addAll(page1);
        raw.addAll(page1); // duplicate fetch of page 1 (the bug)
        raw.addAll(page2);
        assertEquals(60, raw.size(), "fixture sanity: raw should be 60 entries");

        List<ServerDetail> deduped = HetznerCloudResourceManager.dedupServersByName(raw);

        assertEquals(35, deduped.size(),
                "dedup must collapse 60 raw entries with 35 unique names down to 35");
    }

    @Test
    void preservesFirstOccurrenceOrder() {
        // Order matters: downstream consumers (orphan cleaner first-pass,
        // diagnostic logging) iterate in the returned order. If the upstream
        // library ever returns server A on page 1 then a stale copy of A on
        // page 1 again, the first (presumably fresher) copy should win.
        ServerDetail firstA = server("a");
        ServerDetail firstB = server("b");
        ServerDetail secondA = server("a");
        ServerDetail firstC = server("c");

        List<ServerDetail> deduped = HetznerCloudResourceManager.dedupServersByName(
                Arrays.asList(firstA, firstB, secondA, firstC));

        assertEquals(3, deduped.size());
        assertEquals("a", deduped.get(0).getName());
        assertEquals("b", deduped.get(1).getName());
        assertEquals("c", deduped.get(2).getName());
        // first-occurrence wins (identity comparison)
        assertTrue(firstA == deduped.get(0),
                "dedup must keep the FIRST occurrence reference, not the second");
    }

    @Test
    void emptyAndNullInputReturnEmptyList() {
        assertNotNull(HetznerCloudResourceManager.dedupServersByName(null));
        assertEquals(0, HetznerCloudResourceManager.dedupServersByName(null).size());
        assertEquals(0, HetznerCloudResourceManager.dedupServersByName(Collections.emptyList()).size());
    }

    @Test
    void singleServerPassesThroughUnchanged() {
        ServerDetail only = server("solo");
        List<ServerDetail> result = HetznerCloudResourceManager.dedupServersByName(
                Collections.singletonList(only));
        assertEquals(1, result.size());
        assertTrue(only == result.get(0));
    }

    @Test
    void noDuplicatesPassesThroughUnchanged() {
        // The non-bug case: when the upstream library is fixed (or the
        // selector matches <= per_page items so no pagination happens),
        // dedup should be a no-op.
        List<ServerDetail> raw = Arrays.asList(
                server("a"), server("b"), server("c"), server("d"));
        List<ServerDetail> result = HetznerCloudResourceManager.dedupServersByName(raw);
        assertEquals(4, result.size());
        assertEquals("a", result.get(0).getName());
        assertEquals("d", result.get(3).getName());
    }

    @Test
    void allDuplicates_collapseToOne() {
        // Degenerate case: a single-page selector returned 5 copies of the
        // same record across "pages". Should collapse to one.
        ServerDetail dupe = server("hcloud-only-one");
        List<ServerDetail> raw = Arrays.asList(
                dupe, server("hcloud-only-one"), server("hcloud-only-one"),
                server("hcloud-only-one"), server("hcloud-only-one"));
        List<ServerDetail> result = HetznerCloudResourceManager.dedupServersByName(raw);
        assertEquals(1, result.size());
    }

    @Test
    void nullEntriesAndNullNamesAreFilteredDefensively() {
        // A malformed server (missing name) should not blow up the metrics
        // tick or the orphan-cleanup scan. Same for an outright-null entry.
        List<ServerDetail> raw = Arrays.asList(
                server("a"),
                null,
                server(null),
                server("b"),
                server(null),
                null,
                server("a"));   // duplicate of valid name
        List<ServerDetail> result = HetznerCloudResourceManager.dedupServersByName(raw);
        assertEquals(2, result.size(), "only two distinct non-null names");
        assertEquals("a", result.get(0).getName());
        assertEquals("b", result.get(1).getName());
    }

    @Test
    void resultIsMutableCopy_notSharedReferenceWithInput() {
        // Defensive: caller can mutate the returned list (e.g. wrap in
        // unmodifiableList downstream) without aliasing the dedup map.
        List<ServerDetail> raw = Arrays.asList(server("a"), server("b"));
        List<ServerDetail> result = HetznerCloudResourceManager.dedupServersByName(raw);
        // mutate; should not affect raw
        result.add(server("c"));
        assertEquals(2, raw.size());
        assertEquals(3, result.size());
    }
}
