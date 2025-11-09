# AEM Node Counter - Project Structure

This document describes the complete structure of the AEM Node Counter standalone package.

## Directory Structure

```
node-counter/
├── pom.xml                          # Parent POM (reactor build)
├── README.md                        # Complete documentation
├── QUICK_START.md                   # Quick start guide
├── .gitignore                       # Git ignore rules
│
├── core/                            # OSGi Bundle (Java code)
│   ├── pom.xml
│   └── src/main/java/com/adobe/aem/nodecounter/core/
│       ├── config/
│       │   └── PageNodeCountSchedulerConfig.java      # OSGi configuration interface
│       ├── schedulers/
│       │   └── PageNodeCountJob.java                  # Scheduled job to analyze pages
│       └── services/
│           └── NodeCountInfoProvider.java             # Service for node count info
│
├── ui.apps.structure/               # Repository structure package
│   ├── pom.xml
│   └── src/main/content/
│       ├── META-INF/vault/
│       │   └── filter.xml                             # Filter definition
│       └── jcr_root/apps/
│           ├── nodecounter/.content.xml
│           └── nodecounter-packages/.content.xml
│
├── ui.apps/                         # UI components package
│   ├── pom.xml
│   └── src/main/content/
│       ├── META-INF/vault/
│       │   └── filter.xml                             # Filter for availablecolumns
│       └── jcr_root/apps/wcm/core/content/common/
│           └── availablecolumns/.content.xml          # Sites admin column definitions
│
├── ui.config/                       # OSGi configurations package
│   ├── pom.xml
│   └── src/main/content/
│       ├── META-INF/vault/
│       │   └── filter.xml                             # Filter for OSGi configs
│       └── jcr_root/apps/nodecounter/osgiconfig/
│           ├── config/                                # Run mode: all
│           │   ├── .content.xml
│           │   └── org.apache.sling.jcr.repoinit.RepositoryInitializer~nodecounter.cfg.json
│           └── config.author/                         # Run mode: author
│               ├── .content.xml
│               ├── com.adobe.aem.nodecounter.core.schedulers.PageNodeCountJob.cfg.json
│               └── org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-nodecounter.cfg.json
│
└── all/                             # Container package (embeds everything)
    ├── pom.xml
    └── src/main/content/
        ├── META-INF/vault/
        │   └── filter.xml                             # Filter for package install location
        └── jcr_root/apps/nodecounter-packages/.content.xml
```

## Module Descriptions

### Parent POM (`pom.xml`)

- **Purpose**: Reactor build configuration
- **Key Properties**:
  - Group ID: `com.adobe.aem`
  - Artifact ID: `node-counter`
  - Version: `1.0.0-SNAPSHOT`
  - AEM SDK API: `2025.10.22943.20251009T135918Z-250900`
- **Modules**: core, ui.apps.structure, ui.apps, ui.config, all
- **Build Profiles**:
  - `autoInstallBundle`: Install bundles only
  - `autoInstallPackage`: Install packages to author

### Core Bundle (`core/`)

OSGi bundle containing all Java code:

#### PageNodeCountSchedulerConfig.java
- **Type**: OSGi Metatype configuration interface
- **Purpose**: Configuration for the scheduled job
- **Configurable Properties**:
  - `enabled`: Enable/disable the job
  - `rootPath`: Content root path to analyze
  - `scheduler_expression`: Cron expression
  - `highThreshold`: Node count for high complexity
  - `mediumThreshold`: Node count for medium complexity

#### PageNodeCountJob.java
- **Type**: Runnable OSGi component with scheduler
- **Purpose**: Scheduled job to traverse pages and set complexity
- **Key Features**:
  - Uses service user `nodecount-updater`
  - Traverses all pages under configured root
  - Counts nodes per page (excluding nested pages)
  - Sets `complexity` property on `jcr:content`
  - Early exit optimization for performance
  - Comprehensive logging

#### NodeCountInfoProvider.java
- **Type**: OSGi Service
- **Purpose**: Provides node count and complexity information for pages
- **Features**:
  - Retrieves stored complexity and node count properties
  - Returns page metadata (title, path, last modified)
  - Applies threshold-based complexity calculation
  - Used by Sites admin columns and other components

### UI Apps Structure (`ui.apps.structure/`)

Defines repository structure:
- `/apps/nodecounter` folder
- `/apps/nodecounter-packages` folder

### UI Apps (`ui.apps/`)

UI components and definitions:
- **Sites Admin Columns**: Adds "Complexity" column to Sites admin
- **Location**: `/apps/wcm/core/content/common/availablecolumns`
- **Features**:
  - Shows complexity property in column view
  - Sortable column
  - Part of metadata column group

### UI Config (`ui.config/`)

OSGi configurations:

#### Repository Initialization (run mode: all)
- Creates service user: `nodecount-updater`
- Grants permissions: `jcr:read,jcr:write,jcr:modifyProperties,rep:write` on `/content`

#### Scheduler Configuration (run mode: author)
- Configures PageNodeCountJob
- Environment variable support
- Default values provided

#### Service User Mapping (run mode: author)
- Maps `node-counter.core:nodecount-updater` to system user
- Required for ResourceResolverFactory

### All Package (`all/`)

Container package that embeds:
1. **node-counter.core** (JAR) → `/apps/nodecounter-packages/application/install`
2. **node-counter.ui.apps** (ZIP) → `/apps/nodecounter-packages/application/install`
3. **node-counter.ui.config** (ZIP) → `/apps/nodecounter-packages/application/install`

**Output**: `node-counter.all-1.0.0-SNAPSHOT.zip`

## Package Install Locations

When installed on AEM:

```
/apps/
├── nodecounter/                     # From ui.apps.structure
│   └── osgiconfig/                  # From ui.config
│       ├── config/
│       │   └── org.apache.sling.jcr.repoinit.RepositoryInitializer~nodecounter.cfg.json
│       └── config.author/
│           ├── com.adobe.aem.nodecounter.core.schedulers.PageNodeCountJob.cfg.json
│           └── org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-nodecounter.cfg.json
│
├── nodecounter-packages/            # From ui.apps.structure & all
│   └── application/
│       └── install/
│           ├── node-counter.core-1.0.0-SNAPSHOT.jar
│           ├── node-counter.ui.apps-1.0.0-SNAPSHOT.zip
│           └── node-counter.ui.config-1.0.0-SNAPSHOT.zip
│
└── wcm/core/content/common/         # From ui.apps
    └── availablecolumns/.content.xml

/system/
└── nodecount/                       # Service user location
    └── nodecount-updater            # System user
```

## Build Order

Maven reactor builds in this order:

1. **core** - Compiles Java, creates JAR bundle
2. **ui.apps.structure** - Creates structure package
3. **ui.apps** - Creates apps package (depends on structure)
4. **ui.config** - Creates config package (depends on structure)
5. **all** - Embeds all packages (depends on core, ui.apps, ui.config)

## Key Dependencies

### Maven Dependencies
- `com.adobe.aem:aem-sdk-api` - AEM SDK API (provided)
- `biz.aQute.bnd:bnd-maven-plugin` - OSGi bundle generation
- `org.apache.jackrabbit:filevault-package-maven-plugin` - Package creation
- `com.day.jcr.vault:content-package-maven-plugin` - Package installation

### Runtime Dependencies
- Sling Scheduler API - Job scheduling
- Sling Resource API - JCR access
- OSGi Component API - Service management
- CQ WCM API - Page management
- SLF4J - Logging

## Configuration Files

### OSGi Configurations
1. **Repository Init**: Creates service user and ACLs
2. **Scheduler Config**: Job scheduling and thresholds
3. **Service User Mapping**: Maps bundle to system user

### Vault Configurations
Each module has:
- `META-INF/vault/filter.xml` - Defines what content is included in package
- `.content.xml` files - Node definitions

## Package Metadata

### All Package Properties
- **Group**: `com.adobe.aem`
- **Name**: `node-counter.all`
- **Version**: `1.0.0-SNAPSHOT`
- **Type**: container
- **Cloud Manager Target**: none

### Build Output
- **Location**: `all/target/`
- **Filename**: `node-counter.all-1.0.0-SNAPSHOT.zip`
- **Contents**: All sub-packages and bundles

## Development Workflow

1. **Make Changes**: Edit Java files or configurations
2. **Build**: `mvn clean install`
3. **Deploy**: `mvn clean install -PautoInstallSinglePackage`
4. **Verify**: Check logs and test functionality
5. **Iterate**: Repeat as needed

## Production Deployment

1. **Build**: `mvn clean package`
2. **Upload**: Via Package Manager or Cloud Manager
3. **Install**: Install the all package
4. **Configure**: Adjust OSGi configurations via Config Manager
5. **Monitor**: Watch logs for successful execution

## Maintenance

### Updating Thresholds
- Edit OSGi config via Config Manager
- Or update `ui.config/.../PageNodeCountJob.cfg.json`
- Rebuild and redeploy

### Changing Package Version
- Update version in parent `pom.xml`
- Maven will propagate to all modules
- Rebuild all packages

### Adding Features
- Add Java classes to `core/src/main/java/`
- Add configurations to `ui.config/`
- Update dependencies in respective POMs
- Rebuild and test

