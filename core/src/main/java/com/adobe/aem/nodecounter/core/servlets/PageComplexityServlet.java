package com.adobe.aem.nodecounter.core.servlets;

import com.day.cq.wcm.api.Page;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.servlet.Servlet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Servlet to return page complexity information as JSON.
 * 
 * Endpoints:
 * - GET /bin/nodecounter/page?path=/content/mysite/page
 *   Returns node count and complexity for a single page
 * 
 * - GET /bin/nodecounter/pages?rootPath=/content/mysite&limit=100
 *   Returns node counts for all pages under rootPath
 * 
 * Example:
 * curl -u admin:admin "http://localhost:4502/bin/nodecounter/page?path=/content/wknd/us/en"
 */
@Component(
    service = Servlet.class,
    property = {
        "sling.servlet.methods=GET",
        "sling.servlet.paths=/bin/nodecounter/page",
        "sling.servlet.extensions=json"
    }
)
public class PageComplexityServlet extends SlingSafeMethodsServlet {
    
    private static final Logger LOG = LoggerFactory.getLogger(PageComplexityServlet.class);
    
    private static final int DEFAULT_HIGH_THRESHOLD = 2048;
    private static final int DEFAULT_MEDIUM_THRESHOLD = 1024;
    private static final int DEFAULT_LIMIT = 100;
    
    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) 
            throws IOException {
        
        ResourceResolver resolver = request.getResourceResolver();
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        // Get parameters
        String path = request.getParameter("path");
        String rootPath = request.getParameter("rootPath");
        int limit = getIntParameter(request, "limit", DEFAULT_LIMIT);
        int highThreshold = getIntParameter(request, "highThreshold", DEFAULT_HIGH_THRESHOLD);
        int mediumThreshold = getIntParameter(request, "mediumThreshold", DEFAULT_MEDIUM_THRESHOLD);
        
        try {
            if (path != null && !path.isEmpty()) {
                // Single page analysis
                JsonObjectBuilder result = analyzeSinglePage(resolver, path, highThreshold, mediumThreshold);
                if (result != null) {
                    response.getWriter().write(result.build().toString());
                } else {
                    sendError(response, 404, "Page not found: " + path);
                }
            } else if (rootPath != null && !rootPath.isEmpty()) {
                // Multiple pages analysis
                JsonObjectBuilder result = analyzeMultiplePages(resolver, rootPath, limit, highThreshold, mediumThreshold);
                response.getWriter().write(result.build().toString());
            } else {
                sendError(response, 400, "Missing required parameter: 'path' or 'rootPath'");
            }
        } catch (Exception e) {
            LOG.error("Error analyzing page complexity", e);
            sendError(response, 500, "Error: " + e.getMessage());
        }
    }
    
    /**
     * Analyze a single page
     */
    private JsonObjectBuilder analyzeSinglePage(ResourceResolver resolver, String pagePath, 
                                                 int highThreshold, int mediumThreshold) {
        Resource pageResource = resolver.getResource(pagePath);
        if (pageResource == null) {
            return null;
        }
        
        Page page = pageResource.adaptTo(Page.class);
        if (page == null) {
            return null;
        }
        
        Resource jcrContent = pageResource.getChild("jcr:content");
        if (jcrContent == null) {
            return null;
        }
        
        int nodeCount = countNodes(jcrContent);
        String complexity = determineComplexity(nodeCount, highThreshold, mediumThreshold);
        
        return Json.createObjectBuilder()
            .add("success", true)
            .add("path", page.getPath())
            .add("title", page.getTitle() != null ? page.getTitle() : page.getName())
            .add("name", page.getName())
            .add("nodeCount", nodeCount)
            .add("complexity", complexity)
            .add("lastModified", page.getLastModified() != null ? 
                page.getLastModified().getTimeInMillis() : 0)
            .add("thresholds", Json.createObjectBuilder()
                .add("high", highThreshold)
                .add("medium", mediumThreshold));
    }
    
    /**
     * Analyze multiple pages under a root path
     */
    private JsonObjectBuilder analyzeMultiplePages(ResourceResolver resolver, String rootPath, 
                                                    int limit, int highThreshold, int mediumThreshold) {
        Resource rootResource = resolver.getResource(rootPath);
        if (rootResource == null) {
            return Json.createObjectBuilder()
                .add("success", false)
                .add("error", "Root path not found: " + rootPath);
        }
        
        List<PageInfo> pages = new ArrayList<>();
        collectPages(rootResource, pages, limit);
        
        JsonArrayBuilder pagesArray = Json.createArrayBuilder();
        int highCount = 0;
        int mediumCount = 0;
        int lowCount = 0;
        
        for (PageInfo pageInfo : pages) {
            String complexity = determineComplexity(pageInfo.nodeCount, highThreshold, mediumThreshold);
            
            pagesArray.add(Json.createObjectBuilder()
                .add("path", pageInfo.path)
                .add("title", pageInfo.title)
                .add("nodeCount", pageInfo.nodeCount)
                .add("complexity", complexity));
            
            switch (complexity) {
                case "high": highCount++; break;
                case "medium": mediumCount++; break;
                case "low": lowCount++; break;
            }
        }
        
        return Json.createObjectBuilder()
            .add("success", true)
            .add("rootPath", rootPath)
            .add("totalPages", pages.size())
            .add("limitReached", pages.size() >= limit)
            .add("summary", Json.createObjectBuilder()
                .add("high", highCount)
                .add("medium", mediumCount)
                .add("low", lowCount))
            .add("thresholds", Json.createObjectBuilder()
                .add("high", highThreshold)
                .add("medium", mediumThreshold))
            .add("pages", pagesArray);
    }
    
    /**
     * Recursively collect pages
     */
    private void collectPages(Resource resource, List<PageInfo> pages, int limit) {
        if (pages.size() >= limit) {
            return;
        }
        
        Page page = resource.adaptTo(Page.class);
        if (page != null) {
            Resource jcrContent = resource.getChild("jcr:content");
            if (jcrContent != null) {
                int nodeCount = countNodes(jcrContent);
                pages.add(new PageInfo(page.getPath(), 
                    page.getTitle() != null ? page.getTitle() : page.getName(), 
                    nodeCount));
            }
        }
        
        // Recurse into children
        Iterator<Resource> children = resource.listChildren();
        while (children.hasNext() && pages.size() < limit) {
            collectPages(children.next(), pages, limit);
        }
    }
    
    /**
     * Count nodes recursively, excluding nested pages
     */
    private int countNodes(Resource resource) {
        if (resource == null) {
            return 0;
        }
        
        int count = 0;
        Iterator<Resource> children = resource.listChildren();
        
        while (children.hasNext()) {
            Resource child = children.next();
            String primaryType = child.getValueMap().get("jcr:primaryType", String.class);
            
            // Skip nested cq:Page nodes
            if ("cq:Page".equals(primaryType)) {
                continue;
            }
            
            count++;
            count += countNodes(child);
        }
        
        return count;
    }
    
    /**
     * Determine complexity level
     */
    private String determineComplexity(int nodeCount, int highThreshold, int mediumThreshold) {
        if (nodeCount > highThreshold) {
            return "high";
        } else if (nodeCount > mediumThreshold) {
            return "medium";
        } else {
            return "low";
        }
    }
    
    /**
     * Get integer parameter with default value
     */
    private int getIntParameter(SlingHttpServletRequest request, String paramName, int defaultValue) {
        String value = request.getParameter(paramName);
        if (value != null && !value.isEmpty()) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                LOG.warn("Invalid integer parameter {}: {}", paramName, value);
            }
        }
        return defaultValue;
    }
    
    /**
     * Send error response
     */
    private void sendError(SlingHttpServletResponse response, int status, String message) 
            throws IOException {
        response.setStatus(status);
        JsonObjectBuilder error = Json.createObjectBuilder()
            .add("success", false)
            .add("error", message);
        response.getWriter().write(error.build().toString());
    }
    
    /**
     * Helper class to store page information
     */
    private static class PageInfo {
        String path;
        String title;
        int nodeCount;
        
        PageInfo(String path, String title, int nodeCount) {
            this.path = path;
            this.title = title;
            this.nodeCount = nodeCount;
        }
    }
}

