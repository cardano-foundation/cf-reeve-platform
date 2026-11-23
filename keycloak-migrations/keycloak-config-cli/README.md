# Manual Keycloak configuration with keycloak-config-cli

This directory contains focused, declarative configuration for applying Reeve
changes to an existing Keycloak realm. It is **not** a full realm import and is
not wired into Docker Compose.

## What the migrations change

[`001-reeve-api-resource-server.json`](001-reeve-api-resource-server.json):

- creates or reconciles the bearer-only `reeve-api` client;
- reconciles the existing `roles` client scope;
- adds the `reeve-api` audience mapper;
- adds the flat `roles` claim mapper used by Spring Security.

[`002-basic-client-scope.json`](002-basic-client-scope.json):

- creates or reconciles the `basic` client scope;
- adds the `sub` access-token mapper;
- adds the `auth_time` ID- and access-token mapper;
- assigns `basic` as a default scope of `webclient`.

The full existing `protocolMappers` list is declared for each managed scope
because keycloak-config-cli treats that list as desired state. Omitting an
existing mapper would remove it. Other properties omitted from a managed
resource are preserved. Review the target realm before the first production run
if either scope has environment-specific mappers that are not represented here.

Only `clientId` and `defaultClientScopes` are declared for `webclient`, so its
other properties are preserved. The complete default-scope list from the
checked-in realm is included because that list is also desired state; any
environment-specific default scopes not represented in the migration would be
removed.

[`003-group-access-model.json`](003-group-access-model.json):

- creates the permissions (client roles of `reeve-api`), the module and platform
  realm roles, and the temporary `preset_*` roles;
- creates the `reeve-backend` service account with the default scopes `basic`, `roles`
  (puts its `realm-management` roles in the token) and `kc-admin-api` (Keycloak 26.8.2+
  rejects Admin API tokens without this scope's audience; the scope is built into
  realms on 26.8.2+, so this needs Keycloak 26.8.2 or later), and gives it the `realm-management`
  roles it needs. Keycloak generates its secret: read it in the admin console and
  set it as `LOB_KEYCLOAK_ADMIN_CLIENT_SECRET`;
- turns off full scope on `webclient`, so its tokens only identify the user, and
  removes the `organisations` scope from its default scopes.

[`004-reports-viewer-event-codes.json`](004-reports-viewer-event-codes.json):

- gives `reports_viewer` the permission `event_codes:view`: the chart of accounts
  screens list the reference codes. A role's `composites` list is desired state, so
  the file declares the role's whole list, not only the added permission;
- `003` declares the same list. Every run applies all files in order, so a role
  declared differently in two files would lose and regain the permission on every
  run. Keep the two in step when changing `reports_viewer`.

[`one-time/cardano-foundation-group-migration.json`](one-time/cardano-foundation-group-migration.json)
runs **once**, after the migrations above. It creates the group tree and moves the
`auditor`, `accountant`, `manager` and `admin` users to their preset. See
[Moving to the group model](#moving-to-the-group-model).

## Apply manually with Docker

Run from the repository root while Keycloak is running. The following command
uses a pinned keycloak-config-cli build for Keycloak 26 and connects to the
Keycloak container through the existing `lob` Docker network:

```bash
read -r -s -p 'Keycloak admin password: ' KEYCLOAK_PASSWORD && echo
export KEYCLOAK_PASSWORD

docker run --rm \
  --network lob \
  -e KEYCLOAK_URL=http://keycloak:8080 \
  -e KEYCLOAK_USER=admin \
  -e KEYCLOAK_PASSWORD \
  -e KEYCLOAK_LOGINREALM=master \
  -e KEYCLOAK_AVAILABILITYCHECK_ENABLED=true \
  -e KEYCLOAK_AVAILABILITYCHECK_TIMEOUT=120s \
  -e 'IMPORT_FILES_LOCATIONS=/config/*.json' \
  -e IMPORT_VALIDATE=true \
  -e IMPORT_CACHE_ENABLED=false \
  -e IMPORT_REMOTESTATE_ENABLED=true \
  -e IMPORT_MANAGED_CLIENT=NO_DELETE \
  -e IMPORT_MANAGED_CLIENTSCOPE=NO_DELETE \
  -e IMPORT_MANAGED_CLIENTSCOPEMAPPING=NO_DELETE \
  -e IMPORT_MANAGED_ROLE=NO_DELETE \
  -v "$PWD/keycloak-migrations/keycloak-config-cli:/config:ro" \
  adorsys/keycloak-config-cli:6.5.1-26.0.5

unset KEYCLOAK_PASSWORD
```

Adjust these values when applying to another environment:

- `--network lob`: Docker network containing Keycloak;
- `KEYCLOAK_URL`: URL reachable **from the CLI container**;
- `KEYCLOAK_USER` and `KEYCLOAK_LOGINREALM`: administrative login;
- image tag: keep the keycloak-config-cli build aligned with the Keycloak major
  version.

For an externally reachable Keycloak, omit `--network lob` and set
`KEYCLOAK_URL` to its HTTPS URL. Do not commit a password or client secret.

If the login fails with `Account is not fully set up`, the admin user needs an
email, first name and last name in the `master` realm. Realms created from
`keycloak-config/realm-export.json` can have this problem.

## Moving to the group model

Do this once on a realm that still has the `reeve_*` roles, and back up Keycloak
first:

1. Run the command above. It only adds roles and clients, and changes `webclient`.
2. Run the one-time file. Use the same command, with these two values changed:

   ```bash
   -e 'IMPORT_FILES_LOCATIONS=/config/one-time/*.json' \
   -e IMPORT_MANAGED_GROUP=NO_DELETE \
   ```

3. Set `LOB_KEYCLOAK_ADMIN_CLIENT_SECRET` and deploy the backend.
4. Add every user who isn't in the one-time file to groups, in the admin console:
   Users → the user → Groups → Join group. Use the table below. Until then they
   keep their account and password, but have no access, because the backend no
   longer reads the `reeve_*` roles.
5. When nobody needs them any more, delete the four `reeve_*` roles by hand. They
   record what each remaining user could do, so delete them last.

**Never run the one-time file a second time.** The `groups` key is authoritative:
it deletes every organisation under `/organisations` that isn't listed, including
the memberships of those groups, and resets the groups of the users it lists.
`IMPORT_MANAGED_GROUP=NO_DELETE` only protects top-level groups. It stays in
`one-time/` so that the regular `/config/*.json` command doesn't pick it up, and no
regular migration may declare `groups`.

The `organisations` user attribute is left in place: nothing reads it, and clearing
attributes from a file would also remove `job` and `phone`.

### Former roles

Under `/organisations/<organisation>`, either join the preset or the groups. Both
give the same access. Presets are a temporary shortcut: to separate review from
publishing for someone, move them to the specific groups instead.

| Former role | Preset | Groups | Also |
|---|---|---|---|
| `reeve_auditor` | `presets/auditor` | `transactions`, `reporting`, `funding`, `document_vault/editor` | |
| `reeve_accountant` | `presets/accountant` | `transactions/setup`, `transactions/importer`, `transactions/reviewer`, `transactions/reconciler`, `reporting/editor`, `funding/event_editor`, `funding/setup`, `document_vault/editor` | |
| `reeve_admin` | `presets/admin` | `org_admin`, `transactions/setup/erp`, `transactions/importer`, `transactions/reviewer`, `transactions/reconciler`, `transactions/publish_viewer`, `reporting/setup`, `reporting/publish_viewer`, `funding/project_manager`, `funding/event_editor`, `funding/publisher`, `funding/setup`, `document_vault/editor`, `document_vault/publisher`, `document_vault/admin` | `/platform/administrators` |
| `reeve_account_manager` | `presets/account_manager` | `org_admin`, `transactions/reconciler`, `transactions/importer`, `transactions/reviewer`, `transactions/publisher`, `transactions/dashboard_editor`, `transactions/setup/erp`, `reporting/editor`, `reporting/publisher`, `reporting/setup`, `funding/event_editor`, `funding/project_manager`, `funding/publisher`, `funding/setup`, `document_vault/editor`, `document_vault/publisher` | `/platform/administrators` |

## Safety settings

The manual command intentionally uses:

- `IMPORT_CACHE_ENABLED=false` so every invocation reconciles current state;
- `IMPORT_REMOTESTATE_ENABLED=true` so CLI-managed state is tracked in the
  realm;
- `no-delete` for clients, client scopes, and client-scope mappings so unrelated
  resources are not purged;
- `no-delete` for roles, because without it a file that declares roles deletes
  the roles created by the other files.

`no-delete` prevents deletion of unrelated clients/scopes, but the properties
of resources explicitly named by these files are still reconciled. In
particular, the `roles.protocolMappers`, `basic.protocolMappers`, and
`webclient.defaultClientScopes` lists are authoritative.

Apply and verify in a non-production environment first, and back up Keycloak
before the first production use.
