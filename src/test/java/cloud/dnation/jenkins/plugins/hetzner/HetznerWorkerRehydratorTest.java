/*
 * Copyright 2026 Percona LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unit tests for HetznerWorkerRehydrator's template-matching logic
 * (PS-11173 Phase 4b, v103.percona.22). The matching heuristic is the
 * highest-risk part of the rehydrate path; the rest of the flow
 * (@Initializer wiring, addNode, fetchAllServers) gets end-to-end
 * validation on the ps3 canary.
 */
package cloud.dnation.jenkins.plugins.hetzner;

import cloud.dnation.hetznerclient.DatacenterDetail;
import cloud.dnation.hetznerclient.LocationDetail;
import cloud.dnation.hetznerclient.ServerDetail;
import cloud.dnation.hetznerclient.ServerType;
import com.google.common.collect.ImmutableMap;
import hudson.model.labels.LabelAtom;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class HetznerWorkerRehydratorTest {

    private MockedStatic<Jenkins> jenkinsMock;

    @BeforeEach
    void setUp() {
        // HetznerServerTemplate constructor calls Jenkins.get().checkPermission()
        // (no-op on the mock) and Label.parse(labelStr) (needs getLabelAtom).
        // Mirror the HetznerCloudSimpleTest setup so Label.parse can resolve
        // the atoms in a labelStr without an NPE deep inside the parser.
        jenkinsMock = mockStatic(Jenkins.class);
        Jenkins jenkins = mock(Jenkins.class);
        doAnswer((Answer<LabelAtom>) inv -> new LabelAtom(inv.getArgument(0)))
                .when(jenkins).getLabelAtom(anyString());
        when(Jenkins.get()).thenReturn(jenkins);
    }

    @AfterEach
    void tearDown() {
        jenkinsMock.close();
    }

    /**
     * v103.percona.22+ VMs carry {@link HetznerConstants#LABEL_TEMPLATE_NAME}.
     * Exact-match path: return the unique template named in the label.
     */
    @Test
    void findTemplate_exactLabelMatch_returnsTemplate() {
        HetznerServerTemplate t1 = template("amd64-build", "img1", "fsn1", "cpx41");
        HetznerServerTemplate t2 = template("arm64-build", "img2", "fsn1", "cax41");

        ServerDetail vm = vm("amd64-build-abc123", "cpx41", "fsn1-dc8", "fsn1",
                ImmutableMap.of(HetznerConstants.LABEL_TEMPLATE_NAME, "amd64-build"));

        HetznerWorkerRehydrator.MatchResult m =
                HetznerWorkerRehydrator.findTemplate(List.of(t1, t2), vm);
        assertSame(t1, m.template);
    }

    /**
     * Legacy VM (provisioned before v103.percona.22) has no template-name
     * label. Heuristic: match by serverType + location + name prefix.
     */
    @Test
    void findTemplate_heuristic_serverTypeLocationPrefix() {
        HetznerServerTemplate amd = template("amd64-build", "img1", "fsn1", "cpx41");
        HetznerServerTemplate arm = template("arm64-build", "img2", "fsn1", "cax41");

        ServerDetail vm = vm("amd64-build-xyz", "cpx41", "fsn1-dc8", "fsn1", null);

        HetznerWorkerRehydrator.MatchResult m =
                HetznerWorkerRehydrator.findTemplate(List.of(amd, arm), vm);
        assertSame(amd, m.template);
    }

    /**
     * Template location may be the long datacenter form (fsn1-dc8). Match
     * against vm.datacenter.name (not just the short location name).
     */
    @Test
    void findTemplate_heuristic_templateLocationIsDatacenter() {
        HetznerServerTemplate t = template("amd64-dc", "img1", "fsn1-dc8", "cpx41");

        ServerDetail vm = vm("amd64-dc-aaa", "cpx41", "fsn1-dc8", "fsn1", null);

        HetznerWorkerRehydrator.MatchResult m =
                HetznerWorkerRehydrator.findTemplate(List.of(t), vm);
        assertSame(t, m.template);
    }

    /**
     * Two templates share serverType + location and could both claim a VM
     * without the template-name label. Return AMBIGUOUS; do not guess.
     */
    @Test
    void findTemplate_heuristic_ambiguous_returnsAmbiguous() {
        // Same serverType + location + default (empty) prefix => both
        // templates produce VMs named "hcloud-<random>" and match the same VM.
        HetznerServerTemplate twin1 = templateWithPrefix("twin-1", "img1", "fsn1", "cpx41", "");
        HetznerServerTemplate twin2 = templateWithPrefix("twin-2", "img1", "fsn1", "cpx41", "");

        ServerDetail vm = vm("hcloud-abc", "cpx41", "fsn1-dc8", "fsn1", null);

        HetznerWorkerRehydrator.MatchResult m =
                HetznerWorkerRehydrator.findTemplate(List.of(twin1, twin2), vm);
        assertSame(HetznerWorkerRehydrator.MatchResult.AMBIGUOUS, m);
        assertNull(m.template);
    }

    /**
     * Different name prefixes disambiguate twin templates.
     */
    @Test
    void findTemplate_heuristic_prefixDisambiguates() {
        HetznerServerTemplate amd = templateWithPrefix("amd64-build", "img1", "fsn1", "cpx41", "amd-worker");
        HetznerServerTemplate amdAlt = templateWithPrefix("amd64-alt", "img1", "fsn1", "cpx41", "alt-worker");

        ServerDetail vm = vm("alt-worker-xyz", "cpx41", "fsn1-dc8", "fsn1", null);

        HetznerWorkerRehydrator.MatchResult m =
                HetznerWorkerRehydrator.findTemplate(List.of(amd, amdAlt), vm);
        assertSame(amdAlt, m.template);
    }

    /**
     * VM whose serverType matches no template returns NONE.
     */
    @Test
    void findTemplate_noMatch_returnsNone() {
        HetznerServerTemplate t = template("amd64-build", "img1", "fsn1", "cpx41");

        ServerDetail vm = vm("hcloud-zzz", "ccx33", "nbg1-dc3", "nbg1", null);

        HetznerWorkerRehydrator.MatchResult m =
                HetznerWorkerRehydrator.findTemplate(List.of(t), vm);
        assertSame(HetznerWorkerRehydrator.MatchResult.NONE, m);
    }

    /**
     * Label-present-but-template-renamed: the label points to a template
     * that no longer exists. Fall through to the heuristic, which still
     * resolves the VM correctly. This is what protects against an operator
     * who renamed a template between provision and restart.
     */
    @Test
    void findTemplate_staleLabel_fallsThroughToHeuristic() {
        HetznerServerTemplate t = template("amd64-build-v2", "img1", "fsn1", "cpx41");

        ServerDetail vm = vm("amd64-build-v2-foo", "cpx41", "fsn1-dc8", "fsn1",
                ImmutableMap.of(HetznerConstants.LABEL_TEMPLATE_NAME, "amd64-build-v1-gone"));

        HetznerWorkerRehydrator.MatchResult m =
                HetznerWorkerRehydrator.findTemplate(List.of(t), vm);
        assertSame(t, m.template);
    }

    /**
     * Empty template list returns NONE without NPE.
     */
    @Test
    void findTemplate_emptyTemplates_returnsNone() {
        ServerDetail vm = vm("hcloud-1", "cpx41", "fsn1-dc8", "fsn1", null);
        HetznerWorkerRehydrator.MatchResult m =
                HetznerWorkerRehydrator.findTemplate(Collections.emptyList(), vm);
        assertSame(HetznerWorkerRehydrator.MatchResult.NONE, m);
    }

    /**
     * Null template list returns NONE without NPE.
     */
    @Test
    void findTemplate_nullTemplates_returnsNone() {
        ServerDetail vm = vm("hcloud-1", "cpx41", "fsn1-dc8", "fsn1", null);
        HetznerWorkerRehydrator.MatchResult m =
                HetznerWorkerRehydrator.findTemplate(null, vm);
        assertSame(HetznerWorkerRehydrator.MatchResult.NONE, m);
    }

    // ----- helpers -----

    private static HetznerServerTemplate template(String name, String image,
                                                  String location, String serverType) {
        // In production deployments operators set prefix=<template-name>; the
        // VMs are then named <prefix>-<random16>. Mirror that here so the
        // matchesPrefix heuristic has data to chew on.
        return templateWithPrefix(name, image, location, serverType, name);
    }

    private static HetznerServerTemplate templateWithPrefix(String name, String image,
                                                            String location, String serverType,
                                                            String prefix) {
        HetznerServerTemplate t = new HetznerServerTemplate(name, "lbl", image, location, serverType);
        t.setPrefix(prefix);
        return t;
    }

    private static ServerDetail vm(String name, String serverType,
                                   String dcName, String locationName,
                                   java.util.Map<String, String> labels) {
        ServerDetail s = new ServerDetail();
        s.setName(name);

        ServerType st = new ServerType();
        st.setName(serverType);
        s.setServerType(st);

        LocationDetail loc = new LocationDetail();
        loc.setName(locationName);
        DatacenterDetail dc = new DatacenterDetail();
        dc.setName(dcName);
        dc.setLocation(loc);
        s.setDatacenter(dc);

        if (labels != null) {
            s.setLabels(labels);
        }
        return s;
    }
}
