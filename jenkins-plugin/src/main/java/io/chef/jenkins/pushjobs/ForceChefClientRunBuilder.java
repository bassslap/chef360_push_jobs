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
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

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
    private String caFile = "";
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
    private String courierCliPath = "/usr/local/bin/chef-courier-cli";

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

    public String getCaFile() { return caFile; }
    @DataBoundSetter
    public void setCaFile(String caFile) { this.caFile = caFile; }

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

    public String getCourierCliPath() { return courierCliPath; }
    @DataBoundSetter
    public void setCourierCliPath(String courierCliPath) { this.courierCliPath = courierCliPath; }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener)
            throws InterruptedException, IOException {
        PrintStream log = listener.getLogger();
        ObjectMapper mapper = new ObjectMapper();
        HttpClient.Builder httpBuilder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30));
        if (caFile != null && !caFile.trim().isEmpty()) {
            httpBuilder.sslContext(sslContextForCa(Path.of(caFile.trim())));
            log.println("[chef360] Using custom CA certificate: " + caFile.trim());
        }
        HttpClient http = httpBuilder.build();

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

        submitViaCourierCli(mapper, payload, apiKey, apiSecret, log);
    }

    private void submitViaCourierCli(ObjectMapper mapper, String payload, String apiKey,
                                     String apiSecret, PrintStream log) throws IOException, InterruptedException {
        String cliPath = courierCliPath == null || courierCliPath.trim().isEmpty()
            ? "/usr/local/bin/chef-courier-cli" : courierCliPath.trim();
        Path home = Files.createTempDirectory("chef360-courier-");
        Path credentialsDir = home.resolve(".chef-platform");
        Files.createDirectories(credentialsDir);
        String profile = "[chef-org]\n"
                + "DeviceId = \"jenkins\"\n"
                + "Url = \"" + chef360BaseUrl + "\"\n"
                + "AccessKey = \"" + apiKey + "\"\n"
                + "SecretKey = \"" + apiSecret + "\"\n"
                + "TenantId = \"" + chef360TenantId + "\"\n"
                + "OrgName = \"chef-org\"\n"
                + "OrgId = \"" + chef360OrgId + "\"\n";
        Files.write(credentialsDir.resolve("credentials"),
            Base64.getEncoder().encode(profile.getBytes(StandardCharsets.UTF_8)));
        Path jobFile = home.resolve("job.json");
        ObjectNode job = (ObjectNode) mapper.readTree(payload);
        job.put("name", "jenkins-force-chef-client-" + UUID.randomUUID());
        job.put("description", "Jenkins force chef-client run");
        job.put("scheduleRule", "RRULE:FREQ=DAILY;INTERVAL=1");
        job.put("allowManualExecution", true);
        ArrayNode exceptions = job.putArray("exceptionRules");
        ObjectNode exception = exceptions.addObject();
        exception.put("duration", 1);
        exception.put("rrule", "RRULE:FREQ=WEEKLY;BYDAY=SU");
        Files.writeString(jobFile, mapper.writeValueAsString(job), StandardCharsets.UTF_8);

        try {
            log.println("[chef360] Creating Courier scheduler job through " + cliPath + "...");
            JsonNode created = runCourierCli(home, mapper, log,
                    "scheduler", "jobs", "add-job", "--profile", "chef-org",
                    "--body-file", jobFile.toString(), "--format", "json");
            String jobId = created.path("item").path("id").asText("");
            if (jobId.isEmpty()) {
                throw new IOException("Courier scheduler did not return a job id: " + created);
            }

            ObjectNode manual = mapper.createObjectNode();
            manual.put("jobId", jobId);
            manual.put("orgId", chef360OrgId);
            manual.put("tenantId", chef360TenantId);
            manual.put("triggeredAt", java.time.Instant.now().toString());
            manual.put("triggeredBy", "jenkins");
            Path manualFile = home.resolve("manual.json");
            Files.writeString(manualFile, mapper.writeValueAsString(manual), StandardCharsets.UTF_8);
            JsonNode triggered = runCourierCli(home, mapper, log,
                    "scheduler", "jobs", "create-manual-job", "--profile", "chef-org",
                    "--body-file", manualFile.toString(), "--format", "json");
            log.println("[chef360] Courier manual job accepted: " + triggered);
            if (waitForCompletion) {
                pollCourierCli(home, mapper, jobId, log);
            }
        } finally {
            deleteTree(home);
        }
    }

    private JsonNode runCourierCli(Path home, ObjectMapper mapper, PrintStream log, String... args)
            throws IOException, InterruptedException {
        String cliPath = courierCliPath == null || courierCliPath.trim().isEmpty()
            ? "/usr/local/bin/chef-courier-cli" : courierCliPath.trim();
        List<String> command = new ArrayList<>();
        command.add(cliPath);
        command.addAll(Arrays.asList(args));
        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        processBuilder.environment().put("HOME", home.toString());
        processBuilder.environment().put("PATH", "/usr/local/bin:/usr/bin:/bin");
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("Courier CLI failed (" + exit + "): " + output);
        }
        return mapper.readTree(output);
    }

    private void pollCourierCli(Path home, ObjectMapper mapper, String jobId, PrintStream log)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + pollTimeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            JsonNode result = runCourierCli(home, mapper, log,
                    "state", "instance", "list-all", "--profile", "chef-org",
                    "--job-id", jobId, "--pagination.size", "10", "--format", "json");
            JsonNode items = result.path("items");
            if (items.isArray() && items.size() > 0) {
                JsonNode latest = items.get(items.size() - 1);
                String status = latest.path("status").asText("");
                log.println("[chef360]   instance status: " + status);
                if ("success".equals(status)) return;
                if ("failure".equals(status)) {
                    throw new IOException("Courier job instance reported failure: " + latest);
                }
            }
            Thread.sleep(pollIntervalSeconds * 1000L);
        }
        throw new IOException("Timed out waiting for Courier job completion");
    }

    private void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
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

        String effectiveInterpreterName = "chef/courier-interpreter/chef-client".equals(interpreterName)
            ? "chef-platform/chef-client-interpreter" : interpreterName;

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
        interpreter.put("name", effectiveInterpreterName);
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

    private SSLContext sslContextForCa(Path caPath) throws IOException {
        try (InputStream certificateInput = Files.newInputStream(caPath)) {
            Certificate certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(certificateInput);
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("chef360-custom-ca", certificate);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trustStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers.getTrustManagers(), null);
            return sslContext;
        } catch (Exception e) {
            throw new IOException("Could not load CA certificate '" + caPath + "'", e);
        }
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
