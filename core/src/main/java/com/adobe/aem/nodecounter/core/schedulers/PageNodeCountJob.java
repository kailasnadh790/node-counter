package com.adobe.aem.nodecounter.core.schedulers;

import com.adobe.aem.nodecounter.core.config.PageNodeCountSchedulerConfig;
import org.apache.sling.api.resource.*;
import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.query.Query;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
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

    // Configuration properties
    private String rootPath;
    private boolean enabled;
    private String schedulerExpression;
    private int highThreshold;
    private int mediumThreshold;
    private int threadPoolSize;
    private int maxPagesPerRun;
    private int batchCommitSize;
    private boolean processOnlyModified;
    private int modifiedSinceHours;
    
    // Track previous enabled state to detect enable transitions
    private boolean previouslyEnabled = false;
    
    // Thread pool for parallel processing
    private ExecutorService executorService;

    @Activate
    @Modified
    protected void activate(PageNodeCountSchedulerConfig config) {
        // Detect if enabled is changing from false to true
        boolean wasDisabled = !this.previouslyEnabled;
        boolean nowEnabled = config.enabled();
        boolean justEnabled = wasDisabled && nowEnabled;
        
        this.rootPath = config.rootPath();
        this.enabled = config.enabled();
        this.schedulerExpression = config.scheduler_expression();
        this.highThreshold = config.highThreshold();
        this.mediumThreshold = config.mediumThreshold();
        this.threadPoolSize = config.threadPoolSize();
        this.maxPagesPerRun = config.maxPagesPerRun();
        this.batchCommitSize = config.batchCommitSize();
        this.processOnlyModified = config.processOnlyModified();
        this.modifiedSinceHours = config.modifiedSinceHours();
        
        LOG.info("Activating PageNodeCountJob - enabled: {}, rootPath: {}, expression: {}, threads: {}, maxPages: {}, batchSize: {}, onlyModified: {}", 
                enabled, rootPath, schedulerExpression, threadPoolSize, maxPagesPerRun, batchCommitSize, processOnlyModified);
        
        // Initialize thread pool
        if (executorService != null) {
            // Graceful shutdown without interrupting - avoid repository corruption
            executorService.shutdown();
            LOG.info("Initiated graceful shutdown of previous thread pool");
            // Don't wait or interrupt - let threads finish naturally
        }
        executorService = Executors.newFixedThreadPool(threadPoolSize, 
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "PageNodeCounter-" + counter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            });
        
        // Remove existing scheduled jobs
        scheduler.unschedule(JOB_NAME);
        scheduler.unschedule(JOB_NAME + ".immediate");
        
        // Schedule with config expression if enabled
        if (enabled) {
            try {
                // If job was just enabled (not initial activation), run immediately
                if (justEnabled) {
                    ScheduleOptions immediateOptions = scheduler.NOW();
                    immediateOptions.name(JOB_NAME + ".immediate");
                    immediateOptions.canRunConcurrently(false);
                    scheduler.schedule(this, immediateOptions);
                    LOG.info("PageNodeCountJob enabled - scheduling immediate execution");
                }
                
                // Schedule recurring cron job
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
        
        // Update previous state
        this.previouslyEnabled = enabled;
    }
    
    @Deactivate
    protected void deactivate() {
        LOG.info("Deactivating PageNodeCountJob");
        scheduler.unschedule(JOB_NAME);
        scheduler.unschedule(JOB_NAME + ".immediate");
        
        if (executorService != null) {
            LOG.info("Shutting down thread pool gracefully...");
            executorService.shutdown();
            
            try {
                // Wait for graceful termination, but don't interrupt threads
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    LOG.warn("Thread pool did not terminate within 30 seconds. " +
                            "Threads will finish naturally (daemon threads will be cleaned up by JVM).");
                }
            } catch (InterruptedException e) {
                // Don't interrupt worker threads - just log and continue
                LOG.warn("Deactivation interrupted while waiting for thread pool shutdown. " +
                        "Worker threads will continue and finish naturally.");
                // Note: We intentionally do NOT call Thread.currentThread().interrupt()
                // or executorService.shutdownNow() to avoid corrupting repository sessions
            }
        }
        
        // Reset state
        this.previouslyEnabled = false;
        
        LOG.info("PageNodeCountJob deactivation complete");
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
            
            // Discover pages using query (much faster than traversal)
            List<String> pagePaths = discoverPages(rr);
            
            if (pagePaths.isEmpty()) {
                LOG.info("No pages found to process");
                return;
            }
            
            LOG.info("Found {} pages to process", pagePaths.size());
            
            // Limit pages if configured
            if (maxPagesPerRun > 0 && pagePaths.size() > maxPagesPerRun) {
                LOG.info("Limiting processing to {} pages (found {})", maxPagesPerRun, pagePaths.size());
                pagePaths = pagePaths.subList(0, maxPagesPerRun);
            }
            
            // Process pages in parallel
            AtomicInteger pagesProcessed = new AtomicInteger(0);
            AtomicInteger pagesUpdated = new AtomicInteger(0);
            AtomicInteger pagesSkipped = new AtomicInteger(0);
            AtomicInteger pagesFailed = new AtomicInteger(0);
            AtomicLong totalNodesProcessed = new AtomicLong(0);
            
            // Split pages into batches for better control
            int batchSize = Math.max(10, pagePaths.size() / (threadPoolSize * 4));
            List<List<String>> batches = partition(pagePaths, batchSize);
            
            LOG.info("Processing {} pages in {} batches using {} threads", 
                    pagePaths.size(), batches.size(), threadPoolSize);
            
            // Submit batches to thread pool
            List<Future<?>> futures = new ArrayList<>();
            for (List<String> batch : batches) {
                Future<?> future = executorService.submit(() -> 
                    processBatch(batch, pagesProcessed, pagesUpdated, pagesSkipped, 
                                pagesFailed, totalNodesProcessed));
                futures.add(future);
            }
            
            // Wait for all batches to complete
            for (Future<?> future : futures) {
                try {
                    future.get(30, TimeUnit.MINUTES); // Timeout per batch
                } catch (TimeoutException e) {
                    LOG.error("Batch processing timed out after 30 minutes", e);
                    // Use cancel(false) to avoid interrupting - prevents repository corruption
                    future.cancel(false);
                } catch (Exception e) {
                    LOG.error("Error waiting for batch completion", e);
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            LOG.info("PageNodeCountJob completed. Pages: {} total, {} updated, {} skipped, {} failed | " +
                    "Nodes: {} | Duration: {} ms ({} sec)", 
                    pagesProcessed.get(), pagesUpdated.get(), pagesSkipped.get(), pagesFailed.get(),
                    totalNodesProcessed.get(), duration, duration / 1000);
            
        } catch (LoginException e) {
            LOG.error("Failed to obtain ResourceResolver. Check service user configuration.", e);
        } catch (Exception e) {
            LOG.error("Unexpected error running PageNodeCountJob for path: {}", rootPath, e);
        }
    }

    /**
     * Discovers pages using JCR query (much faster than tree traversal)
     */
    private List<String> discoverPages(ResourceResolver rr) {
        List<String> pagePaths = new ArrayList<>();
        
        try {
            StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append("SELECT * FROM [cq:Page] WHERE ISDESCENDANTNODE([")
                       .append(rootPath)
                       .append("])");
            
            // Optional: Filter by modification date for incremental processing
            if (processOnlyModified && modifiedSinceHours > 0) {
                Calendar cutoff = Calendar.getInstance();
                cutoff.add(Calendar.HOUR, -modifiedSinceHours);
                
                // Note: This requires pages to have cq:lastModified property
                queryBuilder.append(" AND [jcr:content/cq:lastModified] > CAST('")
                          .append(javax.jcr.ValueFactory.class.getSimpleName()) // Placeholder for proper date formatting
                          .append("' AS DATE)");
                
                LOG.info("Filtering pages modified in last {} hours", modifiedSinceHours);
            }
            
            String queryString = queryBuilder.toString();
            LOG.debug("Executing query: {}", queryString);
            
            Iterator<Resource> results = rr.findResources(queryString, Query.JCR_SQL2);
            
            while (results.hasNext()) {
                Resource page = results.next();
                pagePaths.add(page.getPath());
            }
            
            LOG.debug("Query found {} pages", pagePaths.size());
            
        } catch (Exception e) {
            LOG.error("Error querying for pages, falling back to traversal", e);
            // Fallback to traversal if query fails
            Resource root = rr.getResource(rootPath);
            if (root != null) {
                collectPagesRecursive(root, pagePaths);
            }
        }
        
        return pagePaths;
    }
    
    /**
     * Fallback method to collect pages via traversal
     */
    private void collectPagesRecursive(Resource resource, List<String> pagePaths) {
        String primaryType = resource.getValueMap().get("jcr:primaryType", String.class);
        if ("cq:Page".equals(primaryType)) {
            pagePaths.add(resource.getPath());
        }
        
        for (Resource child : resource.getChildren()) {
            collectPagesRecursive(child, pagePaths);
        }
    }

    /**
     * Processes a batch of pages (runs in parallel thread)
     */
    private void processBatch(List<String> pagePaths, AtomicInteger pagesProcessed, 
                             AtomicInteger pagesUpdated, AtomicInteger pagesSkipped,
                             AtomicInteger pagesFailed, AtomicLong totalNodesProcessed) {
        
        // Each thread gets its own ResourceResolver for thread safety
        Map<String, Object> authInfo = Collections.singletonMap(
            ResourceResolverFactory.SUBSERVICE, "nodecount-updater");
        
        try (ResourceResolver rr = resolverFactory.getServiceResourceResolver(authInfo)) {
            if (rr == null) {
                LOG.error("Failed to obtain ResourceResolver for batch processing");
                return;
            }
            
            int batchUpdates = 0;
            
            for (String pagePath : pagePaths) {
                try {
                    pagesProcessed.incrementAndGet();
                    
                    Resource pageResource = rr.getResource(pagePath);
                    if (pageResource == null) {
                        LOG.debug("Page not found: {}", pagePath);
                        continue;
                    }
                    
                    Resource jcrContent = pageResource.getChild("jcr:content");
                    if (jcrContent == null) {
                        LOG.debug("No jcr:content for page: {}", pagePath);
                        continue;
                    }
                    
                    // Count nodes and determine complexity
                    NodeCountResult result = countNodesWithComplexity(jcrContent);
                    totalNodesProcessed.addAndGet(result.count);
                    
                    // Update properties if changed
                    ModifiableValueMap props = jcrContent.adaptTo(ModifiableValueMap.class);
                    if (props != null) {
                        Long oldNodeCount = props.get("nodeCount", Long.class);
                        String oldComplexity = props.get("complexity", String.class);
                        
                        // Check if update needed
                        boolean countChanged = result.count != (oldNodeCount != null ? oldNodeCount : -1L);
                        boolean complexityChanged = result.complexity != null && !result.complexity.equals(oldComplexity);
                        
                        if (countChanged || complexityChanged) {
                            props.put("nodeCount", result.count);
                            props.put("complexity", result.complexity);
                            props.put("nodeCountLastUpdated", Calendar.getInstance());
                            
                            batchUpdates++;
                            pagesUpdated.incrementAndGet();
                            
                            LOG.debug("Updated: {} - nodeCount: {} -> {}, complexity: '{}' -> '{}'", 
                                    pagePath, oldNodeCount, result.count, oldComplexity, result.complexity);
                            
                            // Batch commit
                            if (batchUpdates >= batchCommitSize) {
                                rr.commit();
                                LOG.debug("Batch commit: {} pages", batchUpdates);
                                batchUpdates = 0;
                            }
                        } else {
                            pagesSkipped.incrementAndGet();
                        }
                    }
                    
                } catch (Exception e) {
                    LOG.error("Error processing page: {}", pagePath, e);
                    pagesFailed.incrementAndGet();
                    try {
                        rr.refresh(); // Recover from error
                    } catch (Exception refreshEx) {
                        LOG.error("Failed to refresh resolver", refreshEx);
                    }
                }
            }
            
            // Final commit for remaining updates
            if (batchUpdates > 0) {
                rr.commit();
                LOG.debug("Final batch commit: {} pages", batchUpdates);
            }
            
        } catch (LoginException e) {
            LOG.error("Failed to obtain ResourceResolver for batch", e);
        } catch (Exception e) {
            LOG.error("Error in batch processing", e);
        }
    }

    /**
     * OPTIMIZED: Single-pass counting that determines both node count and complexity
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
     * Counts all descendants of a resource using Oak's NodeCounter MBean.
     * 
     * This approach uses Oak's internal statistics instead of traversing the tree,
     * which provides significant performance benefits:
     * - Zero cache impact (no nodes loaded into cache)
     * - Constant time operation (O(1) vs O(n))
     * - No repository contention
     * 
     * Trade-off: Counts are approximate, not exact. Oak updates these statistics
     * asynchronously. For complexity assessment (low/medium/high), approximate
     * counts are sufficient and the performance gain is worth it.
     */
    private long countDescendants(Resource resource) {
        if (resource == null) {
            return 0;
        }
        
        String path = resource.getPath();
        
        try {
            // First try to use NodeCounter MBean for fast approximate count
            long count = getNodeCountFromMBean(path);
            
            if (count >= 0) {
                return count;
            }
            
            // Fallback: If MBean not available, use direct JCR query count
            return getNodeCountFromQuery(resource);
            
        } catch (Exception e) {
            LOG.warn("Error counting descendants for {}: {}", path, e.getMessage());
            return 0;
        }
    }
    
    /**
     * Gets approximate node count from Oak's NodeCounter MBean.
     * Returns -1 if MBean is not available or count cannot be determined.
     */
    private long getNodeCountFromMBean(String path) {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            
            // Oak NodeCounter MBean object name
            ObjectName mbeanName = new ObjectName("org.apache.jackrabbit.oak:name=NodeCounter,type=NodeCounter");
            
            if (server.isRegistered(mbeanName)) {
                // Invoke getEstimatedNodeCount operation with path parameter
                Long count = (Long) server.invoke(
                    mbeanName, 
                    "getEstimatedNodeCount",
                    new Object[]{path},
                    new String[]{String.class.getName()}
                );
                
                if (count != null && count >= 0) {
                    LOG.debug("MBean count for {}: {}", path, count);
                    return count;
                }
            } else {
                LOG.debug("NodeCounter MBean not available, using fallback");
            }
        } catch (Exception e) {
            LOG.debug("Could not get MBean count for {}: {}", path, e.getMessage());
        }
        
        return -1;
    }
    
    /**
     * Fallback method: Uses JCR query to count descendant nodes.
     * More expensive than MBean but still better than full traversal.
     */
    private long getNodeCountFromQuery(Resource resource) {
        try {
            Node node = resource.adaptTo(Node.class);
            if (node == null) {
                return 0;
            }
            
            // Use query to count descendants (excludes nested cq:Page nodes)
            String queryString = String.format(
                "SELECT * FROM [nt:base] AS node " +
                "WHERE ISDESCENDANTNODE(node, '%s') " +
                "AND node.[jcr:primaryType] <> 'cq:Page'",
                resource.getPath()
            );
            
            javax.jcr.query.QueryManager qm = node.getSession().getWorkspace().getQueryManager();
            javax.jcr.query.Query query = qm.createQuery(queryString, Query.JCR_SQL2);
            
            // Set limit to prevent counting millions of nodes
            query.setLimit(10000);
            
            javax.jcr.query.QueryResult result = query.execute();
            long count = result.getRows().getSize();
            
            LOG.debug("Query count for {}: {}", resource.getPath(), count);
            return count;
            
        } catch (Exception e) {
            LOG.warn("Query count failed for {}: {}", resource.getPath(), e.getMessage());
            return 0;
        }
    }
    
    /**
     * Determines complexity level based on count
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
     * Partitions a list into smaller sublists
     */
    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
    
    /**
     * Simple holder class for count and complexity results
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
