# GitLab Connector for MidPoint

This is an upgraded identity connector for managing GitLab users, groups, and projects via the GitLab REST API.  
Originally developed by Evolveum for GitLab version 9.5.0 and MidPoint 3.5 (`update-delta-op` branch), the connector has now been refactored to support GitLab 18.1+ and MidPoint 4.4+.

## Compatibility

- GitLab version: 18.1 and newer
- MidPoint version: 4.4 and newer (tested on 4.9)
- Connector version: 2.0.2
- ConnId framework: 1.6.0.1
- REST base connector: 1.5.2.0

## Capabilities and Features

| Feature                    | Supported |
|---------------------------|-----------|
| Provisioning              | Yes       |
| Live Synchronization      | No        |
| Password Management       | Yes       |
| Activation                | Yes       |
| Paging Support            | Yes       |
| Native Attribute Names    | Yes       |
| Scripting Support         | No        |

## Enhancements and Customizations

The connector was extended and improved to include the following functionality:

- Support for **service accounts** via the `service_accounts` API
    - Not all service-account-specific endpoints are used (e.g. the `service_accounts` update endpoint is not available),  
      but **update operations are fully supported** via the standard `/users` endpoint.
    - When provisioning a service account, the optional attribute `groupId` can be used:
        - If `groupId` is set, the service account will be created inside the specified GitLab group.
        - If `groupId` is empty or not defined, the service account will be created globally (outside of any group).


- Improved paging support for large datasets
    - All list-based queries (e.g. users, groups, projects) now use GitLab’s HTTP pagination headers (`X-Next-Page`, `X-Total-Pages`, etc.) instead of controlling page offsets.

### Account Filtering Logic

- The connector includes a configuration option `onlyHumanAccounts` in the resource object type.
    - When set to `true` (default), only **human accounts** are returned under the default `AccountObjectClass`.
    - When set to `false`, **bot and service accounts** will also be included in search results.
- **Recommended approach**: Define a separate object type (e.g. `serviceAccount`) to handle non-human identities.
    - Use the default object type for human accounts.
    - This ensures clear separation and simplifies provisioning and filtering logic in MidPoint.


## Known Limitations

- The following filters are not supported: `EndsWithFilter`, `StartsWithFilter`, `AndFilter`, `OrFilter`, `NotFilter`
- Avatars are supported only for inbound mappings (not outbound)
- Project creation is only supported **under the user account** associated with the access token
    - Creating a project directly inside a GitLab group (i.e. nested project) is not supported
    - Projects created from MidPoint will appear as **personal projects** under the token's user

## Configuration

The connector uses a GitLab **private token** for authentication. This token must belong to a GitLab administrator. You can generate it in the GitLab GUI under the user's personal access token settings.

### Required Properties

| Property       | Description                        |
|----------------|------------------------------------|
| `privateToken` | Admin access token from GitLab     |
| `loginUrl`     | Base URL of your GitLab instance   |

### Example Configuration

```properties
privateToken=XXXXXXXXXXXXXXXXXXXX
loginUrl=https://gitlab.myorg.com
```
## Sample Configurations

The `gitlab/samples` directory contains two sets of MidPoint resource examples:

- `legacy/`: original sample configurations from the Evolveum project.  
  - These are **unchanged** and may **not work as-is** with newer MidPoint versions.
  - Use for historical reference only.

- `v4.9+/`: updated resource definitions compatible with **MidPoint 4.9 and newer**.
  - Includes working association definitions and schema-compliant structures.
  - These samples are ready to use out of the box with the upgraded GitLab connector.

# 🔐 CI/CD: Setting up `GITLAB_TOKEN` for GitLab Release

To allow the GitLab CI pipeline to automatically create a release when a tag is pushed (e.g. `v1.2.3`), you must define a CI/CD variable named `GITLAB_TOKEN`.

## 📌 Steps:

1. Go to your GitLab project
2. Open **Settings → CI/CD**
3. Expand the **Variables** section
4. Click **“Add variable”**
5. Fill in the following fields:

| Field        | Value                                                  |
|--------------|---------------------------------------------------------|
| **Key**      | `GITLAB_TOKEN`                                          |
| **Value**    | Your Personal Access Token or Project Access Token      |
| **Type**     | Environment variable (default)                          |
| **Masked**   | ✅ Yes                                                  |
| **Protected**| ✅ Yes (if using protected branches/tags)               |

## ✅ Recommended token types and permissions:

| Token Type           | Where to create it                                             | Required Scope |
|----------------------|----------------------------------------------------------------|----------------|
| Personal Access Token| [https://gitlab.com/-/profile/personal_access_tokens](https://gitlab.com/-/profile/personal_access_tokens) | `api`          |
| Project Access Token | **Project → Settings → Access Tokens**                        | `api`          |

Make sure to **store the token securely** and copy it only once. The token will be used by the pipeline to authenticate with the GitLab API when creating the release.

---

## ℹ️ Note about GitHub

For GitHub Actions, no manual token setup is required. A built-in `GITHUB_TOKEN` is automatically provided and used to authenticate GitHub API calls such as creating a release.
