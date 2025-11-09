# Quick Start Guide - AEM Node Counter

This guide will help you quickly install and configure the AEM Node Counter package.

## Step 1: Build the Package

```bash
cd /path/to/node-counter
mvn clean install
```

The installable package will be created at:
```
all/target/node-counter.all-1.0.0-SNAPSHOT.zip
```

## Step 2: Install the Package

### Option A: Via Package Manager UI

1. Open Package Manager: http://localhost:4502/crx/packmgr
2. Click "Upload Package"
3. Select `all/target/node-counter.all-1.0.0-SNAPSHOT.zip`
4. Click "Install"

### Option B: Via Maven

```bash
mvn clean install -PautoInstallSinglePackage
```

## Step 3: Configure the Scheduler

1. Open Configuration Manager: http://localhost:4502/system/console/configMgr
2. Find: **AEM Node Counter Scheduler Config**
3. Configure settings:
   - **Enabled**: ✓ (checked)
   - **Content Root Path**: `/content/wknd` (or your site path)
   - **Scheduler Cron Expression**: `0 * * * * ?` (every minute for testing)
   - **High Complexity Threshold**: `2048`
   - **Medium Complexity Threshold**: `1024`
4. Click "Save"

## Step 4: Verify Installation

### Check the Scheduler is Running

1. Open Sling Log: http://localhost:4502/system/console/slinglog
2. Filter by: `com.adobe.aem.nodecounter`
3. Look for: `"PageNodeCountJob successfully scheduled"`

### Wait for Job Execution

The job will run based on your cron expression. For testing with `0 * * * * ?`, wait up to 1 minute.

Watch the logs for:
```
PageNodeCountJob completed successfully. Pages processed: X
```

### Verify Complexity Property is Set

1. Open CRXDE Lite: http://localhost:4502/crx/de
2. Navigate to a page: `/content/wknd/us/en/jcr:content`
3. Look for the `complexity` property (should be "low", "medium", or "high")

## Step 5: View Results in Sites Admin

1. Navigate to Sites: http://localhost:4502/sites.html/content/wknd
2. Switch to **Column View** (icon at top right)
3. Click the **View Settings** icon (three horizontal lines)
4. Ensure **"Complexity"** is checked
5. You should now see the complexity level for each page

## Step 6: Query Pages by Complexity

Once the scheduler has run, query pages using standard AEM tools:

### Using QueryBuilder

```bash
curl -u admin:admin "http://localhost:4502/bin/querybuilder.json?path=/content/wknd&type=cq:Page&property=jcr:content/complexity&property.value=high"
```

### Using CRXDE Lite

Navigate to: http://localhost:4502/crx/de

Use JCR-SQL2 query:
```sql
SELECT * FROM [cq:PageContent] AS pageContent
WHERE ISDESCENDANTNODE(pageContent, '/content/wknd')
AND pageContent.[complexity] = 'high'
```

## Common Issues & Solutions

### Issue: Scheduler Not Running

**Check:**
- OSGi configuration is enabled
- Cron expression is valid
- Check logs for errors

**Solution:**
```bash
# View scheduler status
http://localhost:4502/system/console/status-Sling
```

### Issue: Complexity Property Not Set

**Check:**
- Scheduler has run at least once
- Root path exists and contains pages
- Service user has permissions

**Solution:**
```bash
# Manually trigger job (or wait for next scheduled run)
# Check logs: http://localhost:4502/system/console/slinglog
```

### Issue: Permission Errors

**Check:**
- Service user `nodecount-updater` exists
- Service user mapping is configured

**Solution:**
```bash
# Verify service user
http://localhost:4502/security/users.html
# Search for: nodecount-updater
```

### Issue: Column Not Showing in Sites Admin

**Check:**
- ui.apps package is installed
- Available columns configuration is deployed

**Solution:**
- Reinstall the package
- Clear browser cache
- Check filter.xml includes `/apps/wcm/core/content/common/availablecolumns`

## Testing Different Cron Schedules

For development/testing, use frequent schedules:

- **Every minute**: `0 * * * * ?`
- **Every 5 minutes**: `0 */5 * * * ?`
- **Every 30 minutes**: `0 */30 * * * ?`

For production, use less frequent schedules:

- **Daily at 2 AM**: `0 0 2 * * ?`
- **Every 6 hours**: `0 0 */6 * * ?`
- **Weekly on Sunday at midnight**: `0 0 0 ? * SUN`

## Next Steps

1. **Adjust Thresholds**: Fine-tune complexity thresholds based on your content
2. **Schedule Optimization**: Set appropriate cron schedule for production
3. **Query Complex Pages**: Use QueryBuilder to find high complexity pages
4. **Performance Optimization**: Consider content structure improvements for complex pages

## Need Help?

- Check logs: http://localhost:4502/system/console/slinglog
- Review README.md for detailed documentation
- Verify OSGi configuration: http://localhost:4502/system/console/configMgr

## Uninstall

To remove the package:

1. Go to Package Manager: http://localhost:4502/crx/packmgr
2. Find "AEM Node Counter - All"
3. Click "More" → "Uninstall"
4. Click "More" → "Delete"

