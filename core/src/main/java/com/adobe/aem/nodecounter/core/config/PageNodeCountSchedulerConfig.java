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
}

