package io.chef.jenkins.pushjobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Forces a chef-client run on 1..n Chef 360 managed nodes by submitting a job
 * instance to the Chef Courier orchestrator API, then (optionally) polling the
 * Courier state service for completion. This is the Chef Push Jobs replacement,
 * built as a native Jenkins build step instead of a shell-script pipeline.
 */
public class ForceChefClientRunBuilder extends Builder implements SimpleBuildStep {

    private final String chef360BaseUrl;
    private final String chef360OrgId;
    private final String chef360TenantId;
    private final String credentialsId;

    private String nodeIds = "";
    private String tagName = "role";
    private String tagValue = "";
    private String executionType = "parallel";
    private int timeoutSeconds = 900;
    private int successPercent = 100;
    private String interpreterName = "chef/courier-interpreter/chef-client";
    private String interpreterMinVersion = "1.0.0";
    private String interpreterMaxVersion = "2.0.0";
    private boolean waitForCompletion = true;
    private int pollIntervalSeconds = 15;
    private int pollTimeoutSeconds = 1800;
    private String orchestratorPath = "/courier/orchestrator-api/v1";
    private String statePath = "/courier/state-api/v1";
    private String nodeManagementPath = "/node/management/v1";
    private String tagNamespace = "tags";

    @DataBoundConstructor
    public ForceChefClientRunBuilder(String chef360BaseUrl, String chef360OrgId, String chef360TenantId, String credentialsId) {
        this.chef360BaseUrl = trimTrailingSlash(chef360BaseUrl);
        this.chef360OrgId = chef360OrgId;
        this.chef360TenantId = chef360TenantId;
        this.credentialsId = credentialsId;
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public String getChef360BaseUrl() { return chef360BaseUrl; }
    public String getChef360OrgId() { return chef360OrgId; }
    public String getChef360TenantId() { return chef360TenantId; }
    public String getCredentialsId() { return credentialsId; }

    public String getNodeIds() { return nodeIds; }
    @DataBoundSetter
    public void setNodeIds(String nodeIds) { this.nodeIds = nodeIds; }

    public String getTagName() { return tagName; }
    @DataBoundSetter
    public void setTagName(String tagName) { this.tagName = tagName; }

    public String getTagValue() { return tagValue; }
    @DataBoundSetter
    public void setTagValue(String tagValue) { this.tagValue = tagValue; }

    public String getExecutionType() { return executionType; }
    @DataBoundSetter
    public void setExecutionType(String executionType) { this.executionType = executionType; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    @DataBoundSetter
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public boolean isWaitForCompletion() { return waitForCompletion; }
    @DataBoundSetter
    public void setWaitForCompletion(boolean waitForCompletion) { this.waitForCompletion = waitForCompletion; }

    public int getPollIntervalSeconds() { return pollIntervalSeconds; }
    @DataBoundSetter
    public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }

    public int getPollTimeoutSeconds() { return pollTimeoutSeconds; }
    @DataBoundSetter
    public void setPollTimeoutSeconds(int pollTimeoutSeconds) { this.pollTimeoutSeconds = pollTimeoutSeconds; }

    public String getInterpreterName() { return interpreterName; }
    @DataBoundSetter
    public void setInterpreterName(String interpreterName) { this.interpreterName = interpreterName; }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener)
            throws InterruptedException, IOException {
        PrintStream log = listener.getLogger();
        ObjectMapper mapper = new ObjectMapper();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

        StringCredentials apiKeyCred = CredentialsProvider.findCredentialById(credentialsId, StringCredentials.class, run);
        if (apiKeyCred == null) {
            throw new IOException("Could not resolve credentials '" + credentialsId + "'. Expected a 'Secret text' credential holding 'apiKey:apiSecret'.");
        }
        String[] keyAndSecret = apiKeyCred.getSecret().getPlainText().split(":", 2);
        if (keyAndSecret.length != 2) {
            throw new IOException("Credential '" + credentialsId + "' must be formatted as 'apiKey:apiSecret'.");
        }
        String apiKey = keyAndSecret[0];
        String apiSecret = keyAndSecret[1];

        List<String> ids = resolveNodeIds(http, mapper, apiKey, apiSecret, log);
        if (ids.isEmpty()) {
            throw new IOException("No target nodes resolved (check nodeIds or tagName/tagValue).");
        }

        String instanceId = UUID.randomUUID().toString();
        String jobId = UUID.randomUUID().toString();
        String payload = buildJobInstancePayload(mapper, instanceId, jobId, ids);

        log.println("[chef360] Submitting Courier job instance " + instanceId + " for " + ids.size() + " node(s): " + ids);

        HttpRequest submit = baseRequestBuilder(chef360BaseUrl + orchestratorPath + "/job-instances", apiKey, apiSecret)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> submitResponse = send(http, submit);
        log.println("[chef360] Orchestrator response: HTTP " + submitResponse.statusCode());
        if (submitResponse.statusCode() != 200 && submitResponse.statusCode() != 201) {
            log.println(submitResponse.body());
            throw new IOException("Failed to submit job instance (HTTP " + submitResponse.statusCode() + ")");
        }

        if (!waitForCompletion) {
            return;
        }

        log.println("[chef360] Polling for completion (timeout " + pollTimeoutSeconds + "s)...");
        long deadline = System.currentTimeMillis() + pollTimeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            HttpRequest statusReq = baseRequestBuilder(chef360BaseUrl + statePath + "/instance/" + instanceId, apiKey, apiSecret)
                    .GET().build();
            HttpResponse<String> statusResp = send(http, statusReq);
            if (statusResp.statusCode() == 200) {
                JsonNode item = mapper.readTree(statusResp.body()).path("item");
                String status = item.path("status").asText("");
                log.println("[chef360]   instance status: " + status);
                if ("success".equals(status)) {
                    return;
                }
                if ("failure".equals(status)) {
                    throw new IOException("Job instance " + instanceId + " reported failure");
                }
            }
            Thread.sleep(pollIntervalSeconds * 1000L);
        }
        throw new IOException("Timed out waiting for job instance " + instanceId + " to complete");
    }

    private List<String> resolveNodeIds(HttpClient http, ObjectMapper mapper, String apiKey, String apiSecret, PrintStream log) throws IOException, InterruptedException {
        if (nodeIds != null && !nodeIds.trim().isEmpty()) {
            return Arrays.stream(nodeIds.split("[,\\n]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }

        log.println("[chef360] Resolving nodes by tag " + tagName + "=" + tagValue + "...");
        ObjectNode attr = mapper.createObjectNode();
        attr.put("name", tagName);
        ArrayNode ns = attr.putArray("namespace");
        ns.add(tagNamespace);
        ArrayNode val = attr.putArray("value");
        val.add(tagValue);
        attr.put("operator", "=");

        ObjectNode attributes = mapper.createObjectNode();
        ArrayNode attributesArr = attributes.putArray("attributes");
        attributesArr.add(attr);

        ObjectNode filterBody = mapper.createObjectNode();
        filterBody.set("constraints", attributes);

        HttpRequest req = baseRequestBuilder(chef360BaseUrl + nodeManagementPath + "/filters/exec", apiKey, apiSecret)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(filterBody)))
                .build();
        HttpResponse<String> resp = send(http, req);
        if (resp.statusCode() != 200) {
            throw new IOException("Node filter lookup failed (HTTP " + resp.statusCode() + "): " + resp.body());
        }
        JsonNode items = mapper.readTree(resp.body()).path("items");
        List<String> result = new ArrayList<>();
        for (JsonNode n : items) {
            result.add(n.path("id").asText());
        }
        log.println("[chef360] Resolved node IDs: " + result);
        return result;
    }

    private String buildJobInstancePayload(ObjectMapper mapper, String instanceId, String jobId, List<String> nodeIdList) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("Id", instanceId);
        root.put("jobId", jobId);
        root.put("name", "force-chef-client-run");

        ObjectNode target = mapper.createObjectNode();
        target.put("executionType", executionType);
        ArrayNode groups = target.putArray("groups");
        ObjectNode group = mapper.createObjectNode();
        group.put("timeoutSeconds", timeoutSeconds);
        ObjectNode batchSize = mapper.createObjectNode();
        batchSize.put("type", "number");
        batchSize.put("value", nodeIdList.size());
        group.set("batchSize", batchSize);
        group.put("distributionMethod", "batching");
        group.put("earlyTermination", false);
        ArrayNode successCriteria = group.putArray("successCriteria");
        ObjectNode criterion = mapper.createObjectNode();
        criterion.put("status", "success");
        ObjectNode numRuns = mapper.createObjectNode();
        numRuns.put("type", "percent");
        numRuns.put("value", successPercent);
        criterion.set("numRuns", numRuns);
        successCriteria.add(criterion);
        group.put("nodeListType", "nodes");
        ArrayNode nodeIdentifiers = group.putArray("nodeIdentifiers");
        nodeIdList.forEach(nodeIdentifiers::add);
        groups.add(group);
        root.set("target", target);

        ObjectNode actions = mapper.createObjectNode();
        actions.put("accessMode", "agent");
        ArrayNode steps = actions.putArray("steps");
        ObjectNode step = mapper.createObjectNode();
        step.put("name", "run-chef-client");
        step.put("description", "Force a chef-client run (Chef Push Jobs replacement)");
        ObjectNode interpreter = mapper.createObjectNode();
        interpreter.put("name", interpreterName);
        ObjectNode skill = mapper.createObjectNode();
        skill.put("minVersion", interpreterMinVersion);
        skill.put("maxVersion", interpreterMaxVersion);
        interpreter.set("skill", skill);
        step.set("interpreter", interpreter);
        ObjectNode command = mapper.createObjectNode();
        command.put("exec", "chef-client");
        step.set("command", command);
        step.put("retryCount", 1);
        steps.add(step);
        actions.set("steps", steps);
        root.set("actions", actions);

        return mapper.writeValueAsString(root);
    }

    private HttpRequest.Builder baseRequestBuilder(String url, String apiKey, String apiSecret) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("api-key", apiKey)
                .header("api-secret", apiSecret)
                .header("OrganizationId", chef360OrgId)
                .header("TenantId", chef360TenantId);
    }

    private HttpResponse<String> send(HttpClient http, HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Force chef-client run (Chef 360 Courier)";
        }

        public FormValidation doCheckChef360BaseUrl(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Base URL is required, e.g. https://internal.cloud.chef.io");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckTagValue(@QueryParameter String value, @QueryParameter String nodeIds) {
            if ((nodeIds == null || nodeIds.trim().isEmpty()) && (value == null || value.trim().isEmpty())) {
                return FormValidation.warning("Set either nodeIds or tagName/tagValue to target nodes.");
            }
            return FormValidation.ok();
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item, @QueryParameter String credentialsId) {
            StandardListBoxModel result = new StandardListBoxModel();
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(credentialsId);
                }
            } else {
                if (!item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                    return result.includeCurrentValue(credentialsId);
                }
            }
            var result2 = result.includeEmptyValue();
            if (item != null) {
                result2 = result2.includeAs(ACL.SYSTEM2, item, StringCredentials.class);
            } else {
                result2 = result2.includeAs(ACL.SYSTEM2, Jenkins.get(), StringCredentials.class);
            }
            return result2.includeCurrentValue(credentialsId);
        }

        public ListBoxModel doFillExecutionTypeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("parallel", "parallel");
            items.add("sequential", "sequential");
            return items;
        }
    }
}
