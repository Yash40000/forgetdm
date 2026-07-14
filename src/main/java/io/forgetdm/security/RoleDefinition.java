package io.forgetdm.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record RoleDefinition(String name, String label, String description, Set<String> permissions) {
    public static final List<RoleDefinition> ALL = List.of(
            new RoleDefinition("ADMIN", "Platform Admin", "Full control of ForgeTDM, security, and audit administration.", Set.of("admin.all")),
            new RoleDefinition("TDM_ARCHITECT", "TDM Architect", "Designs source/target maps, policies, DataScope blueprints, and validation flows.", Set.of(
                    "dashboard.read", "datasource.read", "datasource.manage", "discovery.read", "discovery.manage",
                    "policy.read", "policy.manage", "datascope.read", "datascope.manage", "provision.read",
                    "provision.run", "provision.approve", "synthetic.read", "synthetic.profile", "synthetic.manage", "synthetic.run",
                    "synthetic.direct.run", "synthetic.approve", "synthetic.cancel", "synthetic.export",
                    "ri.read", "ri.manage",
                    "mapping.read", "mapping.manage",
                    "mapping.run", "unstructured.read", "unstructured.manage", "unstructured.run", "unstructured.cancel",
                    "query.run", "validation.read", "validation.run", "reservation.read",
                    "reservation.manage", "virtualization.read", "virtualization.manage", "mainframe.read",
                    "mainframe.manage", "assistant.use", "assistant.manage", "audit.read", "integration.read", "integration.manage")),
            new RoleDefinition("DATA_ENGINEER", "Data Engineer", "Builds mappings, runs loads, and manages technical delivery jobs.", Set.of(
                    "dashboard.read", "datasource.read", "discovery.read", "policy.read", "datascope.read",
                    "datascope.manage", "provision.read", "provision.run", "synthetic.read", "synthetic.profile",
                    "synthetic.manage", "synthetic.run", "synthetic.direct.run", "synthetic.cancel", "synthetic.export",
                    "ri.read", "ri.manage",
                    "mapping.read", "mapping.manage", "mapping.run", "unstructured.read", "unstructured.manage",
                    "unstructured.run", "unstructured.cancel", "query.run", "validation.read",
                    "validation.run", "reservation.read", "virtualization.read", "mainframe.read", "assistant.use")),
            new RoleDefinition("TESTER", "Tester", "Consumes approved data, generates safe test data, and validates/reserves test records.", Set.of(
                    "dashboard.read", "datasource.read", "policy.read", "datascope.read", "provision.read", "synthetic.read",
                    "synthetic.run", "synthetic.export", "ri.read", "ri.manage", "unstructured.read", "unstructured.run",
                    "unstructured.cancel", "reservation.read", "reservation.manage", "validation.read", "query.run", "assistant.use")),
            new RoleDefinition("AUDITOR", "Auditor", "Read-only evidence view across audit, validation, and TDM configuration.", Set.of(
                    "dashboard.read", "datasource.read", "discovery.read", "policy.read", "datascope.read",
                    "provision.read", "synthetic.read", "mapping.read", "unstructured.read", "ri.read", "validation.read", "reservation.read",
                    "virtualization.read", "mainframe.read", "audit.read", "integration.read"))
    );

    public static final Map<String, RoleDefinition> BY_NAME = ALL.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(RoleDefinition::name, r -> r));
}
