package com.adobe.aem.nodecounter.core.config;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "AEM Node Counter Scheduler Config")
public @interface PageNodeCountSchedulerConfig {
    @AttributeDefinition(
        name = "Enabled",
        description = "Enable or disable the node counter job"
    )
    boolean enabled() default true;

    @AttributeDefinition(
        name = "Content Root Path",
        description = "The root path under which pages will be checked (e.g. /content/mysite)"
    )
    String rootPath() default "/content";

    @AttributeDefinition(
        name = "Scheduler Cron Expression",
        description = "CRON expression to run the job (e.g. '0 0 0 * * ?' for daily at midnight, '0 0 * * * ?' for every hour, '0 * * * * ?' for every minute)"
    )
    String scheduler_expression() default "0 0 0 * * ?";

    @AttributeDefinition(
        name = "High Complexity Threshold",
        description = "Node count threshold for HIGH complexity (default: 2048). Pages with more nodes than this value are marked as 'high'"
    )
    int highThreshold() default 2048;

    @AttributeDefinition(
        name = "Medium Complexity Threshold",
        description = "Node count threshold for MEDIUM complexity (default: 1024). Pages with more nodes than this value (but less than high threshold) are marked as 'medium'"
    )
    int mediumThreshold() default 1024;
    
    @AttributeDefinition(
        name = "Thread Pool Size",
        description = "Number of parallel threads for processing pages (default: 4). Higher values = faster processing but more CPU/memory usage"
    )
    int threadPoolSize() default 4;
    
    @AttributeDefinition(
        name = "Max Pages Per Run",
        description = "Maximum number of pages to process in a single run (default: 5000). Set to 0 for unlimited. Helps prevent long-running jobs"
    )
    int maxPagesPerRun() default 5000;
    
    @AttributeDefinition(
        name = "Batch Commit Size",
        description = "Number of page updates before committing to JCR (default: 50). Higher values = better performance but more memory usage"
    )
    int batchCommitSize() default 50;
    
    @AttributeDefinition(
        name = "Process Only Modified Pages",
        description = "If true, only processes pages modified since last run (default: false). Dramatically improves performance for subsequent runs"
    )
    boolean processOnlyModified() default false;
    
    @AttributeDefinition(
        name = "Modified Since Hours",
        description = "When 'Process Only Modified Pages' is enabled, only process pages modified in the last N hours (default: 24)"
    )
    int modifiedSinceHours() default 24;
}

