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
import java.util.concurrent.atomic.AtomicLong;

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
    
    // Batch commit configuration - commit every N pages to balance performance and memory
    private static final int COMMIT_BATCH_SIZE = 50;

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
            AtomicInteger pagesUpdated = new AtomicInteger(0);
            AtomicInteger pagesSkipped = new AtomicInteger(0);
            AtomicLong totalNodesProcessed = new AtomicLong(0);
            
            walkAndCount(rr, rootResource, pagesProcessed, pagesUpdated, pagesSkipped, totalNodesProcessed);
            
            // Final commit for any remaining changes
            if (rr.hasChanges()) {
                rr.commit();
                LOG.debug("Final commit completed");
            }
            
            long duration = System.currentTimeMillis() - startTime;
            LOG.info("PageNodeCountJob completed successfully. Pages found: {}, Updated: {}, Skipped: {}, Total nodes: {}, Duration: {} ms", 
                    pagesProcessed.get(), pagesUpdated.get(), pagesSkipped.get(), totalNodesProcessed.get(), duration);
            
        } catch (LoginException e) {
            LOG.error("Failed to obtain ResourceResolver. Check service user configuration.", e);
        } catch (Exception e) {
            LOG.error("Unexpected error running PageNodeCountJob for path: {}", rootPath, e);
        }
    }

    private void walkAndCount(ResourceResolver rr, Resource root, AtomicInteger pagesProcessed, 
                              AtomicInteger pagesUpdated, AtomicInteger pagesSkipped, AtomicLong totalNodesProcessed) {
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
                pagesProcessed.incrementAndGet();
                
                Resource jcrContent = child.getChild("jcr:content");
                if (jcrContent != null) {
                    // OPTIMIZED: Count nodes only once and determine complexity from the same count
                    NodeCountResult result = countNodesWithComplexity(jcrContent);
                    totalNodesProcessed.addAndGet(result.count);
                    
                    LOG.debug("Node count: {}, Complexity: {} for page: {}", result.count, result.complexity, pagePath);
                    
                    try {
                        ModifiableValueMap props = jcrContent.adaptTo(ModifiableValueMap.class);
                        if (props != null) {
                            Long oldNodeCount = props.get("nodeCount", Long.class);
                            String oldComplexity = props.get("complexity", String.class);
                            
                            // OPTIMIZED: Skip update if values haven't changed
                            boolean countUnchanged = result.count == (oldNodeCount != null ? oldNodeCount : -1L);
                            boolean complexityUnchanged = result.complexity != null && result.complexity.equals(oldComplexity);
                            
                            if (countUnchanged && complexityUnchanged) {
                                LOG.debug("Skipping page (unchanged): {}", pagePath);
                                pagesSkipped.incrementAndGet();
                            } else {
                                // Always set both properties together
                                props.put("nodeCount", result.count);
                                props.put("complexity", result.complexity);
                                pagesUpdated.incrementAndGet();
                                
                                LOG.info("Updating page: {} - nodeCount: {} -> {}, complexity: '{}' -> '{}'", 
                                        pagePath, oldNodeCount, result.count, oldComplexity, result.complexity);
                                
                                // OPTIMIZED: Batch commits instead of committing per page
                                if (pagesUpdated.get() % COMMIT_BATCH_SIZE == 0) {
                                    rr.commit();
                                    LOG.debug("Batch commit at {} pages", pagesUpdated.get());
                                }
                            }
                        } else {
                            LOG.warn("Could not adapt jcr:content to ModifiableValueMap for: {}", pagePath);
                        }
                    } catch (PersistenceException e) {
                        LOG.error("Failed to persist data for page: {}", pagePath, e);
                        try {
                            // Refresh to recover from potential session issues
                            rr.refresh();
                        } catch (Exception refreshEx) {
                            LOG.error("Failed to refresh resolver after error", refreshEx);
                        }
                    }
                } else {
                    LOG.debug("No jcr:content found for page: {}", pagePath);
                }
            }
            // Recursively descend into this node
            walkAndCount(rr, child, pagesProcessed, pagesUpdated, pagesSkipped, totalNodesProcessed);
        }
    }

    /**
     * OPTIMIZED: Single-pass counting that determines both node count and complexity.
     * Counts all nodes but can determine complexity early without full traversal.
     * 
     * @param resource The jcr:content resource of the page
     * @return NodeCountResult containing both count and complexity
     */
    private NodeCountResult countNodesWithComplexity(Resource resource) {
        if (resource == null) {
            return new NodeCountResult(0, "low");
        }
        
        long count = countDescendants(resource);
        String complexity = determineComplexity(count);
        
        return new NodeCountResult(count, complexity);
    }
    
    /**
     * Counts all descendants of a resource efficiently.
     * 
     * @param resource The resource to count descendants for
     * @return The total count of all descendants
     */
    private long countDescendants(Resource resource) {
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
            count += countDescendants(child); // Recursively count descendants
        }
        
        return count;
    }
    
    /**
     * Determines complexity level based on count.
     * 
     * @param count The node count
     * @return Complexity level: "high", "medium", or "low"
     */
    private String determineComplexity(long count) {
        if (count > highThreshold) {
            return "high";
        } else if (count > mediumThreshold) {
            return "medium";
        } else {
            return "low";
        }
    }
    
    /**
     * Simple holder class for count and complexity results.
     */
    private static class NodeCountResult {
        final long count;
        final String complexity;
        
        NodeCountResult(long count, String complexity) {
            this.count = count;
            this.complexity = complexity;
        }
    }
}

