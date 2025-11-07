package com.adobe.aem.nodecounter.core.schedulers;

import com.adobe.aem.nodecounter.core.config.PageNodeCountSchedulerConfig;
import org.apache.sling.api.resource.*;
import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Component(service = Runnable.class, immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = PageNodeCountSchedulerConfig.class)
public class PageNodeCountJob implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(PageNodeCountJob.class);
    private static final String JOB_NAME = "PageNodeCountJob";

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private Scheduler scheduler;

    private String rootPath;
    private boolean enabled;
    private String schedulerExpression;
    private int highThreshold;
    private int mediumThreshold;

    @Activate
    @Modified
    protected void activate(PageNodeCountSchedulerConfig config) {
        this.rootPath = config.rootPath();
        this.enabled = config.enabled();
        this.schedulerExpression = config.scheduler_expression();
        this.highThreshold = config.highThreshold();
        this.mediumThreshold = config.mediumThreshold();
        
        LOG.info("Activating PageNodeCountJob - enabled: {}, rootPath: {}, expression: {}, highThreshold: {}, mediumThreshold: {}", 
                enabled, rootPath, schedulerExpression, highThreshold, mediumThreshold);
        
        // Remove existing scheduled job
        scheduler.unschedule(JOB_NAME);
        
        // Schedule with config expression if enabled
        if (enabled) {
            try {
                ScheduleOptions options = scheduler.EXPR(schedulerExpression);
                options.name(JOB_NAME);
                options.canRunConcurrently(false);
                scheduler.schedule(this, options);
                LOG.info("PageNodeCountJob successfully scheduled with cron expression: {}", schedulerExpression);
            } catch (Exception e) {
                LOG.error("Failed to schedule PageNodeCountJob with expression: {}", schedulerExpression, e);
            }
        } else {
            LOG.info("PageNodeCountJob is disabled via configuration");
        }
    }
    
    @Deactivate
    protected void deactivate() {
        LOG.info("Deactivating PageNodeCountJob");
        scheduler.unschedule(JOB_NAME);
        LOG.info("PageNodeCountJob unscheduled");
    }

    @Override
    public void run() {
        if (!enabled) {
            LOG.debug("PageNodeCountJob skipped - job is disabled");
            return;
        }
        
        long startTime = System.currentTimeMillis();
        LOG.info("Starting PageNodeCountJob execution for root path: {}", rootPath);

        Map<String, Object> authInfo = Collections.singletonMap(
            ResourceResolverFactory.SUBSERVICE, "nodecount-updater");
        
        try (ResourceResolver rr = resolverFactory.getServiceResourceResolver(authInfo)) {
            if(rr == null) {
                LOG.error("Failed to obtain ResourceResolver. Check service user configuration.");
                return;
            }
            Resource rootResource = rr.getResource(rootPath);
            if (rootResource == null) {
                LOG.warn("Root path not found: {}. Job execution aborted.", rootPath);
                return;
            }
            
            LOG.debug("Root resource found: {}. Starting page traversal...", rootPath);
            AtomicInteger pagesProcessed = new AtomicInteger(0);
            walkAndCount(rr, rootResource, pagesProcessed);
            
            long duration = System.currentTimeMillis() - startTime;
            LOG.info("PageNodeCountJob completed successfully. Pages processed: {}, Duration: {} ms", 
                    pagesProcessed.get(), duration);
            
        } catch (LoginException e) {
            LOG.error("Failed to obtain ResourceResolver. Check service user configuration.", e);
        } catch (Exception e) {
            LOG.error("Unexpected error running PageNodeCountJob for path: {}", rootPath, e);
        }
    }

    private void walkAndCount(ResourceResolver rr, Resource root, AtomicInteger pagesProcessed) {
        if (root == null) {
            LOG.debug("Null resource encountered, skipping");
            return;
        }

        Iterator<Resource> children = root.listChildren();
        while (children.hasNext()) {
            Resource child = children.next();
            String resourceType = child.getResourceType();
            String primaryType = child.getValueMap().get("jcr:primaryType", String.class);
            
            if ("cq:Page".equals(resourceType) || "cq:Page".equals(primaryType)) {
                String pagePath = child.getPath();
                LOG.debug("Processing page: {}", pagePath);
                
                Resource jcrContent = child.getChild("jcr:content");
                if (jcrContent != null) {
                    // Get the full node count (without limit)
                    long nodeCount = countAllDescendants(jcrContent);
                    String complexity = determineComplexity(jcrContent);
                    LOG.debug("Node count: {}, Complexity level for {}: {}", nodeCount, pagePath, complexity);
                    
                    try {
                        ModifiableValueMap props = jcrContent.adaptTo(ModifiableValueMap.class);
                        if (props != null) {
                            String oldComplexity = props.get("complexity", String.class);
                            Long oldNodeCount = props.get("nodeCount", Long.class);
                            
                            // Always overwrite the properties, even if they exist
                            props.put("complexity", complexity);
                            props.put("nodeCount", nodeCount);
                            rr.commit();
                            pagesProcessed.incrementAndGet();
                            
                            LOG.debug("Set nodeCount={}, complexity={} for page: {} (old: nodeCount={}, complexity={})", 
                                    nodeCount, complexity, pagePath, oldNodeCount, oldComplexity);
                        } else {
                            LOG.warn("Could not adapt jcr:content to ModifiableValueMap for: {}", pagePath);
                        }
                    } catch (PersistenceException e) {
                        LOG.warn("Failed to persist complexity for page: {}", pagePath, e);
                    }
                } else {
                    LOG.debug("No jcr:content found for page: {}", pagePath);
                }
            }
            // Recursively descend into this node
            walkAndCount(rr, child, pagesProcessed);
        }
    }

    /**
     * Determines the complexity level of a page based on node count.
     * Uses early exit optimization to stop counting once a category is determined.
     * 
     * @param resource The jcr:content resource of the page
     * @return Complexity level: "high" (>highThreshold), "medium" (>mediumThreshold), or "low" (<=mediumThreshold)
     */
    private String determineComplexity(Resource resource) {
        if (resource == null) {
            return "low";
        }
        
        // Count up to highThreshold + 1 to determine if page is high complexity
        int count = countWithLimit(resource, highThreshold + 1);
        
        if (count > highThreshold) {
            return "high";
        } else if (count > mediumThreshold) {
            return "medium";
        } else {
            return "low";
        }
    }
    
    /**
     * Counts all descendants of a resource (full count, no limit).
     * Used for reporting the actual node count.
     * 
     * @param resource The resource to count descendants for
     * @return The total count of all descendants
     */
    private long countAllDescendants(Resource resource) {
        if (resource == null) {
            return 0;
        }
        
        long count = 0;
        for (Resource child : resource.getChildren()) {
            // Skip counting if this is a cq:Page (don't count nested pages)
            String primaryType = child.getValueMap().get("jcr:primaryType", String.class);
            if ("cq:Page".equals(primaryType)) {
                continue; // Don't count child pages or their descendants
            }
            
            count++; // Count this child
            
            // Recursively count descendants
            count += countAllDescendants(child);
        }
        
        return count;
    }
    
    /**
     * Counts descendants up to a specified limit for optimization.
     * Stops counting once the limit is reached.
     * 
     * @param resource The resource to count descendants for
     * @param limit The maximum count before stopping
     * @return The count of descendants (may be less than actual if limit reached)
     */
    private int countWithLimit(Resource resource, int limit) {
        if (resource == null) {
            return 0;
        }
        
        int count = 0;
        for (Resource child : resource.getChildren()) {
            // Early exit if we've reached the limit
            if (count >= limit) {
                return count;
            }
            
            // Skip counting if this is a cq:Page (don't count nested pages)
            String primaryType = child.getValueMap().get("jcr:primaryType", String.class);
            if ("cq:Page".equals(primaryType)) {
                continue; // Don't count child pages or their descendants
            }
            
            count++; // Count this child
            
            // Check limit again before recursing
            if (count >= limit) {
                return count;
            }
            
            count += countWithLimit(child, limit - count); // Count descendants with remaining limit
        }
        return count;
    }
}

